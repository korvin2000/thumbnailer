package thumbnailer.ops;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import thumbnailer.index.Gate;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Cry;
import thumbnailer.source.Disks;

/**
 * File operations on the library (catalog_plan.md §4): the only code that writes there. One at a time under the
 * {@link Gate}, on the platform thread {@code ops}, with the settings and the {@code .cry} key (the operation's
 * password, else the session's) taken at admission; one item at a time, planned again right before it runs, in a turn
 * on its disk's {@code SCAN} lane, through the journaled {@link Steps}; cancel takes effect between items. Progress is
 * published as {@link OpProgress} (at most 4/s) and the end as {@link OpResult}. What a crash left is recovered when
 * the bean is created (before any scan) and again before each operation.
 */
@Service
public class FileOps {

    private static final Logger log = LoggerFactory.getLogger(FileOps.class);
    private static final long PACE = 250_000_000L; // progress at most 4 times a second

    private final Planner planner;
    private final Steps steps;
    private final SettingsStore settings;
    private final Gate gate;
    private final Disks disks;
    private final ApplicationEventPublisher events;
    private volatile boolean cancelled;
    private volatile Thread worker;

    FileOps(Planner planner, Steps steps, SettingsStore settings, Gate gate, Disks disks, ApplicationEventPublisher events) {
        this.planner = planner;
        this.steps = steps;
        this.settings = settings;
        this.gate = gate;
        this.disks = disks;
        this.events = events;
        steps.recover();
    }

    /** Admits the operation and starts it; returns its id. Refused while the gate is held or when there is nothing to do. */
    public String submit(Op op) {
        var hold = gate.tryEnter(Gate.Activity.OP).orElseThrow(() -> new IllegalStateException(gate.blocker()));
        try {
            var snapshot = settings.get();
            var key = key(op, snapshot);
            var sources = planner.sources(op);
            if (sources.isEmpty()) throw new IllegalArgumentException("Nothing to do");
            var id = UUID.randomUUID().toString();
            cancelled = false;
            worker = Thread.ofPlatform().name("ops").daemon(true)
                    .uncaughtExceptionHandler((t, e) -> log.error("Operation {} stopped", id, e))
                    .start(() -> run(id, op, snapshot, key, sources, hold));
            return id;
        } catch (RuntimeException e) {
            hold.close();
            throw e;
        }
    }

    /** What the operation would do, item by item, for its confirm dialog. */
    public Preflight preflight(Op op) {
        var s = settings.get();
        return planner.preflight(op, s, key(op, s));
    }

    /** Stops after the current item. */
    public void cancel() { cancelled = true; }

    public boolean running() { return gate.current() == Gate.Activity.OP; }

    /** On shutdown: the current item finishes; a copy still running after 5 s is interrupted and rolls back. */
    @EventListener(ContextClosedEvent.class)
    void close() throws InterruptedException {
        cancel();
        var w = worker;
        if (w == null) return;
        w.join(Duration.ofSeconds(5));
        if (w.isAlive()) {
            w.interrupt();
            w.join(Duration.ofSeconds(10));
        }
    }

    /** Waits until the worker has ended (tests). */
    void await() throws InterruptedException {
        var w = worker;
        if (w != null) w.join();
    }

    private void run(String id, Op op, Settings s, byte[] key, List<Planner.Source> sources, Gate.Hold hold) {
        var p = new Progress(id, op.kind(), sources.size(), Instant.now());
        try (hold) {
            steps.recover(); // what a failed item or a restart left, before anything new
            for (int n = 0; n < sources.size() && !cancelled; n++) {
                var item = planner.item(op, s, key, sources.get(n), Set.of());
                p.current = item.path();
                p.publish(false);
                var reason = item.reason() != null ? item.reason() : Planner.fits(item) ? null : "No space";
                if (reason != null) {
                    p.skipped.add(new OpResult.Issue(item.path(), reason));
                    continue;
                }
                try (var turn = lane(s, item.manifest().src()).enter()) {
                    var partly = steps.run(item.manifest().at(id, n), key, turn);
                    if (partly == null) p.done++;
                    else p.failed.add(new OpResult.Issue(item.path(), partly));
                } catch (IOException | RuntimeException e) {
                    var why = Trees.reason(e);
                    log.warn("{} {}: {}", op.kind(), item.path(), why);
                    if (steps.pending(id, n)) p.failed.add(new OpResult.Issue(item.path(), why + "; finished on the next start"));
                    else (Trees.inUse(e) ? p.skipped : p.failed).add(new OpResult.Issue(item.path(), why));
                }
            }
        }
        // the gate is open again before the result: a client may submit the next operation as soon as it sees it
        p.current = null;
        p.publish(true);
        events.publishEvent(new OpResult(id, p.done, List.copyOf(p.skipped), List.copyOf(p.failed)));
    }

    /** The operation's turn: its source's root disk, read like a scan. */
    private Disks.Lane lane(Settings s, String path) {
        var disk = s.root(path).map(r -> disks.disk(s.locate(r.path()), r.storage()))
                .orElseGet(() -> disks.disk(s.locate(path).getRoot(), null));
        return disks.lane(disk, Disks.Use.SCAN);
    }

    /** The operation's password, else the session's key; null = none. */
    private static byte[] key(Op op, Settings s) {
        if (op.password() != null && !op.password().isBlank()) return Cry.key(op.password());
        return s.cryKey() == null ? null : HexFormat.of().parseHex(s.cryKey());
    }

    /** One operation's counters, published throttled. */
    private final class Progress {
        final String id;
        final Op.Kind kind;
        final int total;
        final Instant started;
        final List<OpResult.Issue> skipped = new ArrayList<>(), failed = new ArrayList<>();
        int done;
        String current;
        long last;

        Progress(String id, Op.Kind kind, int total, Instant started) {
            this.id = id;
            this.kind = kind;
            this.total = total;
            this.started = started;
            this.last = System.nanoTime() - PACE;
        }

        void publish(boolean end) {
            if (!end && System.nanoTime() - last < PACE) return;
            last = System.nanoTime();
            var message = !end ? "Running" : cancelled && done + skipped.size() + failed.size() < total ? "Cancelled" : "Done";
            events.publishEvent(new OpProgress(id, kind, !end, total, done, current, skipped.size(), failed.size(), message,
                    started, end ? Instant.now() : null));
        }
    }
}
