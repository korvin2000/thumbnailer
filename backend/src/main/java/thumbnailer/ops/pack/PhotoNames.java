package thumbnailer.ops.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import thumbnailer.ops.Hashes;

/**
 * The copy-photos naming rule (catalog_plan.md §4.5): a file with the same size and hash already there is the photo
 * (skip it); another file of that name moves the copy to {@code name (2).ext}, {@code (3)}…, each checked the same way.
 */
public final class PhotoNames {

    private PhotoNames() {}

    /** Where a photo {@code name} of {@code bytes} bytes and {@code hash} goes in {@code dir}; empty when it is there already. */
    public static Optional<Path> target(Path dir, String name, long bytes, long hash) throws IOException {
        for (int n = 1; ; n++) {
            var p = dir.resolve(numbered(name, n));
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return Optional.of(p);
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && Files.size(p) == bytes && hash(p) == hash) return Optional.empty();
        }
    }

    /** {@code name} for n = 1, else {@code name (n).ext}; a name without an extension (or a dot file) gets the suffix at its end. */
    public static String numbered(String name, int n) {
        if (n == 1) return name;
        int slash = name.lastIndexOf('/'), dot = name.lastIndexOf('.');
        return dot <= slash + 1 ? name + " (" + n + ")" : name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }

    private static long hash(Path p) throws IOException {
        try (var in = Files.newInputStream(p)) {
            return Hashes.of(in);
        }
    }
}
