package thumbnailer.ops.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import thumbnailer.spi.AlbumContainer;

/**
 * The {@link Unpacker}: one {@link AlbumContainer#extract} pass into the caller's {@code <name>.part/} directory, created
 * here, structure and times kept, every name contained (catalog_plan.md §4.4); the flatten map comes from the caller.
 */
@Component
public class FolderUnpacker implements Unpacker {

    private static final Pattern REFUSED = Pattern.compile("(^|.*/)\\.\\.(/.*|$)|^/.*|^[A-Za-z]:.*|.*[:\\x00-\\x1f].*");

    /**
     * Refuses (before writing anything) a name that is absolute, drive-prefixed, holds {@code ..}, a ':' or a control
     * character, and fails on a file whose byte count differs from its listing or one the pass never delivered.
     */
    @Override
    public Map<String, Long> unpack(AlbumContainer raw, Path dir, Map<String, String> nameMap) throws IOException {
        var root = dir.toAbsolutePath().normalize();
        var targets = new HashMap<String, Path>();
        for (var item : raw.items()) targets.put(item.name(), contained(root, nameMap.getOrDefault(item.name(), item.name())));
        Files.createDirectories(root.getParent());
        Files.createDirectory(root); // ours alone: never a directory (or a link to one) that was there
        var read = new ConcurrentHashMap<String, Long>(); // the sink may run on 7-Zip's threads
        raw.extract(targets.keySet(), (item, in) -> {
            var file = targets.get(item.name());
            Files.createDirectories(file.getParent());
            var h = new Hashed(in);
            try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                h.copyTo(out);
            }
            h.expect(item.name(), item.bytes());
            if (item.mtime() > 0) Files.setLastModifiedTime(file, FileTime.fromMillis(item.mtime()));
            read.put(root.relativize(file).toString().replace('\\', '/'), h.hash());
        });
        if (read.size() < targets.size()) throw new IOException("Not every file of " + raw.name() + " was extracted");
        return read;
    }

    /** {@code name} resolved below {@code dir}, or an {@link IOException} when it would leave it. */
    static Path contained(Path dir, String name) throws IOException {
        var n = name.replace('\\', '/');
        try {
            var p = dir.resolve(n).normalize();
            if (!REFUSED.matcher(n).matches() && p.startsWith(dir.normalize()) && !p.equals(dir.normalize())) return p;
        } catch (InvalidPathException e) {
            // refused below
        }
        throw new IOException("Refused entry name: " + name);
    }
}
