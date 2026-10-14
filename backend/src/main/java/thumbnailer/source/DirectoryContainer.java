package thumbnailer.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/** The files directly inside a directory; subdirectories are albums of their own. Listed on first use only. */
final class DirectoryContainer implements AlbumContainer {

    private final Path dir;
    private final Exclusions exclusions;
    private List<Item> items;

    DirectoryContainer(Path dir, Exclusions exclusions) {
        this.dir = dir;
        this.exclusions = exclusions;
    }

    @Override public String name() { return dir.getFileName().toString(); }

    @Override
    public List<Item> items() {
        if (items == null) {
            var list = new ArrayList<Item>();
            try (var files = Files.newDirectoryStream(dir)) {
                for (var file : files) {
                    var a = Files.readAttributes(file, BasicFileAttributes.class);
                    if (a.isRegularFile() && !exclusions.excludes(Containers.normalize(file)))
                        list.add(new Item(file.getFileName().toString(), a.size(), a.lastModifiedTime().toMillis()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            list.sort(BY_NAME);
            items = List.copyOf(list);
        }
        return items;
    }

    @Override public InputStream open(String name) throws IOException { return Files.newInputStream(dir.resolve(name)); }

    @Override
    public byte[] head(String name, int limit) throws IOException {
        try (var ch = FileChannel.open(dir.resolve(name))) {
            var b = ByteBuffer.allocate((int) Math.min(limit, ch.size()));
            while (b.hasRemaining() && ch.read(b) >= 0) {}
            return b.position() == b.capacity() ? b.array() : Arrays.copyOf(b.array(), b.position());
        }
    }

    /** One read into native memory of the arena: no heap array to zero or collect. */
    @Override
    public MemorySegment read(String name, Arena arena) throws IOException {
        try (var ch = FileChannel.open(dir.resolve(name))) {
            var seg = arena.allocate(ch.size());
            var buf = seg.asByteBuffer();
            while (buf.hasRemaining() && ch.read(buf) >= 0) {}
            return seg.asSlice(0, buf.position());
        }
    }

    @Override public void close() {}

    @Component
    static final class Factory implements ContainerFactory {
        @Override public Kind kind() { return Kind.DIRECTORY; }
        @Override public boolean accepts(Path file) { return false; }
        @Override public AlbumContainer open(Path path, Exclusions exclusions) { return new DirectoryContainer(path, exclusions); }
    }
}
