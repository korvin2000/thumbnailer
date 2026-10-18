package thumbnailer.ops.pack;

import static java.util.stream.Collectors.toMap;

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
import thumbnailer.ops.Hashes;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.AlbumContainer.Item;

/**
 * The {@link Unpacker}: one {@link AlbumContainer#extract} pass into the caller's {@code <name>.part/} directory,
 * structure and times kept, every name contained (catalog_plan.md §4.4), then each written file read back.
 */
@Component
public class FolderUnpacker implements Unpacker {

    private static final Pattern REFUSED = Pattern.compile("(^|.*/)\\.\\.(/.*|$)|^/.*|^[A-Za-z]:.*|.*[:\\x00-\\x1f].*");

    /**
     * The flatten rule: an archive whose only top-level entry is a directory of its own base name ({@code X.zip}
     * holding {@code X/…}) unpacks that directory's content at the top. Old → new for every file, or empty.
     */
    public static Map<String, String> flatten(AlbumContainer raw) {
        var top = raw.name() + "/";
        var items = raw.items();
        boolean nested = !items.isEmpty() && items.stream()
                .allMatch(i -> i.name().length() > top.length() && i.name().regionMatches(true, 0, top, 0, top.length()));
        return nested ? items.stream().collect(toMap(Item::name, i -> i.name().substring(top.length()))) : Map.of();
    }

    /**
     * Returns the read-back hash of every file by its name below {@code dir} ('/' separators). Refuses (before
     * writing anything) a name that is absolute, drive-prefixed, holds {@code ..}, a ':' or a control character, and
     * fails on a file whose bytes or read-back hash differ from what the archive delivered.
     */
    @Override
    public Map<String, Long> unpack(AlbumContainer raw, Path dir, Map<String, String> nameMap) throws IOException {
        var targets = new HashMap<String, Path>();
        for (var item : raw.items()) targets.put(item.name(), contained(dir, nameMap.getOrDefault(item.name(), item.name())));
        var written = new ConcurrentHashMap<String, Long>(); // the sink may run on 7-Zip's threads
        raw.extract(targets.keySet(), (item, in) -> {
            var file = targets.get(item.name());
            Files.createDirectories(file.getParent());
            var h = new Hashed(in);
            try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                h.copyTo(out);
            }
            if (h.count() != item.bytes()) throw new IOException(item.name() + ": " + h.count() + " bytes, listed " + item.bytes());
            if (item.mtime() > 0) Files.setLastModifiedTime(file, FileTime.fromMillis(item.mtime()));
            written.put(item.name(), h.hash());
        });
        var hashes = new HashMap<String, Long>();
        for (var e : targets.entrySet()) {
            long back;
            try (var in = Files.newInputStream(e.getValue())) {
                back = Hashes.of(in);
            }
            if (!Long.valueOf(back).equals(written.get(e.getKey()))) throw new IOException("Read back differs: " + e.getValue());
            hashes.put(dir.relativize(e.getValue()).toString().replace('\\', '/'), back);
        }
        return hashes;
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
