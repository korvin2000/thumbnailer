package thumbnailer.settings;

import static java.util.stream.Collectors.toMap;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import thumbnailer.image.Render;
import thumbnailer.source.Containers;

/**
 * User settings: scan roots with their own exclusions, global exclusions, scan on start, where moved folders are found
 * now ({@code pathMap}) and how covers and entry thumbnails render. Absent fields (an older settings.json) are defaults.
 */
public record Settings(List<Root> roots, List<String> exclusions, boolean scanOnStart, List<Mapping> pathMap,
                       Render covers, Render thumbs) {

    public static final Settings DEFAULT = new Settings(null, null, true, null, null, null);

    public record Root(String path, List<String> exclusions) {
        public Root {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Root path is empty");
            path = Containers.normalize(Path.of(path));
            exclusions = clean(exclusions);
        }
    }

    /** Stored paths below directory {@code from} are on disk below {@code to}; both normalized and ending in '/'. */
    public record Mapping(String from, String to) {
        public Mapping {
            from = dir(from);
            to = dir(to);
        }

        private static String dir(String p) {
            return p == null || p.isBlank() ? null : Containers.prefix(Containers.normalize(Path.of(p.strip())));
        }
    }

    public Settings {
        roots = roots == null ? List.of() : List.copyOf(roots.stream().filter(Objects::nonNull)
                .collect(toMap(Root::path, Function.identity(), (a, b) -> a, LinkedHashMap::new)).values());
        exclusions = clean(exclusions);
        pathMap = pathMap == null ? List.of()
                : pathMap.stream().filter(m -> m != null && m.from() != null && m.to() != null).toList();
        covers = Objects.requireNonNullElse(covers, Render.COVERS);
        thumbs = Objects.requireNonNullElse(thumbs, Render.THUMBS);
    }

    /**
     * Where a stored (canonical) path is on disk: below the {@code to} of the longest matching {@code from}, else the
     * path itself. Stored paths never change, so this is the only canonical-to-physical mapping.
     */
    public Path locate(String canonical) {
        return Path.of(pathMap.stream().filter(m -> Containers.within(canonical, m.from()))
                .max(Comparator.comparingInt(m -> m.from().length()))
                .map(m -> Containers.rebase(canonical, m.from(), m.to())).orElse(canonical));
    }

    /**
     * A root inside another would index the same albums twice, under different ids; a mapping that starts inside a root
     * would move part of it, which a scan of the root (bound to one physical directory) cannot follow.
     */
    public Settings requireDisjointRoots() {
        for (var outer : roots) {
            for (var inner : roots) {
                if (inner != outer && Containers.within(inner.path(), outer.path()))
                    throw new IllegalArgumentException("Roots overlap: " + outer.path() + " contains " + inner.path());
            }
            for (var m : pathMap) {
                if (Containers.within(m.from(), outer.path()) && !m.from().equalsIgnoreCase(Containers.prefix(outer.path())))
                    throw new IllegalArgumentException("Path mapping " + m.from() + " lies inside root " + outer.path());
            }
        }
        return this;
    }

    private static List<String> clean(List<String> globs) {
        return globs == null ? List.of() : globs.stream().filter(g -> g != null && !g.isBlank()).map(String::strip).toList();
    }
}
