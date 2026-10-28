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
import java.util.zip.CheckedOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import thumbnailer.spi.AlbumContainer;

/**
 * Writes a zip in one pass with the JDK (catalog_plan.md §4.5 {@code auto}): images STORED (already compressed; a
 * stored entry's header carries its CRC, so each is read whole first: into memory up to {@link #IN_MEMORY}, beyond
 * into a spill file beside the target), other files DEFLATED; entry times from the source.
 */
final class ZipPacker implements Closeable {

    private static final int MAX_STORED = Integer.MAX_VALUE - 8;
    /** A stored image read into memory at most; a larger one spills to disk. */
    static final int IN_MEMORY = 64 << 20;

    private final ZipOutputStream zip;
    private final Path target;
    private final int inMemory;

    /** A new {@code target}: never one that was there, nor what a link there points to. */
    ZipPacker(Path target) throws IOException { this(target, IN_MEMORY); }

    ZipPacker(Path target, int inMemory) throws IOException {
        zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target, CREATE_NEW, WRITE), 64 << 10));
        this.target = target;
        this.inMemory = inMemory;
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
            var bytes = size > inMemory ? null : h.readNBytes(inMemory + 1); // bounded, whatever the listing says
            if (bytes != null && bytes.length <= inMemory) {
                var crc = new CRC32();
                crc.update(bytes);
                stored(e, bytes.length, crc);
                zip.write(bytes);
            } else spill(e, bytes, h);
        } else {
            e.setMethod(ZipEntry.DEFLATED);
            zip.putNextEntry(e);
            h.copyTo(zip);
        }
        h.expect(name, size);
        zip.closeEntry();
        return h.hash();
    }

    private void stored(ZipEntry e, long size, CRC32 crc) throws IOException {
        e.setMethod(ZipEntry.STORED);
        e.setSize(size);
        e.setCompressedSize(size);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
    }

    /**
     * The source read once into {@code <target>.<n>.spill}, created new beside the target as the 7z packer's spills
     * (never one that was there), with its CRC on the way; then the spill into the entry, and deleted.
     */
    private void spill(ZipEntry e, byte[] read, Hashed h) throws IOException {
        var f = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName() + ".", ".spill");
        try {
            var crc = new CRC32();
            long size;
            try (var out = new CheckedOutputStream(Files.newOutputStream(f), crc)) {
                if (read != null) out.write(read);
                size = (read == null ? 0 : read.length) + h.copyTo(out);
            }
            stored(e, size, crc);
            Files.copy(f, zip);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Override public void close() throws IOException { zip.close(); }
}
