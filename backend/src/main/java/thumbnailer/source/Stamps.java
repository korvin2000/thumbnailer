package thumbnailer.source;

import com.dynatrace.hash4j.hashing.HashStream64;
import com.dynatrace.hash4j.hashing.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiConsumer;
import thumbnailer.spi.AlbumContainer;
import thumbnailer.spi.AlbumContainer.Item;

/** A container's stamps: its own if it knows one ({@link AlbumContainer#signature()}, {@link AlbumContainer#contentId()}), else a hash of its listing. */
public final class Stamps {

    private Stamps() {}

    /** Changes whenever any file is added, removed, resized or touched, sidecars included. */
    public static String signature(AlbumContainer c) {
        var own = c.signature();
        return own != null ? own : hash(c.items(), (h, i) -> h.putString(i.name()).putLong(i.bytes()).putLong(i.mtime()));
    }

    /** Survives moves and copies: depends on image names and sizes only. */
    public static String contentId(AlbumContainer c) {
        var own = c.contentId();
        return own != null ? own : hash(c.images(), (h, i) -> h.putString(i.name()).putLong(i.bytes()));
    }

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

    /** 64-bit wyhash (final 4) as 16 hex digits; {@code putString} is length-prefixed, so fields never run together. */
    private static String hash(List<Item> items, BiConsumer<HashStream64, Item> put) {
        var h = Hashing.wyhashFinal4().hashStream();
        items.forEach(i -> put.accept(h, i));
        return HexFormat.of().toHexDigits(h.getAsLong());
    }
}
