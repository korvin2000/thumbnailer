package thumbnailer.settings;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.SYNC;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import thumbnailer.source.Cry;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link Settings} persisted as {@code settings.json} in the active library directory, except {@code cryKey}: it lives
 * in memory for this process only (a key found in an older file is dropped) until {@link #forgetKey}. Clients see only
 * its fingerprint ({@link #masked}); sending that back keeps the key, a stale one is dropped.
 */
@Component
public class SettingsStore {

    private final Path file;
    private final JsonMapper json;
    private volatile Settings current;

    SettingsStore(@Value("${thumbnailer.library-dir:${thumbnailer.data-dir}}") Path dataDir, JsonMapper json) throws IOException {
        this.file = Files.createDirectories(dataDir).toAbsolutePath().normalize().resolve("settings.json");
        this.json = json;
        this.current = Files.exists(file) ? json.readValue(file.toFile(), Settings.class).withKey(null) : Settings.DEFAULT;
    }

    public Settings get() { return current; }

    /** The settings as clients see them: the key replaced by its fingerprint. */
    public Settings masked() { return current.withKey(fingerprint(current)); }

    public synchronized Settings set(Settings settings) throws IOException {
        var key = settings.cryKey();
        if (key != null && key.length() == 8) settings = settings.withKey(key.equals(fingerprint(current)) ? current.cryKey() : null);
        var tmp = file.resolveSibling("settings.json.tmp");
        Files.write(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings.requireDisjointRoots().withKey(null)), CREATE, TRUNCATE_EXISTING, SYNC); // on disk before the rename publishes it
        Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE);
        return current = settings;
    }

    public synchronized void forgetKey() {
        if (current.cryKey() != null) current = current.withKey(null);
    }

    private static String fingerprint(Settings s) {
        return s.cryKey() == null ? null : Cry.fingerprint(HexFormat.of().parseHex(s.cryKey()));
    }

    public Path dataDir() { return file.getParent(); }

    public Path file() { return file; }
}
