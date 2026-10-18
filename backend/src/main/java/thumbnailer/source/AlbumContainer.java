package thumbnailer.source;

import com.dynatrace.hash4j.hashing.HashStream64;
import com.dynatrace.hash4j.hashing.Hashing;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/** An opened album (a directory or an archive): lists its files and streams their bytes. */
public interface AlbumContainer extends Closeable {

    Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "cry");

    Comparator<Item> BY_NAME = Comparator.comparing(Item::name, String.CASE_INSENSITIVE_ORDER);

    /** A file in the container: name relative to the container, size, modification time in epoch millis. */
    record Item(String name, long bytes, long mtime) {}

    /** Display name: the directory name, or the archive name without its extension. */
    String name();

    /** Every file sorted by name, case-insensitively; sidecar files included since they feed the signature. */
    List<Item> items();

    InputStream open(String name) throws IOException;

    /** At most {@code limit} leading bytes of a file: what header parsers need, never the whole image. */
    default byte[] head(String name, int limit) throws IOException {
        try (var in = open(name)) {
            return in.readNBytes(limit);
        }
    }

    /**
     * The whole file as memory owned by {@code arena}. Containers read into native memory: under parallel renders,
     * mapping lost 30 % throughput to page faults and multi-MB heap arrays 15-20 % to G1 humongous allocation.
     */
    default MemorySegment read(String name, Arena arena) throws IOException {
        try (var in = open(name)) {
            return MemorySegment.ofArray(in.readAllBytes());
        }
    }

    /**
     * Feeds {@code sink} the first {@code limit} bytes of each named file, in whatever order reads the container
     * fastest: the order of the files on disk or in the archive (a solid archive decompresses once for all of them).
     * A file that cannot be read is skipped. The sink may run inside that decompression, on another thread: it must
     * not read this container and must be thread-safe.
     */
    default void heads(Collection<String> names, int limit, HeadSink sink) throws IOException {
        for (var name : names) {
            byte[] head;
            try {
                head = head(name, limit);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            sink.accept(name, head);
        }
    }

    interface HeadSink { void accept(String name, byte[] head) throws IOException; }

    /**
     * Feeds {@code sink} every named file whole, in the order that reads the container fastest. Fail-closed, unlike
     * {@link #heads}: a file that cannot be read, or a name listed twice (which one is meant?), fails the call.
     * Containers that read only front to back override it with one pass.
     */
    default void extract(Collection<String> names, EntrySink sink) throws IOException {
        var items = items().stream().collect(Collectors.groupingBy(Item::name));
        for (var n : names) {
            var same = items.getOrDefault(n, List.of());
            if (same.size() != 1) throw same.isEmpty() ? new FileNotFoundException(n) : new IOException("Duplicate entry name " + n + " in " + name());
        }
        for (var n : names) {
            try (var in = open(n)) {
                sink.accept(items.get(n).getFirst(), in);
            }
        }
    }

    interface EntrySink { void accept(Item item, InputStream in) throws IOException; }

    /**
     * Whether reading one file costs a pass over every file packed before it (a solid archive, a compressed tar): read
     * several only through {@link #heads}, which takes them in packing order in one pass.
     */
    default boolean sequential() { return false; }

    /**
     * Whether reading it is bound by the CPU, not the disk: one decompression pass over a compressed solid archive or
     * tar. Its reads hold no turn on a disk that pays for seeks: other albums read while it decompresses.
     */
    default boolean cpuBound() { return false; }

    /** The key state of this container's encrypted members (see {@link Cry}): stored with the signature of an album holding any. */
    default String keyTag() { return ""; }

    default List<Item> images() { return items().stream().filter(i -> isImage(i.name())).toList(); }

    /** Changes whenever any file is added, removed, resized or touched, sidecars included. */
    default String signature() { return hash(items(), (h, i) -> h.putString(i.name()).putLong(i.bytes()).putLong(i.mtime())); }

    /** Survives moves and copies: depends on image names and sizes only. */
    default String contentId() { return hash(images(), (h, i) -> h.putString(i.name()).putLong(i.bytes())); }

    /**
     * An archive's signature without listing it: its size and mtime (any change rewrites the file) and the exclusion
     * globs, so a rescan of an unchanged archive costs one {@code stat}.
     */
    static String archiveSignature(Path file, Exclusions exclusions) {
        try {
            var a = Files.readAttributes(file, BasicFileAttributes.class);
            var h = Hashing.wyhashFinal4().hashStream().putLong(a.size()).putLong(a.lastModifiedTime().toMillis());
            exclusions.patterns().forEach(p -> h.putString(p.pattern()));
            return HexFormat.of().toHexDigits(h.getAsLong());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isImage(String name) { return IMAGE_EXTENSIONS.contains(extension(name)); }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 64-bit wyhash (final 4) as 16 hex digits; {@code putString} is length-prefixed, so fields never run together. */
    private static String hash(List<Item> items, BiConsumer<HashStream64, Item> put) {
        var h = Hashing.wyhashFinal4().hashStream();
        items.forEach(i -> put.accept(h, i));
        return HexFormat.of().toHexDigits(h.getAsLong());
    }
}
