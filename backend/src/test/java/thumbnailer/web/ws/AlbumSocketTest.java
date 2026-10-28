package thumbnailer.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumEvent;
import thumbnailer.catalog.CatalogEvent;
import thumbnailer.catalog.EntityKind;
import thumbnailer.index.ScanProgress;
import thumbnailer.ops.Op;
import thumbnailer.ops.OpProgress;
import thumbnailer.ops.OpResult;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.spi.Kind;
import tools.jackson.databind.json.JsonMapper;

/** The broadcast outbox: each session's hello first, coalescing behind a slow drain, the overflow. */
class AlbumSocketTest {

    private static final String FENCE = "{\"type\":\"catalogChanged\",\"kinds\":[\"LIST\"]}";

    @TempDir Path dir;
    private final List<AlbumSocket> sockets = new ArrayList<>();
    private String library;

    @AfterEach
    void close() { sockets.forEach(AlbumSocket::close); }

    @Test
    void helloComesFirstWithTheLibraryAndTheLastScanAndOperationState() throws Exception {
        var socket = socket();
        var first = new Client("1", new CountDownLatch(0));
        socket.afterConnectionEstablished(first.session);
        assertThat(first.await(1)).containsExactly(
                "{\"type\":\"hello\",\"library\":\"" + library + "\",\"scan\":null,\"op\":null,\"opResult\":null}");

        socket.on(new ScanProgress(true, "/r", 3, 1, 1, 0, "Indexing", null, null));
        socket.on(new OpProgress("o1", Op.Kind.MOVE, true, 2, 1, "a", 0, 0, null, null, null));
        var second = new Client("2", new CountDownLatch(0));
        socket.afterConnectionEstablished(second.session);
        assertThat(second.await(1).getFirst()).startsWith("{\"type\":\"hello\",\"library\":\"" + library + "\",\"scan\":{\"running\":true,\"root\":\"/r\"")
                .contains("\"op\":{\"id\":\"o1\",\"kind\":\"MOVE\",\"running\":true").endsWith("\"opResult\":null}");
        assertThat(first.await(3)).as("the broadcasts after its hello").extracting(m -> m.substring(0, m.indexOf(',')))
                .containsExactly("{\"type\":\"hello\"", "{\"type\":\"scanProgress\"", "{\"type\":\"opProgress\"");

        socket.on(new OpResult("o1", 1, List.of(), List.of()));
        var third = new Client("3", new CountDownLatch(0));
        socket.afterConnectionEstablished(third.session);
        assertThat(third.await(1).getFirst()).contains("\"scan\":{\"running\":true", "\"op\":null,",
                "\"opResult\":{\"id\":\"o1\",\"done\":1,\"skipped\":[],\"failed\":[]}}");
    }

    @Test
    void aSlowDrainSendsTheNewestMessagePerAlbumInArrivalOrderOfItsLastUpdate() throws Exception {
        var socket = socket();
        var client = new Client("1", new CountDownLatch(1));
        socket.afterConnectionEstablished(client.session);
        assertThat(client.sending.await(5, TimeUnit.SECONDS)).as("ws-broadcast is held in the hello's send").isTrue();
        for (int revision = 1; revision <= 100; revision++) socket.on(new AlbumEvent.Changed(album("x", revision)));
        socket.on(new AlbumEvent.Changed(album("y", 1)));
        socket.on(new AlbumEvent.Removed("y")); // removed after a change: the removal
        socket.on(new AlbumEvent.Removed("z"));
        socket.on(new AlbumEvent.Changed(album("z", 2))); // back after a removal: the change
        socket.on(new CatalogEvent(List.of(EntityKind.LIST)));
        client.hold.countDown();

        var received = client.await(5);
        assertThat(received.get(1)).startsWith("{\"type\":\"albumChanged\",\"album\":{\"id\":\"x\"").contains("\"revision\":100,");
        assertThat(received.get(2)).isEqualTo("{\"type\":\"albumRemoved\",\"albumId\":\"y\"}");
        assertThat(received.get(3)).startsWith("{\"type\":\"albumChanged\",\"album\":{\"id\":\"z\"").contains("\"revision\":2,");
        assertThat(received.get(4)).isEqualTo(FENCE);
    }

    @Test
    void anOverflowingOutboxSendsEveryClientAwayAndServesTheNextOne() throws Exception {
        var socket = socket();
        var slow = new Client("1", new CountDownLatch(1));
        socket.afterConnectionEstablished(slow.session);
        assertThat(slow.sending.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i <= AlbumSocket.MAX_PENDING; i++) socket.on(new AlbumEvent.Removed("a" + i));
        slow.hold.countDown();
        for (long end = System.nanoTime() + 5_000_000_000L; slow.closed.get() == null && System.nanoTime() < end; ) Thread.sleep(5);
        assertThat(slow.closed.get()).isEqualTo(CloseStatus.SESSION_NOT_RELIABLE);
        assertThat(slow.received).as("its hello only").hasSize(1);

        var next = new Client("2", new CountDownLatch(0));
        socket.afterConnectionEstablished(next.session);
        socket.on(new CatalogEvent(List.of(EntityKind.LIST)));
        assertThat(next.await(2).getLast()).isEqualTo(FENCE);
        assertThat(slow.received).hasSize(1);
    }

    private AlbumSocket socket() throws Exception {
        var store = SettingsStore.class.getDeclaredConstructor(Path.class, JsonMapper.class);
        store.setAccessible(true); // package-private: the store is a bean, built by Spring
        var settings = store.newInstance(dir, JsonMapper.builder().build());
        library = Containers.normalize(settings.dataDir());
        var socket = new AlbumSocket(JsonMapper.builder().build(), null, null, null, settings, null, null, null, null, null, null, null);
        sockets.add(socket);
        return socket;
    }

    private static Album album(String id, long revision) {
        return new Album(id, "/r", "/r/" + id, Kind.DIRECTORY, id, null, false, revision, false, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, List.of(), 0, false, null, List.of(), List.of(), 1, new Album.Internal("c" + id, "s", null, null, 0));
    }

    /** A session recording what it is sent; its sends wait for {@code hold}, {@code sending} tells one began. */
    private static final class Client {
        final List<String> received = new CopyOnWriteArrayList<>();
        final AtomicReference<CloseStatus> closed = new AtomicReference<>();
        final CountDownLatch sending = new CountDownLatch(1);
        final CountDownLatch hold;
        final WebSocketSession session;

        Client(String id, CountDownLatch hold) {
            this.hold = hold;
            session = (WebSocketSession) Proxy.newProxyInstance(WebSocketSession.class.getClassLoader(),
                    new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getId", "toString" -> id;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "isOpen" -> closed.get() == null;
                        case "sendMessage" -> {
                            sending.countDown();
                            hold.await();
                            received.add(((TextMessage) args[0]).getPayload());
                            yield null;
                        }
                        case "close" -> {
                            closed.compareAndSet(null, args == null ? CloseStatus.NORMAL : (CloseStatus) args[0]);
                            yield null;
                        }
                        default -> null;
                    });
        }

        /** Exactly {@code n} messages, the last of them given a moment to be followed by more. */
        List<String> await(int n) throws InterruptedException {
            for (long end = System.nanoTime() + 5_000_000_000L; received.size() < n && System.nanoTime() < end; ) Thread.sleep(5);
            Thread.sleep(50);
            assertThat(received).hasSize(n);
            return List.copyOf(received);
        }
    }
}
