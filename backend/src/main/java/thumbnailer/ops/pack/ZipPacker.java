package thumbnailer.ops.pack;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import thumbnailer.spi.AlbumContainer;

/**
 * Writes a zip in one pass with the JDK (catalog_plan.md §4.5 {@code auto}): images STORED (already compressed; read
 * whole, since a stored entry's header carries its CRC), other files DEFLATED; entry times from the source.
 */
final class ZipPacker implements Closeable {

    private static final int MAX_STORED = Integer.MAX_VALUE - 8;

    private final ZipOutputStream zip;

    /** A new {@code target}: never one that was there, nor what a link there points to. */
    ZipPacker(Path target) throws IOException {
        zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target, CREATE_NEW, WRITE), 64 << 10));
    }

    /** Packs the named files of {@code raw} through one {@link AlbumContainer#extract} pass; returns their hashes by name. */
    static Map<String, Long> pack(AlbumContainer raw, Collection<String> names, Path target) throws IOException {
        var hashes = new ConcurrentHashMap<String, Long>();
        try (var zip = new ZipPacker(target)) {
            raw.extract(names, (item, in) -> hashes.put(item.name(), zip.put(item.name(), item.mtime(), item.bytes(), in)));
        }
        return hashes;
    }

    /** Adds entry {@code name} with the {@code size} bytes of {@code in} (another count fails) and time {@code mtime} (≤ 0: none); returns their hash. */
    long put(String name, long mtime, long size, InputStream in) throws IOException {
        var e = new ZipEntry(name);
        if (mtime > 0) e.setLastModifiedTime(FileTime.fromMillis(mtime));
        var h = new Hashed(in);
        if (AlbumContainer.isImage(name) && size <= MAX_STORED) {
            var bytes = h.readAllBytes();
            var crc = new CRC32();
            crc.update(bytes);
            e.setMethod(ZipEntry.STORED);
            e.setSize(bytes.length);
            e.setCompressedSize(bytes.length);
            e.setCrc(crc.getValue());
            zip.putNextEntry(e);
            zip.write(bytes);
        } else {
            e.setMethod(ZipEntry.DEFLATED);
            zip.putNextEntry(e);
            h.copyTo(zip);
        }
        h.expect(name, size);
        zip.closeEntry();
        return h.hash();
    }

    @Override public void close() throws IOException { zip.close(); }
}
