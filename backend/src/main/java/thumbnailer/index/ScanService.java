package thumbnailer.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import thumbnailer.catalog.Catalog;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.source.Disks;
import thumbnailer.source.Exclusions;

/**
 * Runs one scan at a time, of every root or of folders at or below roots (scopes): discovery on the scan thread, one
 * virtual thread per album; the walk waits while {@code index-threads} albums are in flight, so memory and open files
 * stay bounded at any library size. Albums take turns on their disk ({@link Disks}): one after another on a disk that
 * pays for seeks, all at once on an SSD. A scan holds the {@link Gate} while it runs; the catalog counts it suspended
 * are rebuilt when it ends.
 */
@Service
public class ScanService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final SettingsStore settings;
    private final AlbumDiscovery discovery;
    private final Reconciler reconciler;
    private final ApplicationEventPublisher events;
    private final Disks disks;
    private final Gate gate;
    private final Catalog catalog;
    private final ExecutorService pool;
    private final int width; // albums indexed at once
    private final Map<String, Instant> lastScans = new ConcurrentHashMap<>();
    private final Object cancelLock = new Object(); // taking the gate and publishing the flag are one step to cancel()
    private AtomicBoolean cancelled = new AtomicBoolean(); // the last started scan's cancel flag (guarded by cancelLock)
    private volatile Thread coordinator;

    ScanService(SettingsStore settings, AlbumDiscovery discovery, Reconciler reconciler, ApplicationEventPublisher events,
                Disks disks, Gate gate, Catalog catalog, @Value("${thumbnailer.index-threads:0}") int threads) {
        this.settings = settings;
        this.discovery = discovery;
        this.reconciler = reconciler;
        this.events = events;
        this.disks = disks;
        this.gate = gate;
        this.catalog = catalog;
        this.width = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        this.pool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("index-", 1).factory());
    }

    /**
     * Starts scanning the given paths ({@code null} = every root) in the background; false if the {@link Gate} is held
     * (a scan, an operation, a pending restart, a settings save). Each path must be a root or lie below one. The gate
     * comes first, so the settings it reads cannot change under it (a roots save holds the gate); it is let go again if
     * the paths are refused or the thread does not start. The scan's cancel flag is published in the same step as the
     * gate, before the scopes are resolved, so a {@link #cancel} that sees the scan running reaches it.
     */
    public boolean start(List<String> paths) {
        var cancel = new AtomicBoolean();
        Gate.Hold hold;
        synchronized (cancelLock) {
            hold = gate.tryEnter(Gate.Activity.SCAN).orElse(null);
            if (hold == null) return false;
            cancelled = cancel;
        }
        try {
            var s = settings.get();
            var scopes = scopes(s, paths);
            coordinator = Thread.ofPlatform().name("scan").daemon(true).start(() -> {
                ScanProgress last;
                try (hold) {
                    last = scan(s, scopes, cancel::get);
                }
                try {
                    catalog.recount();
                } catch (RuntimeException e) {
                    log.warn("Cannot recount the catalog: {}", e.toString());
                }
                events.publishEvent(last);
            });
            return true;
        } catch (Throwable e) {
            synchronized (cancelLock) { cancelled = new AtomicBoolean(); } // before the gate opens: no other start has published
            hold.close();
            throw e;
        }
    }

    public boolean running() { return gate.current() == Gate.Activity.SCAN; }

    /**
     * Takes the gate for the restart a library operation schedules, refusing every later {@link #start}; the new
     * context starts thawed. False if the gate is held (a scan, an operation, a settings save, a restart).
     */
    public boolean freeze() { return gate.tryEnter(Gate.Activity.RESTART).isPresent(); }

    public boolean frozen() { return gate.current() == Gate.Activity.RESTART; }

    public void cancel() {
        synchronized (cancelLock) { cancelled.set(true); }
    }

    public Optional<Instant> lastScan(String root) { return Optional.ofNullable(lastScans.get(root)); }

    @EventListener(ApplicationReadyEvent.class)
    void scanOnStart() {
        if (settings.get().scanOnStart()) start(null);
    }

    /** Scans synchronously and returns the final progress, which the caller publishes. */
    ScanProgress scan(List<String> paths, BooleanSupplier cancelled) {
        var s = settings.get();
        return scan(s, scopes(s, paths), cancelled);
    }

    /** A directory to walk and the root it lies in, which supplies the exclusions and the albums' root. */
    private record Scope(Settings.Root root, String path) {}

    /**
     * Every root, or the given paths, each spelled like its root below which the file system's spelling of an existing
     * folder wins (a typed {@code album} is the stored {@code Album}); a path inside another is covered by that one.
     */
    private static List<Scope> scopes(Settings s, List<String> paths) {
        if (paths == null) return s.roots().stream().map(r -> new Scope(r, r.path())).toList();
        var scopes = new ArrayList<Scope>();
        paths.stream().filter(Objects::nonNull).map(p -> Containers.normalize(Path.of(p)))
                .sorted(Comparator.comparingInt(String::length)).forEach(p -> {
                    var root = s.roots().stream().filter(r -> Containers.within(p, r.path())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Not inside an indexed folder: " + p));
                    if (scopes.stream().noneMatch(x -> Containers.within(p, x.path())))
                        scopes.add(new Scope(root, spelled(s, root.path(), Containers.rebase(p, root.path(), root.path()))));
                });
        return scopes;
    }

    /** {@code scope} as the file system spells it below {@code root}; as typed if gone or reached through a link. */
    private static String spelled(Settings s, String root, String scope) {
        try {
            var real = Containers.normalize(s.locate(scope).toRealPath());
            var base = Containers.normalize(s.locate(root).toRealPath());
            return Containers.within(real, base) ? Containers.rebase(real, base, root) : scope;
        } catch (IOException e) {
            return scope;
        }
    }

    /**
     * Each scope is walked where the path map finds it; what the walk meets is stored under its canonical path. Scopes
     * on different disks are walked at once, scopes on one disk one after another. Albums at or below a completely
     * walked scope that the walk did not meet are removed at the end, unless cancelled or a walker failed.
     */
    private ScanProgress scan(Settings s, List<Scope> scopes, BooleanSupplier cancelled) {
        var progress = new Progress();
        var completed = ConcurrentHashMap.<String>newKeySet();
        try {
            var pass = reconciler.begin(s);
            var byDisk = new LinkedHashMap<Disks.Disk, List<Scope>>();
            for (var scope : scopes)
                byDisk.computeIfAbsent(disks.disk(s.locate(scope.root().path()), scope.root().storage()), d -> new ArrayList<>()).add(scope);
            var walkers = new ArrayList<Future<?>>();
            try (var group = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("walk-", 1).factory())) {
                byDisk.forEach((disk, list) -> walkers.add(group.submit(() -> {
                    for (var scope : list) {
                        if (cancelled.getAsBoolean()) break;
                        if (scan(s, pass, scope, disk, progress, cancelled)) completed.add(scope.path());
                    }
                })));
            } // waits for every walker; interrupted (closing), it interrupts them first
            for (var w : walkers)
                if (w.state() == Future.State.FAILED)
                    throw w.exceptionNow() instanceof RuntimeException e ? e : new IllegalStateException(w.exceptionNow());
            if (!cancelled.getAsBoolean()) progress.removed.set(pass.prune(completed));
            if (progress.removed.get() > 0) log.info("Removed {} albums that no longer exist", progress.removed.get());
            progress.message = cancelled.getAsBoolean() ? "Cancelled" : "Done";
        } catch (RuntimeException e) {
            log.error("Scan failed", e);
            progress.message = "Failed: " + e.getMessage();
        }
        return progress.snapshot(Instant.now());
    }

    /** Scans one scope through its disk's lane; returns whether it was walked completely and not cancelled. */
    private boolean scan(Settings s, Reconciler.Pass pass, Scope scope, Disks.Disk disk, Progress progress, BooleanSupplier cancelled) {
        var root = scope.root().path();
        var physical = s.locate(scope.path());
        var globs = Exclusions.of(Stream.concat(s.exclusions().stream(), scope.root().exclusions().stream()).toList());
        // Below a reachable root, a folder that is gone or excluded holds no albums: an empty, complete walk.
        boolean empty = !scope.path().equals(root) && (excluded(globs, root, scope.path())
                || Files.notExists(physical) && Files.isDirectory(s.locate(root)));
        if (!empty && !Files.isDirectory(physical)) {
            log.warn("{} is unreachable at {}, skipped", scope.path(), physical);
            return false;
        }
        long t0 = System.nanoTime();
        var counts = new Counts(new AtomicInteger(), new AtomicInteger());
        progress.root = scope.path();
        progress.publish(true);
        pass.load(scope.path());
        boolean complete = empty || walk(pass, scope, physical, globs, disks.lane(disk, Disks.Use.SCAN), progress, counts, cancelled);
        log.info("Scanned {} ({} {}): {} albums, {} changed{} in {} ms", scope.path(), disk.ssd() ? "SSD" : "HDD", disk.key(),
                counts.found(), counts.changed(), complete ? "" : " (incomplete, nothing will be removed)",
                (System.nanoTime() - t0) / 1_000_000);
        if (!complete || cancelled.getAsBoolean()) return false;
        if (scope.path().equals(root)) lastScans.put(root, Instant.now());
        return true;
    }

    /** The albums one scope found and changed, besides the totals of the scan. */
    private record Counts(AtomicInteger found, AtomicInteger changed) {}

    /** Walks one scope, indexing what it finds; returns whether the walk was complete. */
    private boolean walk(Reconciler.Pass pass, Scope scope, Path physical, Exclusions globs, Disks.Lane lane,
                         Progress progress, Counts counts, BooleanSupplier cancelled) {
        var at = Containers.normalize(physical);
        var exclusions = globs.rebased(at, scope.path());
        var slots = new Semaphore(width);
        try {
            return discovery.walk(physical, exclusions, cancelled, found -> {
                progress.discovered.incrementAndGet();
                counts.found().incrementAndGet();
                slots.acquireUninterruptibly();
                try {
                    pool.execute(() -> {
                        try {
                            var path = Containers.rebase(Containers.normalize(found.path()), at, scope.path());
                            if (pass.reconcile(scope.root().path(), path, exclusions, found, lane, cancelled)) {
                                progress.changed.incrementAndGet();
                                counts.changed().incrementAndGet();
                            }
                        } catch (Exception e) {
                            log.warn("Cannot index {}: {}", found.path(), e.toString());
                        } finally {
                            progress.indexed.incrementAndGet();
                            slots.release();
                        }
                        progress.publish(false);
                    });
                } catch (RejectedExecutionException e) { // closing: the walk stops, nothing will be pruned
                    slots.release();
                    throw e;
                }
            }, dir -> pass.unreadable(Containers.rebase(Containers.normalize(dir), at, scope.path())));
        } finally {
            slots.acquireUninterruptibly(width); // every worker of this scope has finished
        }
    }

    /** Whether the walk of {@code root} skips folder {@code path} or a folder between the two (canonical globs). */
    private static boolean excluded(Exclusions globs, String root, String path) {
        for (var p = path; p.length() > root.length(); p = p.substring(0, p.lastIndexOf('/')))
            if (globs.excludes(p + "/")) return true;
        return false;
    }

    /** Stops a running scan before any bean is destroyed, so no worker writes to a closing store. Idempotent. */
    @Override
    @EventListener(ContextClosedEvent.class)
    public void close() throws InterruptedException {
        cancel();
        pool.shutdownNow(); // interrupts the workers; indexers honour it
        pool.awaitTermination(5, TimeUnit.SECONDS);
        var scan = coordinator;
        if (scan != null) scan.join(Duration.ofSeconds(5));
    }

    private final class Progress {
        final AtomicInteger discovered = new AtomicInteger(), indexed = new AtomicInteger(),
                changed = new AtomicInteger(), removed = new AtomicInteger();
        final Instant startedAt = Instant.now();
        final AtomicLong published = new AtomicLong();
        volatile String root, message = "Scanning";

        void publish(boolean force) {
            long now = System.nanoTime(), last = published.get();
            if ((force || now - last >= 250_000_000L) && published.compareAndSet(last, now)) events.publishEvent(snapshot(null));
        }

        ScanProgress snapshot(Instant finishedAt) {
            return new ScanProgress(finishedAt == null, root, discovered.get(), indexed.get(), changed.get(),
                    removed.get(), message, startedAt, finishedAt);
        }
    }
}
