package thumbnailer.source;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * Every file of a zip archive, nested folders included (names keep their '/' paths). Opened and listed on first use
 * only: a rescan of an unchanged archive reads just its {@link AlbumContainer#archiveSignature stat}.
 */
final class ZipContainer implements AlbumContainer {

    private final Path file;
    private final String name;
    private final String prefix;
    private final Exclusions exclusions;
    private String signature;
    private ZipFile zip;
    private List<Item> items;

    ZipContainer(Path file, Exclusions exclusions) {
        this.file = file;
        name = file.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
        prefix = Containers.normalize(file) + "/";
        this.exclusions = exclusions;
    }

    @Override public String name() { return name; }

    /** Memoized before the first read: a zip replaced meanwhile must not lend its new stat to the old content. */
    @Override
    public String signature() {
        if (signature == null) signature = AlbumContainer.archiveSignature(file, exclusions);
        return signature;
    }

    /** Image requests open one known entry, so only scans pay for listing and sorting a large archive. */
    @Override
    public List<Item> items() {
        if (items == null) items = zip().stream().filter(e -> !e.isDirectory() && !exclusions.excludes(prefix + e.getName()))
                .map(e -> new Item(e.getName(), e.getSize(), e.getTime())).sorted(BY_NAME).toList();
        return items;
    }

    @Override public InputStream open(String entry) throws IOException { return zip().getInputStream(entry(entry)); }

    /**
     * Native memory of the arena, filled through a small cache-hot buffer: parallel renders of camera images made
     * multi-MB heap arrays (G1 humongous objects) cost 15-20 % throughput.
     */
    @Override
    public MemorySegment read(String entry, Arena arena) throws IOException {
        var e = entry(entry);
        if (e.getSize() < 0) return AlbumContainer.super.read(entry, arena);
        try (var in = zip().getInputStream(e)) {
            var seg = arena.allocate(e.getSize());
            var buf = new byte[64 << 10];
            long at = 0;
            for (int n; at < seg.byteSize() && (n = in.read(buf, 0, (int) Math.min(buf.length, seg.byteSize() - at))) > 0; at += n)
                MemorySegment.copy(buf, 0, seg, ValueLayout.JAVA_BYTE, at, n);
            if (at != seg.byteSize()) throw new IOException("Truncated entry " + entry);
            return seg;
        }
    }

    @Override
    public void close() throws IOException {
        if (zip != null) zip.close();
    }

    private ZipEntry entry(String entry) throws FileNotFoundException {
        var e = zip().getEntry(entry);
        if (e == null) throw new FileNotFoundException(entry);
        return e;
    }

    private ZipFile zip() {
        if (zip == null) {
            signature();
            try {
                zip = new ZipFile(file.toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return zip;
    }

    @Component
    static final class Factory implements ContainerFactory {
        @Override public Kind kind() { return Kind.ZIP; }
        @Override public boolean accepts(Path file) { return AlbumContainer.extension(file.getFileName().toString()).equals("zip"); }
        @Override public AlbumContainer open(Path path, Exclusions exclusions) { return new ZipContainer(path, exclusions); }
    }
}
