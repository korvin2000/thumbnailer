package thumbnailer.web.ws;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import thumbnailer.ThumbnailerApplication;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.BlobStore;
import thumbnailer.catalog.Catalog;
import thumbnailer.index.Gate;
import thumbnailer.index.ScanService;
import thumbnailer.settings.Libraries;
import thumbnailer.settings.SettingsStore;

/**
 * The library messages: what the active library holds, switching and forgetting libraries, optimizing the stores.
 * A switch and an optimize take effect in a restart, scheduled after the {@code ok} reply has gone out. None of them
 * runs while the {@link Gate} is held (a scan, an operation, a settings save, a restart): each takes it by
 * compare-and-set, and an accepted switch or optimize keeps it for the restart, so scans and further ones are refused.
 */
@Component
class Library {

    private static final Event.About ABOUT = new Event.About(
            Optional.ofNullable(ThumbnailerApplication.class.getPackage().getImplementationVersion()).orElse("dev"),
            Runtime.version().toString(), System.getProperty("os.name") + " " + System.getProperty("os.version"));
    private static final Event OK = new Event.Ok(null);
    private static final Duration TOMBSTONES = Duration.ofDays(365);

    private final Libraries libraries;
    private final AlbumRepository repo;
    private final BlobStore blobs;
    private final SettingsStore settings;
    private final ScanService scans;
    private final Gate gate;
    private final Catalog catalog;

    Library(Libraries libraries, AlbumRepository repo, BlobStore blobs, SettingsStore settings, ScanService scans,
            Gate gate, Catalog catalog) {
        this.libraries = libraries;
        this.repo = repo;
        this.blobs = blobs;
        this.settings = settings;
        this.scans = scans;
        this.gate = gate;
        this.catalog = catalog;
    }

    Event.LibraryInfo info() throws IOException {
        var meta = repo.stats();
        var covers = blobs.stats();
        var file = settings.file();
        return new Event.LibraryInfo(libraries.home(), libraries.dir(), libraries.known(), libraries.pinned(),
                new Event.LibraryStats(meta.albums(), meta.images(), meta.imageBytes(), meta.lastIndexedAt(), covers.count(),
                        covers.liveBytes(), covers.fileBytes(), meta.liveBytes(), meta.fileBytes(),
                        Files.exists(file) ? Files.size(file) : 0), ABOUT);
    }

    /** Holds the gate while the switch is written, then keeps it for the restart; a refused one releases it. */
    Event switchTo(String dir) throws IOException {
        Objects.requireNonNull(dir, "dir is required");
        try (var hold = gate.tryEnter(Gate.Activity.SETTINGS).orElse(null)) {
            if (hold == null) return busy();
            libraries.switchTo(dir);
            hold.restart();
        }
        return restartSoon();
    }

    Event forget(String dir) throws IOException {
        Objects.requireNonNull(dir, "dir is required");
        try (var hold = gate.tryEnter(Gate.Activity.SETTINGS).orElse(null)) {
            if (hold == null) return busy();
            libraries.forget(dir);
        }
        return info();
    }

    /**
     * Covers compact now, tombstones older than a year go with their links and marks, the catalog counts are rebuilt;
     * the database compacts when the restart closes it. The gate is never released, so the restart comes even if a
     * step fails.
     */
    Event optimize() {
        if (!scans.freeze()) return busy();
        try {
            blobs.compact();
            repo.purge(Instant.now().minus(TOMBSTONES));
            catalog.recount();
            repo.compact();
        } finally {
            restartSoon();
        }
        return OK;
    }

    private Event busy() { return new Event.Error(gate.blocker()); }

    private Event restartSoon() {
        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(ThumbnailerApplication::restart);
        return OK;
    }
}
