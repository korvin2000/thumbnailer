package thumbnailer.index;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.source.Exclusions;

/** Runs one scan at a time: discovery on the scan thread, reconciliation on a CPU-sized pool. */
@Service
public class ScanService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final SettingsStore settings;
    private final AlbumDiscovery discovery;
    private final Reconciler reconciler;
    private final ApplicationEventPublisher events;
    private final ExecutorService pool;
    private final AtomicReference<AtomicBoolean> running = new AtomicReference<>(); // the running scan's cancel flag
    private final Map<String, Instant> lastScans = new ConcurrentHashMap<>();
    private volatile Thread coordinator;

    ScanService(SettingsStore settings, AlbumDiscovery discovery, Reconciler reconciler,
                ApplicationEventPublisher events, @Value("${thumbnailer.index-threads:0}") int threads) {
        this.settings = settings;
        this.discovery = discovery;
        this.reconciler = reconciler;
        this.events = events;
        this.pool = Executors.newFixedThreadPool(threads > 0 ? threads : Runtime.getRuntime().availableProcessors(),
                Thread.ofPlatform().name("index-", 1).daemon(true).factory());
    }

    /** Starts scanning the given roots ({@code null} = all) in the background; false if a scan is running. */
    public boolean start(List<String> roots) {
        var cancel = new AtomicBoolean();
        if (!running.compareAndSet(null, cancel)) return false;
        coordinator = Thread.ofPlatform().name("scan").daemon(true).start(() -> {
            ScanProgress last;
            try {
                last = scan(roots, cancel::get);
            } finally {
                running.set(null);
            }
            events.publishEvent(last);
        });
        return true;
    }

    public void cancel() {
        var cancel = running.get();
        if (cancel != null) cancel.set(true);
    }

    public Optional<Instant> lastScan(String root) { return Optional.ofNullable(lastScans.get(root)); }

    @EventListener(ApplicationReadyEvent.class)
    void scanOnStart() {
        if (settings.get().scanOnStart()) start(null);
    }

    /** Scans synchronously and returns the final progress, which the caller publishes. */
    ScanProgress scan(List<String> only, BooleanSupplier cancelled) {
        var wanted = only == null ? null : only.stream().map(r -> Containers.normalize(Path.of(r))).collect(Collectors.toSet());
        var progress = new Progress();
        var pass = reconciler.begin();
        var completed = new ArrayList<String>();
        try {
            var s = settings.get();
            for (var root : s.roots()) {
                if (wanted != null && !wanted.contains(root.path()) || cancelled.getAsBoolean()) continue;
                if (!Files.isDirectory(Path.of(root.path()))) {
                    log.warn("Root {} is unreachable, skipped", root.path());
                    continue;
                }
                long t0 = System.nanoTime();
                int found0 = progress.discovered.get(), changed0 = progress.changed.get();
                progress.root = root.path();
                progress.publish(true);
                pass.load(root.path());
                var tasks = new ArrayList<Future<?>>();
                var exclusions = Exclusions.of(Stream.concat(s.exclusions().stream(), root.exclusions().stream()).toList());
                boolean complete = discovery.walk(Path.of(root.path()), exclusions, cancelled, found -> {
                    progress.discovered.incrementAndGet();
                    tasks.add(pool.submit(() -> {
                        try {
                            if (pass.reconcile(root.path(), exclusions, found, cancelled)) progress.changed.incrementAndGet();
                        } catch (Exception e) {
                            log.warn("Cannot index {}: {}", found.path(), e.toString());
                        }
                        progress.indexed.incrementAndGet();
                        progress.publish(false);
                    }));
                });
                tasks.forEach(ScanService::await);
                if (complete && !cancelled.getAsBoolean()) {
                    completed.add(root.path());
                    lastScans.put(root.path(), Instant.now());
                }
                log.info("Scanned {}: {} albums, {} changed{} in {} ms", root.path(), progress.discovered.get() - found0,
                        progress.changed.get() - changed0, complete ? "" : " (incomplete, nothing will be removed)",
                        (System.nanoTime() - t0) / 1_000_000);
            }
            if (!cancelled.getAsBoolean()) progress.removed.set(pass.prune(completed));
            if (progress.removed.get() > 0) log.info("Removed {} albums that no longer exist", progress.removed.get());
            progress.message = cancelled.getAsBoolean() ? "Cancelled" : "Done";
        } catch (RuntimeException e) {
            log.error("Scan failed", e);
            progress.message = "Failed: " + e.getMessage();
        }
        return progress.snapshot(Instant.now());
    }

    /** Stops a running scan before any bean is destroyed, so no worker writes to a closing store. Idempotent. */
    @Override
    @EventListener(ContextClosedEvent.class)
    public void close() throws InterruptedException {
        cancel();
        // Queued tasks never run after shutdownNow(): cancel them, or the coordinator would wait on them forever.
        pool.shutdownNow().forEach(task -> ((Future<?>) task).cancel(false));
        pool.awaitTermination(5, TimeUnit.SECONDS);
        var scan = coordinator;
        if (scan != null) scan.join(Duration.ofSeconds(5));
    }

    private static void await(Future<?> task) {
        try {
            task.get();
        } catch (ExecutionException e) {
            log.warn("Indexing task failed", e.getCause());
        } catch (CancellationException e) {
            // shutting down
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
