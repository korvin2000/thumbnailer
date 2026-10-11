package thumbnailer.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.source.Containers;
import thumbnailer.source.Exclusions;

/**
 * Compares discovered containers with the stored albums: same path and signature = unchanged; same path, new
 * signature = re-index keeping id and edits; new path whose content matches an album (any root) whose path is gone =
 * moved, re-indexed too since a renamed album has a new name to extract from; otherwise new. Albums not met are
 * removed only by {@link Pass#prune} after complete walks.
 */
@Component
class Reconciler {

    private final AlbumRepository repo;
    private final AlbumService albums;
    private final AlbumIndexer indexer;
    private final Containers containers;

    Reconciler(AlbumRepository repo, AlbumService albums, AlbumIndexer indexer, Containers containers) {
        this.repo = repo;
        this.albums = albums;
        this.indexer = indexer;
        this.containers = containers;
    }

    Pass begin() { return new Pass(); }

    /** One scan: the stored albums of the roots walked so far, by path, and the ids this scan met. */
    final class Pass {
        private final Map<String, Album> known = new ConcurrentHashMap<>();
        private final Set<String> seen = ConcurrentHashMap.newKeySet();

        void load(String root) { repo.byRoot(root).forEach(a -> known.put(a.path(), a)); }

        /** Returns whether the store changed. Thread-safe: called from the indexing pool. */
        boolean reconcile(String root, Exclusions exclusions, AlbumDiscovery.Found found, BooleanSupplier cancelled)
                throws IOException {
            var path = Containers.normalize(found.path());
            var base = known.get(path);
            if (base != null) seen.add(base.id()); // even if indexing fails below: unreadable is not deleted
            try (var container = containers.open(found.kind(), found.path(), exclusions)) {
                var signature = container.signature();
                if (base != null && base.internal().signature().equals(signature) || cancelled.getAsBoolean()) return false;
                if (container.images().isEmpty()) return false;
                if (base == null) {
                    base = moved(container.contentId(), path).orElse(null); // relocated by the commit below only
                    if (base != null) seen.add(base.id());
                }
                var indexed = indexer.index(container, root, path, found.kind(), base);
                if (indexed.isEmpty() || cancelled.getAsBoolean()) return false; // cancelled workers publish nothing
                var x = indexed.get();
                seen.add(albums.commit(base, x.album(), x.entries(), x.cover()).id());
                return true;
            }
        }

        /** Removes the albums of fully walked roots that this scan did not meet; returns how many. */
        int prune(Collection<String> roots) {
            int removed = 0;
            for (var root : roots) {
                for (var album : repo.byRoot(root)) {
                    if (!seen.contains(album.id())) {
                        albums.remove(album.id());
                        removed++;
                    }
                }
            }
            return removed;
        }

        private Optional<Album> moved(String contentId, String path) {
            return repo.byContentId(contentId).stream()
                    .filter(a -> !a.path().equals(path) && Files.notExists(Path.of(a.path()))).findFirst();
        }
    }
}
