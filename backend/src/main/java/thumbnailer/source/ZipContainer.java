package thumbnailer.source;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/** Every file of a zip archive, nested folders included (names keep their '/' paths). */
final class ZipContainer implements AlbumContainer {

    private final ZipFile zip;
    private final String name;
    private final List<Item> items;

    ZipContainer(Path file, Exclusions exclusions) throws IOException {
        zip = new ZipFile(file.toFile());
        name = file.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
        var prefix = Containers.normalize(file) + "/";
        items = zip.stream().filter(e -> !e.isDirectory() && !exclusions.excludes(prefix + e.getName()))
                .map(e -> new Item(e.getName(), e.getSize(), e.getTime())).sorted(BY_NAME).toList();
    }

    @Override public String name() { return name; }

    @Override public List<Item> items() { return items; }

    @Override
    public InputStream open(String entry) throws IOException {
        var e = zip.getEntry(entry);
        if (e == null) throw new FileNotFoundException(entry);
        return zip.getInputStream(e);
    }

    @Override public void close() throws IOException { zip.close(); }

    @Component
    static final class Factory implements ContainerFactory {
        @Override public Kind kind() { return Kind.ZIP; }
        @Override public boolean accepts(Path file) { return AlbumContainer.extension(file.getFileName().toString()).equals("zip"); }
        @Override public AlbumContainer open(Path path, Exclusions exclusions) throws IOException { return new ZipContainer(path, exclusions); }
    }
}
