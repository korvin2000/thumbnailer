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
 * are tested as {@code <archive path>/<entry path>}.
 */
public record Exclusions(List<Pattern> patterns) {

    public static final Exclusions NONE = new Exclusions(List.of());

    public static Exclusions of(Collection<String> globs) {
        return new Exclusions(globs.stream().filter(g -> g != null && !g.isBlank())
                .map(g -> Pattern.compile(Arrays.stream(g.strip().replace('\\', '/').split("\\*", -1))
                        .map(Pattern::quote).collect(joining(".*")), Pattern.CASE_INSENSITIVE))
                .toList());
    }

    public boolean excludes(String path) { return patterns.stream().anyMatch(p -> p.matcher(path).matches()); }
}
