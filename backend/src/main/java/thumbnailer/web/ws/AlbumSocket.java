package thumbnailer.web.ws;

import com.dynatrace.hash4j.hashing.Hashing;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
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
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.CatalogEvent;
import thumbnailer.index.Gate;
import thumbnailer.index.ScanProgress;
import thumbnailer.index.ScanService;
import thumbnailer.ops.OpProgress;
import thumbnailer.ops.OpResult;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.source.Disks;
import thumbnailer.spi.Entry;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code /ws} endpoint: answers requests (echoing their {@code id}; catalog, operation and photo messages through
 * their facades) and broadcasts album, scan, catalog and operation events.
 */
@Component
class AlbumSocket extends TextWebSocketHandler implements WebSocketConfigurer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlbumSocket.class);
    private static final int MAX_ENTRIES = 5000;
    private static final Event OK = new Event.Ok(null);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean closing; // the context is closing: sessions that arrive now are sent away at once
    private final ExecutorService outbox =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ws-broadcast").daemon(true).factory());
    // the .cry key is session-only: forgotten 10 s after the last client left (a reload reconnects well within that)
    private final ScheduledExecutorService idle =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("ws-idle").daemon(true).factory());
    private volatile Future<?> forget;
    private final Object settingsSaves = new Object(); // one settings save at a time: its folder comparison and write
    private final JsonMapper json;
    private final AlbumRepository repo;
    private final AlbumService albums;
    private final ScanService scans;
    private final SettingsStore settings;
    private final Library library;
    private final Disks disks;
    private final Catalog catalog;
    private final Gate gate;
    private final CatalogApi catalogApi;
    private final OpsApi opsApi;
    private final PhotosApi photosApi;

    /** Server-side fields stay off the wire. */
    @JsonIgnoreProperties({"internal", "mtime"})
    private interface ServerOnly {}

    AlbumSocket(JsonMapper json, AlbumRepository repo, AlbumService albums, ScanService scans, SettingsStore settings,
                Library library, Disks disks, Catalog catalog, Gate gate, CatalogApi catalogApi, OpsApi opsApi,
                PhotosApi photosApi) {
        this.json = json.rebuild().addMixIn(Album.class, ServerOnly.class).addMixIn(Entry.class, ServerOnly.class)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.repo = repo;
        this.albums = albums;
        this.scans = scans;
        this.settings = settings;
        this.library = library;
        this.disks = disks;
        this.catalog = catalog;
        this.gate = gate;
        this.catalogApi = catalogApi;
        this.opsApi = opsApi;
        this.photosApi = photosApi;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Loopback origins only: the socket has no authentication, so no foreign web page may drive it.
        registry.addHandler(this, "/ws").setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    @Override
    public void close() {
        outbox.shutdown();
        idle.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Buffers sends so that a slow client never blocks a scan; overflowing the buffer closes that session.
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 10_000, 32 << 20));
        if (forget != null) forget.cancel(false);
        if (closing) goingAway(); // put, then read; goingAway sets, then sweeps: one of them sees the other
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { drop(session); }

    private void drop(WebSocketSession session) {
        if (sessions.remove(session.getId()) != null && sessions.isEmpty() && !closing)
            forget = idle.schedule(() -> { if (sessions.isEmpty()) settings.forgetKey(); }, 10, TimeUnit.SECONDS);
    }

    /** Clients see 1001 (going away) before the server stops: Undertow drops sockets that Spring upgraded. */
    @EventListener(ContextClosedEvent.class)
    void goingAway() {
        closing = true;
        sessions.values().forEach(session -> {
            try {
                session.close(CloseStatus.GOING_AWAY);
            } catch (IOException | RuntimeException ignored) {
                // already gone
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode id = null, type = null;
        Event reply;
        try {
            var tree = json.readTree(message.getPayload());
            id = tree.get("id");
            type = tree.get("type");
            reply = handle(json.treeToValue(tree, Request.class));
        } catch (Exception e) {
            log.debug("Request {} failed", type, e); // never the payload: it may carry a password
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

    @EventListener
    void on(CatalogEvent event) { broadcast(new Event.CatalogChanged(event.kinds())); }

    @EventListener
    void on(OpProgress progress) { broadcast(new Event.OpProgressEvent(progress)); }

    @EventListener
    void on(OpResult result) { broadcast(new Event.OpResultEvent(result)); }

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
                var album = find(albumId);
                var entries = repo.entries(albumId);
                yield new Event.AlbumDetails(album, entries.subList(0, Math.min(entries.size(), MAX_ENTRIES)),
                        catalog.marks(album.internal().seq()));
            }
            case Request.Update(var albumId, var patch) ->
                    new Event.AlbumChanged(albums.patch(albumId, Objects.requireNonNull(patch, "patch is required")));
            case Request.Scan(var paths) -> scans.start(paths) ? OK : new Event.Error(gate.blocker());
            case Request.CancelScan c -> {
                scans.cancel();
                yield OK;
            }
            case Request.GetSettings g -> settingsInfo();
            case Request.SetSettings(var s) -> setSettings(Objects.requireNonNull(s, "settings are required"));
            case Request.FacetsOf(var query) -> new Event.FacetCounts(repo.facets(query == null ? AlbumQuery.ALL : query));
            case Request.Reveal(var albumId, var path) -> {
                var s = settings.get();
                if (path == null) reveal(s.locate(find(albumId).path()), true);
                else reveal(folder(s, Containers.normalize(Path.of(path))), false);
                yield OK;
            }
            case Request.Folders(var path) -> {
                var parent = Containers.normalize(Path.of(Objects.requireNonNull(path, "path is required")));
                yield new Event.FolderList(parent, repo.folders(parent));
            }
            case Request.Browse(var path) -> Browse.list(path);
            case Request.GetLibrary l -> library.info();
            case Request.SwitchLibrary(var dir) -> library.switchTo(dir);
            case Request.ForgetLibrary(var dir) -> library.forget(dir);
            case Request.Optimize o -> library.optimize();
            case Request.Exit x -> library.exit();
            case Request.CatalogRequest r -> catalogApi.handle(r);
            case Request.OpsRequest r -> opsApi.handle(r);
            case Request.PhotosRequest r -> photosApi.handle(r);
        };
    }

    /**
     * New roots or a new path map take the {@link Gate} (retainRoots and a changed mapping would move albums under a
     * running scan's or operation's feet); everything else saves at any time. Saves are serialized, so no save that
     * keeps the folders can bring back old ones a concurrent save has just replaced.
     */
    private Event setSettings(Settings s) throws IOException {
        synchronized (settingsSaves) {
            if (folders(s).equals(folders(settings.get()))) return saveSettings(s);
            try (var hold = gate.tryEnter(Gate.Activity.SETTINGS).orElseThrow(() -> new IllegalStateException(gate.blocker()))) {
                return saveSettings(s);
            }
        }
    }

    private Event saveSettings(Settings s) throws IOException {
        albums.retainRoots(settings.set(s).roots().stream().map(Settings.Root::path).toList());
        return settingsInfo();
    }

    /** What a scan binds: the root paths and the path map, ignoring order. */
    private static List<Set<?>> folders(Settings s) {
        return List.of(Set.copyOf(s.roots().stream().map(Settings.Root::path).toList()), Set.copyOf(s.pathMap()));
    }

    private Album find(String albumId) {
        return repo.find(albumId).orElseThrow(() -> new NoSuchElementException("No album " + albumId));
    }

    private Event settingsInfo() {
        var stats = repo.rootStats();
        var current = settings.masked(); // the .cry key only as its fingerprint
        return new Event.SettingsInfo(current, Containers.normalize(settings.dataDir()), current.roots().stream().map(r -> {
            var stat = stats.get(r.path());
            var location = current.locate(r.path());
            return new Event.RootStatus(r.path(), Containers.normalize(location), Files.isDirectory(location),
                    stat == null ? 0 : stat.albums(), scans.lastScan(r.path()).orElse(stat == null ? null : stat.lastIndexed()),
                    disks.disk(location, r.storage()).ssd() ? Disks.Storage.SSD : Disks.Storage.HDD);
        }).toList(), Long.toHexString(Hashing.wyhashFinal4().hashLongLongToLong(current.covers().epoch(), current.thumbs().epoch())));
    }

    /** Opens a folder in the OS file manager: {@code path} itself, or ({@code select}) the one holding it, which Explorer selects. */
    private static void reveal(Path path, boolean select) throws IOException {
        if (System.getProperty("os.name").startsWith("Windows"))
            new ProcessBuilder(select ? List.of("explorer.exe", "/select,", path.toString()) : List.of("explorer.exe", path.toString())).start();
        else java.awt.Desktop.getDesktop().open((select ? path.getParent() : path).toFile());
    }

    /** Where canonical {@code folder} is on disk; only a folder at or below a configured root is shown. */
    private static Path folder(Settings s, String folder) {
        if (s.root(folder).isEmpty()) throw new IllegalArgumentException("Not inside an indexed folder: " + folder);
        var at = s.locate(folder);
        if (!Files.isDirectory(at)) throw new NoSuchElementException("No folder " + folder);
        return at;
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
            drop(session);
            try {
                session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException | RuntimeException ignored) {
                // already broken
            }
        }
    }
}
