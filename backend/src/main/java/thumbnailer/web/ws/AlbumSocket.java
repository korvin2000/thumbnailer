package thumbnailer.web.ws;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumEvent;
import thumbnailer.album.AlbumQuery;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.album.Entry;
import thumbnailer.index.ScanProgress;
import thumbnailer.index.ScanService;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The {@code /ws} endpoint: answers requests (echoing their {@code id}) and broadcasts album and scan events. */
@Component
class AlbumSocket extends TextWebSocketHandler implements WebSocketConfigurer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlbumSocket.class);
    private static final int MAX_ENTRIES = 5000;
    private static final Event OK = new Event.Ok();

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService outbox =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ws-broadcast").daemon(true).factory());
    private final JsonMapper json;
    private final AlbumRepository repo;
    private final AlbumService albums;
    private final ScanService scans;
    private final SettingsStore settings;

    /** Server-side fields stay off the wire. */
    @JsonIgnoreProperties({"internal", "mtime"})
    private interface ServerOnly {}

    AlbumSocket(JsonMapper json, AlbumRepository repo, AlbumService albums, ScanService scans, SettingsStore settings) {
        this.json = json.rebuild().addMixIn(Album.class, ServerOnly.class).addMixIn(Entry.class, ServerOnly.class)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.repo = repo;
        this.albums = albums;
        this.scans = scans;
        this.settings = settings;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Loopback origins only: the socket has no authentication, so no foreign web page may drive it.
        registry.addHandler(this, "/ws").setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    @Override
    public void close() { outbox.shutdown(); }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Buffers sends so that a slow client never blocks a scan; overflowing the buffer closes that session.
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 10_000, 32 << 20));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session.getId()); }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode id = null;
        Event reply;
        try {
            var tree = json.readTree(message.getPayload());
            id = tree.get("id");
            reply = handle(json.treeToValue(tree, Request.class));
        } catch (Exception e) {
            log.debug("Request failed: {}", message.getPayload(), e);
            reply = new Event.Error(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        send(sessions.getOrDefault(session.getId(), session), text(reply, id));
    }

    @EventListener
    void on(AlbumEvent event) {
        broadcast(switch (event) {
            case AlbumEvent.Changed(var album) -> new Event.AlbumChanged(album);
            case AlbumEvent.Removed(var albumId) -> new Event.AlbumRemoved(albumId);
        });
    }

    @EventListener
    void on(ScanProgress progress) { broadcast(new Event.Progress(progress)); }

    /** Off the publishing thread, in publication order: the scan and mutations never wait for clients. */
    private void broadcast(Event event) {
        outbox.execute(() -> {
            if (sessions.isEmpty()) return;
            var text = text(event, null);
            sessions.values().forEach(session -> send(session, text));
        });
    }

    private Event handle(Request request) throws IOException {
        return switch (request) {
            case Request.Search(var query) -> {
                var q = query == null ? AlbumQuery.ALL : query;
                yield new Event.Results(repo.search(q), repo.count(q));
            }
            case Request.Open(var albumId) -> {
                var entries = repo.entries(albumId);
                yield new Event.AlbumDetails(find(albumId), entries.subList(0, Math.min(entries.size(), MAX_ENTRIES)));
            }
            case Request.Update(var albumId, var patch) ->
                    new Event.AlbumChanged(albums.patch(albumId, Objects.requireNonNull(patch, "patch is required")));
            case Request.Scan(var roots) -> scans.start(roots) ? OK : new Event.Error("A scan is already running");
            case Request.CancelScan c -> {
                scans.cancel();
                yield OK;
            }
            case Request.GetSettings g -> settingsInfo();
            case Request.SetSettings(var s) -> {
                var roots = settings.set(Objects.requireNonNull(s, "settings are required")).roots();
                albums.retainRoots(roots.stream().map(Settings.Root::path).toList());
                yield settingsInfo();
            }
            case Request.FacetsOf(var query) -> new Event.FacetCounts(repo.facets(query == null ? AlbumQuery.ALL : query));
            case Request.Reveal(var albumId) -> {
                reveal(Path.of(find(albumId).path()));
                yield OK;
            }
        };
    }

    private Album find(String albumId) {
        return repo.find(albumId).orElseThrow(() -> new NoSuchElementException("No album " + albumId));
    }

    private Event settingsInfo() {
        var stats = repo.rootStats();
        var current = settings.get();
        return new Event.SettingsInfo(current, Containers.normalize(settings.dataDir()), current.roots().stream().map(r -> {
            var stat = stats.get(r.path());
            return new Event.RootStatus(r.path(), Files.isDirectory(Path.of(r.path())), stat == null ? 0 : stat.albums(),
                    scans.lastScan(r.path()).orElse(stat == null ? null : stat.lastIndexed()));
        }).toList());
    }

    /** Opens the containing folder in the OS file manager; Explorer also selects the album. */
    private static void reveal(Path album) throws IOException {
        if (System.getProperty("os.name").startsWith("Windows")) new ProcessBuilder("explorer.exe", "/select,", album.toString()).start();
        else java.awt.Desktop.getDesktop().open(album.getParent().toFile());
    }

    private String text(Event event, JsonNode id) {
        ObjectNode node = json.valueToTree(event);
        if (id != null) node.set("id", id);
        return json.writeValueAsString(node);
    }

    /** A session that cannot take a message (closed, or over its buffer limit) is dropped; the client reconnects. */
    private void send(WebSocketSession session, String text) {
        try {
            session.sendMessage(new TextMessage(text));
        } catch (IOException | RuntimeException e) {
            log.debug("Closing session {} after a failed send: {}", session.getId(), e.toString());
            sessions.remove(session.getId());
            try {
                session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException | RuntimeException ignored) {
                // already broken
            }
        }
    }
}
