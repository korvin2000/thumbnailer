package thumbnailer.settings;

import static java.util.stream.Collectors.toMap;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import thumbnailer.source.Containers;

/** User settings: scan roots with their own exclusions, global exclusions, scan on start. */
public record Settings(List<Root> roots, List<String> exclusions, boolean scanOnStart) {

    public static final Settings DEFAULT = new Settings(List.of(), List.of(), true);

    public record Root(String path, List<String> exclusions) {
        public Root {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Root path is empty");
            path = Containers.normalize(Path.of(path));
            exclusions = clean(exclusions);
        }
    }

    public Settings {
        roots = roots == null ? List.of() : List.copyOf(roots.stream().filter(Objects::nonNull)
                .collect(toMap(Root::path, Function.identity(), (a, b) -> a, LinkedHashMap::new)).values());
        exclusions = clean(exclusions);
    }

    /** A root inside another would index the same albums twice, under different ids. */
    public Settings requireDisjointRoots() {
        for (var outer : roots) {
            var prefix = Containers.prefix(outer.path());
            for (var inner : roots) {
                if (inner != outer && (inner.path().equalsIgnoreCase(outer.path())
                        || inner.path().regionMatches(true, 0, prefix, 0, prefix.length())))
                    throw new IllegalArgumentException("Roots overlap: " + outer.path() + " contains " + inner.path());
            }
        }
        return this;
    }

    private static List<String> clean(List<String> globs) {
        return globs == null ? List.of() : globs.stream().filter(g -> g != null && !g.isBlank()).map(String::strip).toList();
    }
}
