package thumbnailer.index;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumRepository.Tombstone;
import thumbnailer.album.AlbumService;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.source.Disks;
import thumbnailer.source.Exclusions;
import thumbnailer.source.Kind;

/**
 * What file operations ask of indexing (they depend on {@code index}, never the reverse): re-index an album whose files
 * an operation rewrote or moved, keeping its identity, and scan the paths an operation created, inside the
 * operation's own hold of the {@link Gate}.
 */
@Component
public class Reindexer {

    private final AlbumRepository repo;
    private final AlbumService albums;
    private final AlbumIndexer indexer;
    private final Containers containers;
    private final SettingsStore settings;
    private final ScanService scans;

    Reindexer(AlbumRepository repo, AlbumService albums, AlbumIndexer indexer, Containers containers,
              SettingsStore settings, ScanService scans) {
        this.repo = repo;
        this.albums = albums;
        this.indexer = indexer;
        this.containers = containers;
        this.settings = settings;
        this.scans = scans;
    }

    /**
     * Indexes the album now at canonical {@code newPath} as album {@code albumId}: its user state, pinned cover and photo
     * marks carry over, entries renamed by the operation ({@code nameMap}: old → new name) included.
     */
    public Album reindex(String albumId, String newPath, Kind kind, Map<String, String> nameMap) throws IOException {
        var stored = repo.find(albumId).orElseThrow(() -> new NoSuchElementException("No album " + albumId));
        var s = settings.get();
        var root = s.root(newPath).orElseThrow(() -> new IllegalArgumentException("Not inside an indexed folder: " + newPath));
        var exclusions = Exclusions.of(Stream.concat(s.exclusions().stream(), root.exclusions().stream()).toList())
                .rebased(Containers.normalize(s.locate(root.path())), root.path());
        try (var container = containers.open(kind, s.locate(newPath), exclusions)) {
            var x = indexer.index(container, root.path(), newPath, kind, stored, Disks.Hold.NONE, albums.lane(s, newPath), nameMap)
                    .orElseThrow(() -> new IllegalStateException("no images at " + newPath));
            return albums.commit(Tombstone.of(stored), x.album(), x.entries(), x.cover(), nameMap);
        }
    }

    /** {@link ScanService#scan}: synchronous, the final progress is the caller's to publish; the caller holds the gate. */
    public ScanProgress scan(List<String> paths, BooleanSupplier cancelled) { return scans.scan(paths, cancelled); }
}
