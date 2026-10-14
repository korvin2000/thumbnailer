package thumbnailer.album;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port: album metadata storage. Implementations are thread-safe. Methods taking a directory select by path prefix (the
 * directory itself or anything below it); the {@code root} column is informational.
 */
public interface AlbumRepository {

    List<Album> search(AlbumQuery query);

    int count(AlbumQuery query);

    Facets facets(AlbumQuery query);

    Optional<Album> find(String id);

    List<Album> byRoot(String dir);

    /** Per album path at or below {@code dir}, what a scan compares: id and signature, without loading the albums. */
    Map<String, Stamp> stamps(String dir);

    List<Album> byContentId(String contentId);

    Map<String, RootStats> rootStats();

    /** The child folders of {@code parent} that hold albums, by name ignoring case. */
    List<Folder> folders(String parent);

    List<Entry> entries(String albumId);

    Optional<Entry> entry(String albumId, int index);

    /**
     * Inserts or replaces the album; {@code entries == null} keeps the stored entry list. Consumes the tombstones of its
     * id in the same transaction, so an adopted identity is used up exactly when the save succeeds.
     */
    void save(Album album, List<Entry> entries);

    /** Removes the album, keeping its identity as a {@link Tombstone} under its content id. */
    void delete(String id);

    /** The tombstone of a removed album with this content, if any; read only ({@link #save} consumes it). */
    Optional<Tombstone> recall(String contentId);

    /** Moves the albums of root {@code from} that lie at or below directory {@code to} to root {@code to}. */
    void reroot(String from, String to);

    /** Estimates: live bytes exclude indexes and large objects. */
    Stats stats();

    /** Compacts the store fully when it closes (the restart after an optimize). */
    void compact();

    record RootStats(int albums, Instant lastIndexed) {}

    record Stamp(String id, String signature) {}

    /** {@code albums} at or below the folder; {@code nested}: some lie strictly below it. */
    record Folder(String name, String path, int albums, boolean nested) {}

    record Stats(int albums, long images, long imageBytes, Instant lastIndexedAt, long liveBytes, long fileBytes) {}

    /** What a removed album leaves behind: the user's state, which a new album with the same content takes over. */
    record Tombstone(String id, long revision, int rating, String cover, boolean coverPinned, AlbumMeta edited) {
        public static Tombstone of(Album a) {
            return new Tombstone(a.id(), a.revision(), a.rating(), a.cover(), a.coverPinned(), a.internal().edited());
        }
    }
}
