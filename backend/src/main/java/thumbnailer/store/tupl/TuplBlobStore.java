package thumbnailer.store.tupl;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.cojen.tupl.Database;
import org.cojen.tupl.DatabaseConfig;
import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import thumbnailer.album.BlobStore;

/**
 * Covers in TuplDB ({@code <library-dir>/covers/tupl.db}), tuned for a regenerable cache (see TODO_PLAN/kvbench):
 * no redo log (writes become durable at the next checkpoint, once a second), no page checksums, 4 KiB pages, an
 * off-heap page cache. Freed pages are reused but the file never shrinks by itself, so an idle-time stepped compaction
 * keeps it within a few percent of the live data. A store that cannot open is moved aside and started empty; a store
 * locked by another instance is an error.
 */
@Component
@ConditionalOnProperty(name = "thumbnailer.store.blobs", havingValue = "tupl", matchIfMissing = true)
class TuplBlobStore implements BlobStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TuplBlobStore.class);
    private static final long IDLE = TimeUnit.SECONDS.toNanos(60);
    private static final double SLACK = 0.05;

    private final Path dir;
    private final long cacheBytes;
    private final ScheduledExecutorService janitor =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("covers-compact").daemon(true).factory());
    private volatile Database db;
    private volatile Index ix;
    private volatile long lastWrite = System.nanoTime() - IDLE;
    private volatile boolean dirty = true; // check once after startup
    private boolean reopened;

    TuplBlobStore(@Value("${thumbnailer.library-dir:${thumbnailer.data-dir}}") Path dataDir,
                  @Value("${thumbnailer.store.tupl.cache-mb:128}") long cacheMb) throws IOException {
        dir = dataDir.resolve("covers");
        cacheBytes = cacheMb << 20;
        open();
        if (Files.exists(dataDir.resolve("thumbs.mv")))
            log.info("{} is no longer used ({} MB) and can be deleted", dataDir.resolve("thumbs.mv"), Files.size(dataDir.resolve("thumbs.mv")) >> 20);
        janitor.scheduleWithFixedDelay(this::compactIfIdle, 30, 30, TimeUnit.SECONDS);
    }

    @Override public void put(String key, byte[] bytes) { write(key, bytes); }

    @Override public void remove(String key) { write(key, null); }

    @Override
    public Stats stats() {
        try {
            var s = db.stats();
            var file = dir.resolve("tupl.db");
            return new Stats(ix.count(null, null), (s.totalPages - s.freePages) * s.pageSize, Files.exists(file) ? Files.size(file) : 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A failing store reads as empty: callers re-render on a miss. */
    @Override
    public Optional<byte[]> get(String key) {
        var d = db;
        try {
            return Optional.ofNullable(ix.load(null, key.getBytes(UTF_8)));
        } catch (IOException | RuntimeException e) {
            failed(e, d);
            return Optional.empty();
        }
    }

    /** Null transaction = auto-commit with locking, so a reader never sees a value half-written. */
    private void write(String key, byte[] bytes) {
        var d = db;
        try {
            if (bytes == null) ix.delete(null, key.getBytes(UTF_8));
            else ix.store(null, key.getBytes(UTF_8), bytes);
            lastWrite = System.nanoTime();
            dirty = true;
        } catch (IOException | RuntimeException e) {
            failed(e, d);
        }
    }

    /** The checkpointer closes the database on a fatal I/O error; one reopen is tried, then the store stays empty. */
    private synchronized void failed(Exception e, Database seen) {
        log.warn("Cover store operation failed: {}", e.toString());
        if (seen != db || !seen.isClosed() || reopened) return;
        reopened = true;
        try {
            open();
        } catch (IOException | RuntimeException again) {
            log.warn("Cover store stays unavailable, covers render per request: {}", again.toString());
        }
    }

    private void open() throws IOException {
        Files.createDirectories(dir);
        Database d;
        try {
            d = Database.open(config());
        } catch (IOException | RuntimeException e) {
            if (locked()) throw new IllegalStateException("Cover store " + dir + " is in use by another Thumbnailer", e);
            var broken = dir.resolveSibling("covers.broken-" + System.currentTimeMillis());
            log.warn("Cover store {} cannot open ({}); moved to {}, starting empty", dir, e.toString(), broken);
            Files.move(dir, broken);
            Files.createDirectories(dir);
            d = Database.open(config());
        }
        try {
            ix = d.openIndex("covers");
        } catch (IOException | RuntimeException e) {
            d.close();
            throw e;
        }
        db = d;
        try (Stream<Path> old = Files.list(dir.getParent())) { // keep only the newest broken copy, for diagnosis
            old.filter(p -> p.getFileName().toString().startsWith("covers.broken-"))
                    .sorted(Comparator.comparing(Path::toString).reversed()).skip(1).forEach(p -> FileSystemUtils.deleteRecursively(p.toFile()));
        } catch (IOException | RuntimeException ignored) {
            // best effort, retried on the next start
        }
    }

    private DatabaseConfig config() {
        return new DatabaseConfig().baseFilePath(dir.resolve("tupl").toString()).cacheSize(cacheBytes)
                .durabilityMode(DurabilityMode.NO_REDO).checkpointSizeThreshold(0) // without redo no size ever builds up
                .checkpointRate(1, TimeUnit.SECONDS);
    }

    /** TuplDB holds a lock on {@code tupl.lock} while open; a lock we cannot take means another instance runs. */
    private boolean locked() {
        try (var ch = FileChannel.open(dir.resolve("tupl.lock"), StandardOpenOption.WRITE)) {
            var lock = ch.tryLock();
            if (lock == null) return true;
            lock.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** After 60 s without writes, once per burst of writes. */
    private void compactIfIdle() {
        if (dirty && System.nanoTime() - lastWrite >= IDLE) {
            dirty = false;
            compact();
        }
    }

    /**
     * Overwritten and deleted pages (rescans give albums new covers) sit on the free list: compact if more than 5 % is
     * free. Stepped targets, because one big compaction leaves free pages behind; a step that aborts (a concurrent
     * write) is harmless, the next idle period retries. Also run on demand (optimize); one at a time.
     */
    @Override
    public synchronized void compact() {
        try {
            var d = db;
            var s = d.stats();
            double free = (double) s.freePages / Math.max(1, s.totalPages);
            if (free <= SLACK) return;
            long before = s.totalPages * s.pageSize;
            for (double target : new double[] {0.90, 0.95, 0.98, 0.99}) d.compactFile(null, target);
            var after = d.stats();
            log.info("Compacted cover store: {} MB ({} % free) -> {} MB ({} % free)", before >> 20, Math.round(free * 100),
                    after.totalPages * after.pageSize >> 20, Math.round(100.0 * after.freePages / Math.max(1, after.totalPages)));
        } catch (IOException | RuntimeException e) {
            log.warn("Cover store compaction failed: {}", e.toString());
        }
    }

    /** {@code shutdown()}, not {@code close()}: it checkpoints first and deletes leftover files. */
    @Override
    public synchronized void close() throws IOException {
        reopened = true; // a late request must not reopen it
        janitor.shutdownNow();
        db.shutdown();
    }
}
