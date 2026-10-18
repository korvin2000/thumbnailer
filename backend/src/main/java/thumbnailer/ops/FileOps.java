package thumbnailer.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.index.Gate;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;

/**
 * File operations on the library (catalog_plan.md §4): the only code that writes there. One at a time under the
 * {@link Gate}, on the platform thread {@code ops}, with the settings (and the {@code .cry} key) taken at admission;
 * progress and the result are published as {@link OpProgress} and {@link OpResult}. Phase 0: every item fails as not
 * implemented (track B2).
 */
@Service
public class FileOps {

    private final AlbumRepository repo;
    private final SettingsStore settings;
    private final Gate gate;
    private final ApplicationEventPublisher events;
    private volatile boolean cancelled;
    private volatile Thread worker;

    FileOps(AlbumRepository repo, SettingsStore settings, Gate gate, ApplicationEventPublisher events) {
        this.repo = repo;
        this.settings = settings;
        this.gate = gate;
        this.events = events;
    }

    /** Admits the operation and starts it; returns its id. Refused while the gate is held. */
    public String submit(Op op) {
        var hold = gate.tryEnter(Gate.Activity.OP).orElseThrow(() -> new IllegalStateException(gate.blocker()));
        try {
            var snapshot = settings.get();
            var items = items(op);
            if (items.isEmpty()) throw new IllegalArgumentException("Nothing to do");
            var id = UUID.randomUUID().toString();
            cancelled = false;
            worker = Thread.ofPlatform().name("ops").daemon(true).start(() -> run(id, op, snapshot, items, hold));
            return id;
        } catch (RuntimeException e) {
            hold.close();
            throw e;
        }
    }

    /** What the operation would act on, the bytes of its albums and the free space at its target. */
    public Preflight preflight(Op op) {
        var albums = albums(op);
        return new Preflight(items(albums, op).map(p -> new Preflight.Item(p, false, null, null)).toList(),
                albums.stream().mapToLong(Album::bytes).sum(), free(settings.get(), op), List.of(), List.of());
    }

    /** Stops after the current item. */
    public void cancel() { cancelled = true; }

    public boolean running() { return gate.current() == Gate.Activity.OP; }

    @EventListener(ContextClosedEvent.class)
    void close() throws InterruptedException {
        cancel();
        var w = worker;
        if (w != null) w.join(Duration.ofSeconds(5));
    }

    private void run(String id, Op op, Settings snapshot, List<String> items, Gate.Hold hold) {
        var started = Instant.now();
        var failed = items.stream().map(item -> new OpResult.Issue(item, "not implemented")).toList();
        hold.close(); // before the result: a client may submit the next operation as soon as it sees it
        events.publishEvent(new OpProgress(id, op.kind(), false, items.size(), 0, null, 0, failed.size(),
                "Not implemented", started, Instant.now()));
        events.publishEvent(new OpResult(id, 0, List.of(), failed));
    }

    private List<String> items(Op op) { return items(albums(op), op).toList(); }

    private static Stream<String> items(List<Album> albums, Op op) {
        return Stream.concat(albums.stream().map(Album::path), Stream.ofNullable(op.paths()).flatMap(List::stream));
    }

    private List<Album> albums(Op op) {
        return op.albumIds() == null && op.query() == null ? List.of() : repo.select(op.albumIds(), op.query());
    }

    /**
     * Usable bytes on the volume of the target folder (its nearest existing ancestor, where the path map puts it); -1
     * without one: no target, or a rename (the target is a name) or a delete.
     */
    private static long free(Settings s, Op op) {
        if (op.target() == null || EnumSet.of(Op.Kind.FOLDER_RENAME, Op.Kind.DELETE, Op.Kind.FOLDER_DELETE).contains(op.kind())) return -1;
        try {
            for (var p = s.locate(Containers.normalize(Path.of(op.target()))); p != null; p = p.getParent())
                if (Files.exists(p)) return Files.getFileStore(p).getUsableSpace();
            return -1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
