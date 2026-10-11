package thumbnailer.source;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** An opened album (a directory or an archive): lists its files and streams their bytes. */
public interface AlbumContainer extends Closeable {

    Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff");

    Comparator<Item> BY_NAME = Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER);

    /** A file in the container: name relative to the container, size, modification time in epoch millis. */
    record Item(String name, long bytes, long mtime) {}

    /** Display name: the directory name, or the archive name without its extension. */
    String name();

    /** Every file sorted by name, case-insensitively; sidecar files included since they feed the signature. */
    List<Item> items();

    InputStream open(String name) throws IOException;

    default List<Item> images() { return items().stream().filter(i -> isImage(i.name())).toList(); }

    /** Changes whenever any file is added, removed, resized or touched, sidecars included. */
    default String signature() { return hash(items(), i -> i.name() + '\0' + i.bytes() + '\0' + i.mtime()); }

    /** Survives moves and copies: depends on image names and sizes only. */
    default String contentId() { return hash(images(), i -> i.name() + '\0' + i.bytes()); }

    static boolean isImage(String name) { return IMAGE_EXTENSIONS.contains(extension(name)); }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String hash(List<Item> items, Function<Item, String> key) {
        try {
            var md = MessageDigest.getInstance("SHA-1");
            items.forEach(i -> md.update((key.apply(i) + '\n').getBytes(UTF_8)));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
