package thumbnailer.natives;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Native libraries packed in the jar under {@code /natives/<os>-<arch>/} (file = {@link System#mapLibraryName}), made
 * loadable through a persistent per-user cache instead of a temp file per start: the manifest {@code natives.sha256}
 * ({@code sha256sum} format) names the files, its first 12 hash digits name the bundle directory
 * {@code <cache>/<os>-<arch>/<bundle>/}. A library is copied on first use (temp file, verified against the manifest,
 * atomic move) and trusted from then on, one {@code stat} per start; bundles are immutable, so versions coexist and
 * concurrent processes never race. The approach of xview's {@code net.j7.commons.jni.LibraryLoader} (manifest mode).
 * Cache root: system property {@value #CACHE_PROPERTY}, default {@code ~/.thumbnailer/natives}.
 */
public final class LibraryLoader {

    public static final String CACHE_PROPERTY = "thumbnailer.natives";
    public static final String PLATFORM = os() + "-" + arch();

    private static final String DIR = "/natives/" + PLATFORM + "/";
    private static final Map<String, String> SHA256 = new ConcurrentHashMap<>();
    private static final Path BUNDLE = bundle();
    private static final Map<String, Path> READY = new ConcurrentHashMap<>();

    private LibraryLoader() {}

    /** JNI: {@code System.load} from the cache; the library binds to this class loader, which loads the app classes. */
    public static void load(String name) {
        var path = path(name);
        try {
            System.load(path.toString());
        } catch (UnsatisfiedLinkError e) {
            if (SHA256.get(path.getFileName().toString()).equals(sha256(path))) throw e; // intact: wrong CPU, missing OS library
            READY.remove(name); // damaged cache file: copy it once more
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // still mapped elsewhere; the copy below then fails loudly
            }
            System.load(path(name).toString());
        }
    }

    /** The cached file of library {@code name}, for FFM {@code SymbolLookup.libraryLookup}. */
    public static Path path(String name) { return READY.computeIfAbsent(name, LibraryLoader::install); }

    private static Path install(String name) {
        var file = System.mapLibraryName(name);
        var target = BUNDLE.resolve(file);
        var hash = SHA256.get(file);
        if (hash == null) throw new UnsatisfiedLinkError("No native " + file + " for " + PLATFORM);
        if (Files.isRegularFile(target) && hash.equals(sha256(target))) return target; // a damaged cache file is copied again (FFM has no load error to retry on)
        try (var in = resource(file)) {
            Files.createDirectories(BUNDLE);
            var tmp = Files.createTempFile(BUNDLE, file, ".tmp");
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                if (!hash.equals(sha256(tmp))) throw new IOException("checksum mismatch: " + DIR + file);
                tmp.toFile().setExecutable(true, false);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raced) {
                if (!Files.isRegularFile(target) || !hash.equals(sha256(target))) throw raced; // else another process published the same verified bytes
            } finally {
                Files.deleteIfExists(tmp);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot install native " + file, e);
        }
    }

    private static Path bundle() {
        try (var in = resource("natives.sha256")) {
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            SHA256.putAll(text.lines().map(String::strip).filter(l -> l.length() > 65)
                    .collect(Collectors.toMap(l -> l.substring(64).strip().replaceFirst("^\\*", ""), l -> l.substring(0, 64))));
            var root = System.getProperty(CACHE_PROPERTY);
            return (root != null ? Path.of(root) : Path.of(System.getProperty("user.home"), ".thumbnailer", "natives"))
                    .resolve(PLATFORM).resolve(HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 12));
        } catch (IOException | RuntimeException e) {
            return Path.of(System.getProperty("java.io.tmpdir")); // no natives for this platform: every load fails by name
        }
    }

    private static InputStream resource(String file) throws IOException {
        var in = LibraryLoader.class.getResourceAsStream(DIR + file);
        if (in == null) throw new IOException("missing resource " + DIR + file);
        return in;
    }

    private static String sha256(Path file) {
        try (var in = Files.newInputStream(file)) {
            var md = digest();
            var buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            return "";
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String os() {
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? "windows" : os.startsWith("mac") || os.contains("darwin") ? "macos" : "linux";
    }

    private static String arch() {
        var a = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return switch (a) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> a;
        };
    }
}
