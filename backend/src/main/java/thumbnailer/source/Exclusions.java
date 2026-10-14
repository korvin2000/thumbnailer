package thumbnailer.source;

import static java.util.stream.Collectors.joining;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Exclusion globs matched against whole normalized absolute paths; {@code *} matches any run of characters, '/'
 * included. Directories are tested with a trailing '/', so {@code *}{@code /raw/*} excludes the raw folder itself,
 * {@code *_tmp*} anything with "_tmp" in its path and {@code *}{@code /.*} hidden files and folders. Archive entries
 * are tested as {@code <archive path>/<entry path>}. Globs speak of stored (canonical) paths: a root found elsewhere
 * through the path map tests its physical paths in their canonical form ({@link #rebased}).
 */
public record Exclusions(List<Pattern> patterns, String physical, String canonical) {

    public static final Exclusions NONE = of(List.of());

    public static Exclusions of(Collection<String> globs) {
        return new Exclusions(globs.stream().filter(g -> g != null && !g.isBlank())
                .map(g -> Pattern.compile(Arrays.stream(g.strip().replace('\\', '/').split("\\*", -1))
                        .map(Pattern::quote).collect(joining(".*")), Pattern.CASE_INSENSITIVE))
                .toList(), null, null);
    }

    /** Paths under the normalized directory {@code physical} are matched as if they lay under {@code canonical}. */
    public Exclusions rebased(String physical, String canonical) {
        return physical.equals(canonical) ? this : new Exclusions(patterns, physical, canonical);
    }

    public boolean excludes(String path) {
        var p = physical != null && Containers.within(path, physical) ? Containers.rebase(path, physical, canonical) : path;
        return patterns.stream().anyMatch(x -> x.matcher(p).matches());
    }
}
