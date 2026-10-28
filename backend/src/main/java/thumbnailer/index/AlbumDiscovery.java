package thumbnailer.index;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import thumbnailer.source.Containers;
import thumbnailer.source.Exclusions;
import thumbnailer.spi.AlbumContainer;
import thumbnailer.spi.Kind;

/** Finds albums under a root: directories holding at least {@code min-images} images, and archives. */
@Component
class AlbumDiscovery {

    private static final Logger log = LoggerFactory.getLogger(AlbumDiscovery.class);
    /** Folders the system keeps from users, which hold no albums: unreadable, they are skipped as empty. */
    private static final Set<String> SYSTEM = Set.of("$recycle.bin", "system volume information", "recycler");

    private final Containers containers;
    private final int minImages;

    AlbumDiscovery(Containers containers, @Value("${thumbnailer.min-images:2}") int minImages) {
        this.containers = containers;
        this.minImages = minImages;
    }

    record Found(Path path, Kind kind) {}

    /**
     * Reports albums as they are found, and the folders below the root it may not list ({@code unreadable}: what is
     * stored at or below them is unknown, not gone), the system's own excepted; returns false if part of the tree could
     * not be read otherwise.
     */
    boolean walk(Path root, Exclusions exclusions, BooleanSupplier cancelled, Consumer<Found> found, Consumer<Path> unreadable) {
        var images = new ArrayDeque<int[]>(); // image count of each directory on the current path
        var complete = new boolean[] {true};
        try {
            walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (cancelled.getAsBoolean()) return FileVisitResult.TERMINATE;
                    if (!dir.equals(root) && exclusions.excludes(Containers.normalize(dir) + "/")) return FileVisitResult.SKIP_SUBTREE;
                    images.push(new int[1]);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || exclusions.excludes(Containers.normalize(file))) return FileVisitResult.CONTINUE;
                    if (AlbumContainer.isImage(file.getFileName().toString())) images.element()[0]++;
                    else containers.archive(file).ifPresent(kind -> found.accept(new Found(file, kind)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // A folder we may not list must not block pruning of the rest of the root forever, nor lose the albums
                    // stored below it. Other failures might hide albums anywhere, so no pruning then.
                    if (e instanceof AccessDeniedException && !file.equals(root) && Files.isDirectory(file)) {
                        if (system(root, file)) log.debug("Skipping system folder {}", file);
                        else {
                            log.info("Cannot list {}; the albums stored below it are kept", file);
                            unreadable.accept(file);
                        }
                    } else {
                        log.warn("Cannot read {}: {}", file, e.toString());
                        complete[0] = false;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    if (e != null) visitFileFailed(dir, e);
                    if (images.pop()[0] >= minImages) found.accept(new Found(dir, Kind.DIRECTORY));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Cannot walk {}: {}", root, e.toString());
            return false;
        }
        return complete[0];
    }

    /** The file tree walk (tests inject failures here). */
    void walkFileTree(Path root, FileVisitor<Path> visitor) throws IOException { Files.walkFileTree(root, visitor); }

    private static boolean system(Path root, Path dir) {
        for (var name : root.relativize(dir)) if (SYSTEM.contains(name.toString().toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}
