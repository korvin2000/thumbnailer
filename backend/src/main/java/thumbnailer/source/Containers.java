package thumbnailer.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Registry of {@link ContainerFactory} beans. */
@Component
public class Containers {

    private final List<ContainerFactory> factories;
    private final Cry cry;

    public Containers(List<ContainerFactory> factories, Cry cry) {
        this.factories = List.copyOf(factories);
        this.cry = cry;
    }

    /** The archive kind of a regular file, if any factory claims it. */
    public Optional<Kind> archive(Path file) {
        return factories.stream().filter(f -> f.accepts(file)).map(ContainerFactory::kind).findFirst();
    }

    /** Every container comes {@linkplain Cry#wrap wrapped}: {@code .cry} members decrypt with the key configured now. */
    public AlbumContainer open(Kind kind, Path path, Exclusions exclusions) throws IOException {
        return cry.wrap(openRaw(kind, path, exclusions));
    }

    /** {@link #open}, the {@code .cry} members decrypted with {@code key} (an operation's own); null = the key configured now. */
    public AlbumContainer open(Kind kind, Path path, Exclusions exclusions, byte[] key) throws IOException {
        return key == null ? open(kind, path, exclusions) : Cry.wrap(openRaw(kind, path, exclusions), key);
    }

    /**
     * Every file as it is on disk, {@code .cry} members as ciphertext: what a lossless move, pack or compare copies.
     * The one exception to wrapped containers, for file operations only.
     */
    public AlbumContainer openRaw(Kind kind, Path path) throws IOException { return openRaw(kind, path, Exclusions.NONE); }

    private AlbumContainer openRaw(Kind kind, Path path, Exclusions exclusions) throws IOException {
        return factories.stream().filter(f -> f.kind() == kind).findFirst()
                .orElseThrow(() -> new IllegalStateException("No container factory for " + kind)).open(path, exclusions);
    }

    /** The canonical form of every path the application stores or sends: absolute, normalized, '/' separators. */
    public static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** What every path below a normalized directory starts with; a drive root such as {@code C:/} already ends in '/'. */
    public static String prefix(String dir) { return dir.endsWith("/") ? dir : dir + "/"; }

    /** Whether normalized {@code path} is directory {@code dir} or lies below it, ignoring case. */
    public static boolean within(String path, String dir) {
        var d = prefix(dir);
        return prefix(path).regionMatches(true, 0, d, 0, d.length());
    }

    /** {@code path}, {@link #within} directory {@code from}, re-expressed below directory {@code to}. */
    public static String rebase(String path, String from, String to) {
        int n = prefix(from).length();
        return path.length() > n ? prefix(to) + path.substring(n) : to;
    }
}
