package thumbnailer.index;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumRepository.Stamp;
import thumbnailer.album.AlbumService;
import thumbnailer.settings.Settings;
import thumbnailer.source.Containers;
import thumbnailer.source.Cry;
import thumbnailer.source.Disks;
import thumbnailer.source.Exclusions;

/**
 * Compares discovered containers with the stored albums: same path and signature = unchanged; same path, new
 * signature = re-index keeping id and edits; new path whose content matches an album (any root) whose path is gone =
 * moved, re-indexed too since a renamed album has a new name to extract from; otherwise new (which may take over the
 * tombstone of a removed album, see {@link AlbumService#commit}). Albums not met are removed only by {@link Pass#prune}
 * after complete walks.
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

    /** First converges the stored roots on the configured ones, so an interrupted settings save is completed. */
    Pass begin(Settings settings) {
        albums.retainRoots(settings.roots().stream().map(Settings.Root::path).toList());
        return new Pass(settings);
    }

    /**
     * One scan under one settings snapshot: id and signature of the stored albums of the scopes walked so far, by path,
     * and the ids this scan met.
     */
    final class Pass {
        private final Settings settings;
        private final Map<String, Stamp> known = new ConcurrentHashMap<>();
        private final Set<String> seen = ConcurrentHashMap.newKeySet();

        private Pass(Settings settings) { this.settings = settings; }

        /** Loads the stamps of the albums at or below directory {@code scope}. */
        void load(String scope) { known.putAll(repo.stamps(scope)); }

        /**
         * Returns whether the store changed; {@code path} is the canonical (stored) path of {@code found}, which is where
         * it is on disk. The album's reads happen in one turn on its disk ({@code lane}), from the listing to the first
         * cover read; the decode and the commit come after it. Thread-safe: called from the indexing pool.
         */
        boolean reconcile(String root, String path, Exclusions exclusions, AlbumDiscovery.Found found, Disks.Lane lane,
                          BooleanSupplier cancelled) throws IOException {
            var stamp = known.get(path);
            if (stamp != null) seen.add(stamp.id()); // even if indexing fails below: unreadable is not deleted
            if (cancelled.getAsBoolean()) return false; // a cancelled scan's queue on the disk drains without reading
            try (var turn = lane.enter(); var container = containers.open(found.kind(), found.path(), exclusions)) {
                if (cancelled.getAsBoolean() || stamp != null && Cry.matches(stamp.signature(), container)) return false;
                if (container.images().isEmpty()) { // nothing left to show (all excluded, or .cry files without the password): not met, pruned with its scope
                    if (stamp != null) seen.remove(stamp.id());
                    return false;
                }
                // Only changed albums load in full; a moved one is relocated by the commit below only.
                var base = (stamp != null ? repo.find(stamp.id()) : moved(container.contentId(), path)).orElse(null);
                if (base != null) seen.add(base.id());
                var indexed = indexer.index(container, root, path, found.kind(), base, turn, lane, Map.of());
                if (indexed.isEmpty() || cancelled.getAsBoolean()) return false; // cancelled workers publish nothing
                var x = indexed.get();
                seen.add(albums.commit(base, x.album(), x.entries(), x.cover()).id());
                return true;
            }
        }

        /** Removes the albums at or below fully walked scopes that this scan did not meet; returns how many. */
        int prune(Collection<String> scopes) {
            int removed = 0;
            for (var scope : scopes) {
                for (var stamp : repo.stamps(scope).values()) {
                    if (!seen.contains(stamp.id())) {
                        albums.remove(stamp.id());
                        removed++;
                    }
                }
            }
            return removed;
        }

        private Optional<Album> moved(String contentId, String path) {
            return repo.byContentId(contentId).stream()
                    .filter(a -> !a.path().equals(path) && Files.notExists(settings.locate(a.path()))).findFirst();
        }
    }
}
