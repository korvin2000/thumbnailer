package thumbnailer.settings;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@link Settings} persisted as {@code settings.json} in the data directory. */
@Component
public class SettingsStore {

    private final Path file;
    private final JsonMapper json;
    private volatile Settings current;

    SettingsStore(@Value("${thumbnailer.data-dir}") Path dataDir, JsonMapper json) throws IOException {
        this.file = Files.createDirectories(dataDir).toAbsolutePath().normalize().resolve("settings.json");
        this.json = json;
        this.current = Files.exists(file) ? json.readValue(file.toFile(), Settings.class) : Settings.DEFAULT;
    }

    public Settings get() { return current; }

    public synchronized Settings set(Settings settings) throws IOException {
        var tmp = file.resolveSibling("settings.json.tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), settings.requireDisjointRoots());
        Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE);
        return current = settings;
    }

    public Path dataDir() { return file.getParent(); }
}
