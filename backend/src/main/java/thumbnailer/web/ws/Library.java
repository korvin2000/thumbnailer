package thumbnailer.web.ws;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import thumbnailer.ThumbnailerApplication;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.BlobStore;
import thumbnailer.index.ScanService;
import thumbnailer.settings.Libraries;
import thumbnailer.settings.SettingsStore;

/**
 * The library messages: what the active library holds, switching and forgetting libraries, optimizing the stores.
 * A switch and an optimize take effect in a restart, scheduled after the {@code ok} reply has gone out. None of them
 * runs while a scan does; once a switch or an optimize is accepted, scans stay frozen and further ones are refused
 * until the restart.
 */
@Component
class Library {

    private static final Event.About ABOUT = new Event.About(
            Optional.ofNullable(ThumbnailerApplication.class.getPackage().getImplementationVersion()).orElse("dev"),
            Runtime.version().toString(), System.getProperty("os.name") + " " + System.getProperty("os.version"));
    private static final Event BUSY = new Event.Error("A scan is running"), PENDING = new Event.Error("A restart is pending");
    private static final Event OK = new Event.Ok();

    private final Libraries libraries;
    private final AlbumRepository repo;
    private final BlobStore blobs;
    private final SettingsStore settings;
    private final ScanService scans;

    Library(Libraries libraries, AlbumRepository repo, BlobStore blobs, SettingsStore settings, ScanService scans) {
        this.libraries = libraries;
        this.repo = repo;
        this.blobs = blobs;
        this.settings = settings;
        this.scans = scans;
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

    /** Freezes scans only once the switch is written, so a refused one (bad dir or settings) leaves scanning on. */
    Event switchTo(String dir) throws IOException {
        if (scans.frozen()) return PENDING;
        if (scans.running()) return BUSY;
        libraries.switchTo(Objects.requireNonNull(dir, "dir is required"));
        return restartSoon();
    }

    Event forget(String dir) throws IOException {
        if (scans.running()) return BUSY;
        libraries.forget(Objects.requireNonNull(dir, "dir is required"));
        return info();
    }

    /** Covers compact now; the database compacts when the restart closes it. */
    Event optimize() {
        if (scans.frozen()) return PENDING;
        if (scans.running()) return BUSY;
        scans.freeze();
        blobs.compact();
        repo.compact();
        return restartSoon();
    }

    private Event restartSoon() {
        scans.freeze();
        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(ThumbnailerApplication::restart);
        return OK;
    }
}
