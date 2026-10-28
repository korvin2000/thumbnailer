package thumbnailer.settings;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.SYNC;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import thumbnailer.source.Containers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The libraries of this installation. A library is a directory holding settings, database and covers
 * ({@code thumbnailer.library-dir}); the home directory ({@code thumbnailer.data-dir}) is the default one and holds
 * {@code libraries.json} {@code {active, known}}, which {@link LibraryLocator} applies when the application starts.
 * Switching therefore takes a restart.
 */
@Component
public class Libraries {

    private static final Logger log = LoggerFactory.getLogger(Libraries.class);
    static final String FILE = "libraries.json";

    private final String home, dir;
    private final Path file;
    private final JsonMapper json;
    private final boolean pinned;

    /** The content of {@code libraries.json}: normalized directories; no file = the home library alone. */
    record State(String active, List<String> known) {
        static final State NONE = new State(null, null);

        State { known = known == null ? List.of() : List.copyOf(known); }
    }

    Libraries(@Value("${thumbnailer.data-dir}") Path home, @Value("${thumbnailer.library-dir:${thumbnailer.data-dir}}") Path dir,
              @Value("${thumbnailer.library-pinned:false}") boolean pinned, JsonMapper json) {
        this.home = Containers.normalize(home);
        this.dir = Containers.normalize(dir);
        this.file = Path.of(this.home, FILE);
        this.json = json;
        this.pinned = pinned;
    }

    public String home() { return home; }

    /** The library this process uses. */
    public String dir() { return dir; }

    /** The library the next start uses, unless {@code library-dir} is given from outside. */
    public synchronized String active() { return active(read(file, json)); }

    /** Home first, then the libraries used so far and the active one, without repeats. */
    public synchronized List<String> known() {
        var s = read(file, json);
        return Stream.of(List.of(home), s.known(), List.of(active(s))).flatMap(List::stream).distinct().toList();
    }

    /** {@code library-dir} came from the command line or the environment ({@link LibraryLocator}): no switch applies. */
    public boolean pinned() { return pinned; }

    /**
     * Makes {@code dir} (absolute; created if missing; must be writable; its {@code settings.json}, if any, readable)
     * the active library from the next start.
     */
    public synchronized void switchTo(String dir) throws IOException {
        requireUnpinned();
        var path = Path.of(dir.strip());
        if (!path.isAbsolute()) throw new IllegalArgumentException("Not an absolute path: " + dir);
        try {
            Files.delete(Files.createTempFile(Files.createDirectories(path), "probe", ".tmp"));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot write to " + dir, e);
        }
        var settings = path.resolve("settings.json");
        try {
            if (Files.exists(settings)) json.readValue(settings.toFile(), Settings.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot read " + Containers.normalize(settings) + ": " + e.getMessage(), e);
        }
        var d = Containers.normalize(path);
        var s = read(file, json);
        write(new State(d, Stream.concat(s.known().stream(), Stream.of(d)).distinct().toList()));
    }

    /** Drops {@code dir} from the known libraries; its files stay. */
    public synchronized void forget(String dir) throws IOException {
        requireUnpinned();
        var d = Containers.normalize(Path.of(dir.strip()));
        var s = read(file, json);
        if (d.equals(active(s))) throw new IllegalArgumentException("Cannot forget the active library");
        write(new State(s.active(), s.known().stream().filter(k -> !k.equals(d)).toList()));
    }

    private String active(State s) { return s.active() != null ? s.active() : home; }

    private void requireUnpinned() {
        if (pinned) throw new IllegalStateException("Library is fixed by --thumbnailer.library-dir");
    }

    private void write(State s) throws IOException {
        var tmp = Files.createDirectories(file.getParent()).resolve(FILE + ".tmp");
        Files.write(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(s), CREATE, TRUNCATE_EXISTING, SYNC); // on disk before the rename publishes it
        Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE);
    }

    /**
     * An unreadable file counts as absent: the home library is always usable. The file itself is kept aside as
     * {@code libraries.json.unreadable} (the next save would replace the only copy of the known libraries).
     */
    static State read(Path file, JsonMapper json) {
        try {
            return Files.exists(file) ? json.readValue(file.toFile(), State.class) : State.NONE;
        } catch (JacksonException e) {
            var kept = file.resolveSibling(FILE + ".unreadable");
            try { Files.copy(file, kept, REPLACE_EXISTING); } catch (IOException io) { kept = null; }
            log.warn("Ignoring unreadable {} (kept as {}): {}", file, kept, e.toString());
            return State.NONE;
        }
    }
}
