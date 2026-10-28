package thumbnailer.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.index.Reindexer;
import thumbnailer.ops.Op;
import thumbnailer.ops.OpProgress;
import thumbnailer.ops.OpResult;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;

/** Against a running server: image URLs of another revision or render epoch, and the socket's hello. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransportTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var id = UUID.randomUUID();
        registry.add("thumbnailer.data-dir", () -> Path.of("build/test-data", id.toString()).toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + id + ";DB_CLOSE_DELAY=-1");
    }

    @Autowired Reindexer scans; // its synchronous scan is public
    @Autowired AlbumRepository repo;
    @Autowired AlbumService albums;
    @Autowired SettingsStore settings;
    @Autowired ApplicationEventPublisher events;
    @LocalServerPort int port;
    @TempDir Path root;

    @Test
    void anImageUrlOfAnotherRevisionGetsNoBytesAndOneOfAnotherRenderEpochIsNotStored() throws Exception {
        Files.createDirectories(root.resolve("A"));
        for (int i = 0; i < 2; i++) {
            var out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(80 + 16 * i, 60, BufferedImage.TYPE_INT_RGB), "jpg", out);
            Files.write(root.resolve("A/" + i + ".jpg"), out.toByteArray());
        }
        settings.set(new Settings(List.of(new Settings.Root(root.toString(), List.of())), List.of(), false, null, null, null));
        scans.scan(null, () -> false);
        var a = repo.byRoot(Containers.normalize(root)).getFirst();
        var epoch = "&r=" + ImageController.renderEpoch(settings.get());
        for (var path : List.of("/cover", "/0/thumb", "/0")) {
            var stale = get(a, path + "?v=" + (a.revision() - 1));
            assertThat(stale.statusCode()).as(path).isEqualTo(409);
            assertThat(stale.headers().firstValue("Cache-Control")).as(path).hasValue("no-store");
            assertThat(stale.body()).as(path).isEmpty();
            for (var url : List.of(path + "?v=" + a.revision(), path)) {
                var served = get(a, url);
                assertThat(served.statusCode()).as(url).isEqualTo(200);
                assertThat(served.headers().firstValue("Cache-Control")).as(url).hasValue("max-age=86400, public");
                assertThat(served.body()).as(url).isNotEmpty();
            }
        }
        for (var path : List.of("/cover", "/0/thumb")) {
            var current = get(a, path + "?v=" + a.revision() + epoch);
            assertThat(current.headers().firstValue("Cache-Control")).as(path).hasValue("max-age=86400, public");
            var other = get(a, path + "?v=" + a.revision() + "&r=0");
            assertThat(other.statusCode()).as(path).isEqualTo(200);
            assertThat(other.headers().firstValue("Cache-Control")).as(path).hasValue("no-store");
            assertThat(other.body()).as(path).isEqualTo(current.body());
            var etag = current.headers().firstValue("ETag").orElseThrow();
            var unchanged = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/img/"
                    + a.id() + path + "?v=" + a.revision() + epoch)).header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(unchanged.statusCode()).as(path).isEqualTo(304);
        }
        CompletableFuture.runAsync(() -> albums.quiesce(List.of(a.id()))).get(2, TimeUnit.SECONDS); // no read left open
        albums.release(List.of(a.id()));
    }

    @Test
    void aSocketHearsHelloFirstWithItsLibraryAndTheOperationInHand() throws Exception {
        var hello = "{\"type\":\"hello\",\"library\":\"" + Containers.normalize(settings.dataDir()) + "\",\"scan\":";
        var first = new CopyOnWriteArrayList<String>();
        var socket = connect(first);
        assertThat(await(first, m -> true)).startsWith(hello).endsWith(",\"op\":null,\"opResult\":null}");

        events.publishEvent(new OpProgress("probe", Op.Kind.MOVE, true, 2, 1, "a", 0, 0, null, null, null));
        assertThat(await(first, m -> m.startsWith("{\"type\":\"opProgress\""))).contains("\"id\":\"probe\"");
        var second = new CopyOnWriteArrayList<String>();
        var later = connect(second);
        assertThat(await(second, m -> true)).startsWith(hello)
                .contains(",\"op\":{\"id\":\"probe\",\"kind\":\"MOVE\",\"running\":true,").endsWith(",\"opResult\":null}");

        events.publishEvent(new OpResult("probe", 1, List.of(), List.of()));
        await(first, m -> m.startsWith("{\"type\":\"opResult\""));
        var third = new CopyOnWriteArrayList<String>();
        var last = connect(third);
        assertThat(await(third, m -> true)).startsWith(hello)
                .endsWith(",\"op\":null,\"opResult\":{\"id\":\"probe\",\"done\":1,\"skipped\":[],\"failed\":[]}}");
        assertThat(first.getFirst()).startsWith(hello);
        for (var ws : List.of(socket, later, last)) ws.abort();
    }

    private HttpResponse<byte[]> get(Album album, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/img/" + album.id() + path)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private WebSocket connect(List<String> received) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        received.add(data.toString());
                        ws.request(1);
                        return CompletableFuture.completedFuture(null);
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    private static String await(List<String> received, Predicate<String> match) throws InterruptedException {
        for (long end = System.nanoTime() + 5_000_000_000L; System.nanoTime() < end; Thread.sleep(10)) {
            for (var m : received) if (match.test(m)) return m;
        }
        throw new AssertionError("No matching message in " + received);
    }
}
