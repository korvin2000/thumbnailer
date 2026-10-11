package thumbnailer.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;

/** Scanning end to end against real files, an in-memory H2 and the real MVStore. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScanTest {

    private static final AtomicInteger TESTS = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var id = UUID.randomUUID();
        registry.add("thumbnailer.data-dir", () -> Path.of("build/test-data", id.toString()).toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + id + ";DB_CLOSE_DELAY=-1");
    }

    @Autowired ScanService scans;
    @Autowired AlbumRepository repo;
    @Autowired AlbumService albums;
    @Autowired SettingsStore settings;
    @LocalServerPort int port;
    @TempDir Path root;
    /** Distinct image sizes per test, so no test's album looks like another test's moved album. */
    private final int salt = TESTS.incrementAndGet() * 8;

    @Test
    void reconcilesAddedChangedMovedAndRemovedAlbums() throws IOException {
        dir(root.resolve("A"), "a.jpg", "b.jpg", "c.jpg");
        zip(root.resolve("B.zip"), "a.jpg", "b.jpg", "c.jpg");
        dir(root.resolve("C"), "a.jpg", "b.jpg");
        var first = scan(() -> false);
        assertThat(first.changed()).isEqualTo(3);
        var a = byName("A");
        var b = byName("B");
        albums.patch(a.id(), new AlbumPatch(null, null, null, null, null, null, 7, null));

        assertThat(scan(() -> false).changed()).as("unchanged rescan").isZero();

        dir(root.resolve("A"), "d.jpg");
        Files.createDirectories(root.resolve("sub"));
        Files.move(root.resolve("B.zip"), root.resolve("sub/B.zip"));
        assertThat(scan(() -> false).changed()).isEqualTo(2);
        var changed = repo.find(a.id()).orElseThrow();
        assertThat(changed.imageCount()).isEqualTo(4);
        assertThat(changed.rating()).as("user state survives re-index").isEqualTo(7);
        assertThat(changed.revision()).isGreaterThan(a.revision() + 1);
        var moved = repo.find(b.id()).orElseThrow();
        assertThat(moved.path()).isEqualTo(Containers.normalize(root.resolve("sub/B.zip")));
        assertThat(repo.byRoot(normalizedRoot())).hasSize(3);

        var c = byName("C");
        for (var f : List.of("a.jpg", "b.jpg")) Files.delete(root.resolve("C").resolve(f));
        Files.delete(root.resolve("C"));
        var checks = new AtomicInteger();
        var cancelled = scan(() -> checks.incrementAndGet() > 3);
        assertThat(cancelled.message()).isEqualTo("Cancelled");
        assertThat(repo.find(c.id())).as("a cancelled scan prunes nothing").isPresent();

        assertThat(scan(() -> false).removed()).isEqualTo(1);
        assertThat(repo.find(c.id())).isEmpty();
        assertThat(repo.find(a.id())).isPresent();
    }

    @Test
    void renamedAlbumKeepsItsIdentityAndTakesTheNewName() throws IOException {
        var old = root.resolve("CLS-2019-01-14 - Aurora - (vol-5) 1800px (x2) 1Mb.zip");
        zip(old, "a.jpg", "b.jpg");
        scan(() -> false);
        var before = byName("CLS-2019-01-14 - Aurora - (vol-5)");
        albums.patch(before.id(), new AlbumPatch(null, null, null, null, null, null, 6, null));

        Files.move(old, root.resolve("CLS-2020-02-02 - EL-Studio - Jeny - (vol-6).zip"));
        assertThat(scan(() -> false).changed()).isEqualTo(1);
        var after = repo.find(before.id()).orElseThrow();
        assertThat(after.name()).isEqualTo("CLS-2020-02-02 - EL-Studio - Jeny - (vol-6)");
        assertThat(after.model()).isEqualTo("Jeny");
        assertThat(after.studio()).isEqualTo("EL-Studio");
        assertThat(after.shotDate()).isEqualTo(LocalDate.of(2020, 2, 2));
        assertThat(after.rating()).as("edits survive the rename").isEqualTo(6);
        assertThat(repo.byRoot(normalizedRoot())).hasSize(1);
    }

    @Test
    void exclusionsApplyInsideContainers() throws IOException {
        dir(root.resolve("X"), "a.jpg", "b.jpg", "c_tmp.jpg");
        zip(root.resolve("Y.zip"), "a.jpg", "b.jpg", "raw/c.jpg");
        scan(() -> false, "*_tmp*", "*/raw/*");
        assertThat(byName("X").imageCount()).isEqualTo(2);
        assertThat(byName("Y").imageCount()).isEqualTo(2);
    }

    @Test
    void overlappingRootsAreRejected() {
        var outer = normalizedRoot();
        var inner = outer + "/sub";
        assertThatThrownBy(() -> settings.set(new Settings(List.of(new Settings.Root(outer, List.of()),
                new Settings.Root(inner, List.of())), List.of(), false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(outer).hasMessageContaining(inner);
    }

    @Test
    void userEditsSurviveExtractedRefresh() throws IOException {
        var album = root.resolve("E");
        dir(album, "a.jpg", "b.jpg");
        Files.writeString(album.resolve("album.txt"), "model: Alice\nstudio: S1\ndescription: First\n#wedding #outdoor");
        scan(() -> false);
        var e = byName("E");
        assertThat(e.model()).isEqualTo("Alice");
        assertThat(e.tags()).containsExactly("wedding", "outdoor");

        albums.patch(e.id(), new AlbumPatch(null, "Bob", null, null, "", List.of("wedding"), null, "b.jpg"));
        Files.writeString(album.resolve("album.txt"), "model: Carol\nstudio: S2\ndescription: Second\n#wedding #outdoor #beach");
        assertThat(scan(() -> false).changed()).isEqualTo(1);

        var refreshed = repo.find(e.id()).orElseThrow();
        assertThat(refreshed.model()).as("override kept").isEqualTo("Bob");
        assertThat(refreshed.studio()).as("extracted refreshed").isEqualTo("S2");
        assertThat(refreshed.description()).as("\"\" clears").isNull();
        assertThat(refreshed.tags()).as("a removed tag does not return").containsExactly("wedding");
        assertThat(refreshed.cover()).isEqualTo("b.jpg");
        assertThat(refreshed.coverPinned()).isTrue();

        var unpinned = albums.patch(e.id(), new AlbumPatch(null, null, null, null, null, null, null, ""));
        assertThat(unpinned.coverPinned()).isFalse();
        assertThat(unpinned.revision()).isEqualTo(refreshed.revision() + 1);
    }

    @Test
    void socketEchoesIdsHidesServerFieldsAndDropsRemovedRoots() throws Exception {
        dir(root.resolve("W"), "a.jpg", "b.jpg");
        scan(() -> false);
        var w = byName("W");
        var received = new CopyOnWriteArrayList<String>();
        var socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        received.add(data.toString());
                        ws.request(1);
                        return CompletableFuture.completedFuture(null);
                    }
                }).get(5, TimeUnit.SECONDS);
        socket.sendText("{\"type\":\"search\",\"id\":7,\"query\":{\"text\":\"w\",\"roots\":[\"" + normalizedRoot()
                + "\"],\"sort\":\"NAME\",\"offset\":0,\"limit\":10}}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":7"))).startsWith("{\"type\":\"results\"")
                .contains("\"total\":1", "\"name\":\"W\"", "\"kind\":\"DIRECTORY\"").doesNotContain("internal", "signature");
        socket.sendText("{\"type\":\"nope\",\"id\":\"x\"}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":\"x\""))).contains("\"type\":\"error\"");

        socket.sendText("{\"type\":\"setSettings\",\"id\":9,\"settings\":{\"roots\":[],\"exclusions\":[],\"scanOnStart\":false}}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":9"))).startsWith("{\"type\":\"settings\"");
        await(received, m -> m.equals("{\"type\":\"albumRemoved\",\"albumId\":\"" + w.id() + "\"}"));
        assertThat(repo.byRoot(normalizedRoot())).isEmpty();
        socket.abort();
    }

    private ScanProgress scan(BooleanSupplier cancelled, String... exclusions) throws IOException {
        settings.set(new Settings(List.of(new Settings.Root(root.toString(), List.of())), List.of(exclusions), false));
        return scans.scan(null, cancelled);
    }

    private String normalizedRoot() { return Containers.normalize(root); }

    private Album byName(String name) {
        return repo.byRoot(normalizedRoot()).stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static String await(List<String> received, Predicate<String> match) throws InterruptedException {
        for (long end = System.nanoTime() + 5_000_000_000L; System.nanoTime() < end; Thread.sleep(10)) {
            for (var m : received) if (match.test(m)) return m;
        }
        throw new AssertionError("No matching message in " + received);
    }

    private void dir(Path dir, String... names) throws IOException {
        Files.createDirectories(dir);
        for (int i = 0; i < names.length; i++) Files.write(dir.resolve(names[i]), jpeg(i));
    }

    private void zip(Path file, String... names) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < names.length; i++) {
                out.putNextEntry(new ZipEntry(names[i]));
                out.write(jpeg(i));
            }
        }
    }

    /** Distinct sizes per index, so "smallest" covers are deterministic. */
    private byte[] jpeg(int i) throws IOException {
        var img = new BufferedImage(64 + salt + 32 * i, 48 + 24 * i, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
