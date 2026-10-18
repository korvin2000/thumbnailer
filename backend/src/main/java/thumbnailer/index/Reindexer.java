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
import thumbnailer.album.Entry;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.source.Cry;
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
    private final Disks disks;

    Reindexer(AlbumRepository repo, AlbumService albums, AlbumIndexer indexer, Containers containers,
              SettingsStore settings, ScanService scans, Disks disks) {
        this.repo = repo;
        this.albums = albums;
        this.indexer = indexer;
        this.containers = containers;
        this.settings = settings;
        this.scans = scans;
        this.disks = disks;
    }

    /**
     * Indexes the album now at canonical {@code newPath} as album {@code albumId}: its user state, pinned cover and photo
     * marks carry over, entries renamed by the operation ({@code nameMap}: old → new name) included. Reads in a turn on
     * the disk's {@code SCAN} lane, like a scan. Idempotent: an album stored at {@code newPath} with exactly these files
     * was indexed by this call before (a recovery replaying it), so its names are not mapped twice. When the album holds
     * {@code .cry} entries (or gets them) that the session's key does not open, they would drop out with their pin and
     * marks: that waits for the password ({@code IllegalStateException}).
     */
    public Album reindex(String albumId, String newPath, Kind kind, Map<String, String> nameMap) throws IOException {
        var stored = repo.find(albumId).orElseThrow(() -> new NoSuchElementException("No album " + albumId));
        var s = settings.get();
        var root = s.root(newPath).orElseThrow(() -> new IllegalArgumentException("Not inside an indexed folder: " + newPath));
        var exclusions = Exclusions.of(Stream.concat(s.exclusions().stream(), root.exclusions().stream()).toList())
                .rebased(Containers.normalize(s.locate(root.path())), root.path());
        var lane = disks.lane(disks.disk(s.locate(root.path()), root.storage()), Disks.Use.SCAN);
        try (var turn = lane.enter(); var container = containers.open(kind, s.locate(newPath), exclusions)) {
            var cry = container.items().stream().filter(i -> Cry.is(i.name())).findFirst();
            if (cry.isPresent() && Stream.concat(repo.entries(albumId).stream().map(Entry::name), nameMap.values().stream()).anyMatch(Cry::is))
                try {
                    container.head(cry.get().name(), 4096); // the header and the name: a key that does not fit fails
                } catch (IOException e) {
                    throw new IllegalStateException("waits for the password");
                }
            var renames = stored.path().equals(newPath) && Cry.matches(stored.internal().signature(), container) ? Map.<String, String>of() : nameMap;
            var x = indexer.index(container, root.path(), newPath, kind, stored, turn, lane, renames)
                    .orElseThrow(() -> new IllegalStateException("no images at " + newPath));
            return albums.commit(Tombstone.of(stored), x.album(), x.entries(), x.cover(), renames);
        }
    }

    /** {@link ScanService#scan}: synchronous, the final progress is the caller's to publish; the caller holds the gate. */
    public ScanProgress scan(List<String> paths, BooleanSupplier cancelled) { return scans.scan(paths, cancelled); }
}
