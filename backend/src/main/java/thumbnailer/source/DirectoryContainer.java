package thumbnailer.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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

    @Override public void close() {}

    @Component
    static final class Factory implements ContainerFactory {
        @Override public Kind kind() { return Kind.DIRECTORY; }
        @Override public boolean accepts(Path file) { return false; }
        @Override public AlbumContainer open(Path path, Exclusions exclusions) { return new DirectoryContainer(path, exclusions); }
    }
}
