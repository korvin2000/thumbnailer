package thumbnailer.web.ws;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.StreamSupport;
import thumbnailer.source.Containers;

/** The folder picker's view of the local file system: the drives and the visible subfolders of one directory. */
final class Browse {

    private static final int MAX = 2000;

    private Browse() {}

    /**
     * {@code path} null or blank = the user's home; a file = its folder; a path that cannot be opened = its nearest
     * existing ancestor (else home), with an error.
     */
    static Event.Listing list(String path) {
        var home = Path.of(System.getProperty("user.home"));
        var dir = path == null || path.isBlank() ? home : parse(path);
        if (dir != null && Files.isRegularFile(dir)) dir = dir.getParent();
        String error = null;
        if (dir == null || !Files.isDirectory(dir)) {
            error = "Cannot open " + path;
            while (dir != null && !Files.isDirectory(dir)) dir = dir.getParent();
            if (dir == null) dir = home;
        }
        var folders = new ArrayList<Event.Listing.Entry>();
        try (var children = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (var child : children) {
                var name = child.getFileName().toString();
                if (!name.startsWith(".") && !Files.isHidden(child) && Files.isReadable(child)) // dot folders: hidden by convention
                    folders.add(new Event.Listing.Entry(name, Containers.normalize(child)));
            }
        } catch (IOException | RuntimeException e) {
            error = "Cannot open " + Containers.normalize(dir);
        }
        folders.sort(Comparator.comparing(Event.Listing.Entry::name, String.CASE_INSENSITIVE_ORDER));
        var drives = StreamSupport.stream(FileSystems.getDefault().getRootDirectories().spliterator(), false)
                .map(Containers::normalize).toList();
        return new Event.Listing(Containers.normalize(dir), dir.getParent() == null ? null : Containers.normalize(dir.getParent()),
                drives, folders.subList(0, Math.min(folders.size(), MAX)), error);
    }

    private static Path parse(String path) {
        try {
            return Path.of(path.strip()).toAbsolutePath();
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
