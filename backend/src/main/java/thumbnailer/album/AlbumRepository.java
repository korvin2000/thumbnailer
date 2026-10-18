package thumbnailer.album;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import thumbnailer.catalog.Resolution;

/**
 * Port: album metadata storage. Implementations are thread-safe. Methods taking a directory select by path prefix (the
 * directory itself or anything below it); the {@code root} column is informational. Hidden albums are left out of
 * {@link #search}, {@link #count}, {@link #facets}, {@link #folders}, {@link #rootStats} counts and {@link #stats}, never
 * out of the prefix selections a scan reconciles with. Albums read back carry their links and copies count.
 */
public interface AlbumRepository {

    /** At most this many albums per bulk action or operation. */
    int SELECT_CAP = 500;

    List<Album> search(AlbumQuery query);

    int count(AlbumQuery query);

    Facets facets(AlbumQuery query);

    Optional<Album> find(String id);

    List<Album> byRoot(String dir);

    /** Per album path at or below {@code dir}, what a scan compares: id and signature, without loading the albums. */
    Map<String, Stamp> stamps(String dir);

    List<Album> byContentId(String contentId);

    /** Per stored root: its visible albums and the last index time; a root holding only hidden albums is listed too. */
    Map<String, RootStats> rootStats();

    /** The child folders of {@code parent} that hold albums, by name ignoring case. */
    List<Folder> folders(String parent);

    List<Entry> entries(String albumId);

    Optional<Entry> entry(String albumId, int index);

    /** Replaces only the stored entry list of a stored album (flags learned later); the album row is untouched. */
    void saveEntries(String albumId, List<Entry> entries);

    /**
     * Inserts or replaces the album; {@code entries == null} keeps the stored entry list. In one transaction it also
     * replaces the album's text links (roles 1–5) by {@code resolution}'s, adjusts the counts of its entities over every
     * role ({@code visibleAfter ? links after : ∅} − {@code visibleBefore ? links before : ∅}, unless suspended), adds
     * the alias text to the searchable text, moves the photo marks of renamed entries, takes over the tombstone's
     * {@code seq} when the album carries one and consumes the tombstones of its id, so an adopted identity is used up
     * exactly when the save succeeds.
     */
    void save(Album album, List<Entry> entries, Resolution resolution, Save save);

    /** A save that resolves and counts nothing (tests; links of roles 1–5 are dropped). */
    default void save(Album album, List<Entry> entries) { save(album, entries, Resolution.NONE, Save.UNCOUNTED); }

    /**
     * Removes the album, keeping its identity as a {@link Tombstone} per (content id, id); the counts of the entities
     * it was linked to drop if it was visible. Its links and photo marks stay for a revival until {@link #purge}.
     */
    void delete(String id);

    /** The newest tombstone of a removed album with this content, if any; read only ({@link #save} consumes it). */
    Optional<Tombstone> recall(String contentId);

    /** Moves the albums of root {@code from} that lie at or below directory {@code to} to root {@code to}. */
    void reroot(String from, String to);

    /** A moved album: new path and root and the next revision, nothing else changes. */
    void relocate(String id, String path, String root);

    /** Drops tombstones removed before {@code before} with their links and photo marks; returns how many. */
    int purge(Instant before);

    /** Estimates: live bytes exclude indexes and large objects. */
    Stats stats();

    /** Compacts the store fully when it closes (the restart after an optimize). */
    void compact();

    /**
     * What a bulk action or an operation acts on: the albums by id (unknown ids fail), else those matching
     * {@code query}; more than {@link #SELECT_CAP} fail rather than act on a part.
     */
    default List<Album> select(List<String> ids, AlbumQuery query) {
        if (ids != null) {
            if (ids.size() > SELECT_CAP) throw new IllegalArgumentException("At most " + SELECT_CAP + " albums at once");
            return ids.stream().map(id -> find(id).orElseThrow(() -> new NoSuchElementException("No album " + id))).toList();
        }
        if (query == null) throw new IllegalArgumentException("albumIds or query is required");
        var q = query.page(0, SELECT_CAP);
        int n = count(q);
        if (n > SELECT_CAP) throw new IllegalArgumentException(n + " albums match, at most " + SELECT_CAP + " at once");
        return search(q);
    }

    /**
     * What a save knows besides the album: whether it was visible before (live and not hidden; false for a new or a
     * revived album) and is after, whether counting is suspended (a scan recounts at its end), and the entries an
     * operation renamed (old → new name), whose photo marks follow.
     */
    record Save(boolean visibleBefore, boolean visibleAfter, boolean suspend, Map<String, String> nameMap) {
        public static final Save UNCOUNTED = new Save(false, false, true, Map.of());
    }

    record RootStats(int albums, Instant lastIndexed) {}

    record Stamp(String id, String signature) {}

    /** {@code albums} at or below the folder; {@code nested}: some lie strictly below it. */
    record Folder(String name, String path, int albums, boolean nested) {}

    record Stats(int albums, long images, long imageBytes, Instant lastIndexedAt, long liveBytes, long fileBytes) {}

    /** What a removed album leaves behind: the user's state, which a new album with the same content takes over. */
    record Tombstone(String id, long revision, int rating, String cover, boolean coverPinned, AlbumMeta edited, int seq,
                     boolean hidden) {
        public static Tombstone of(Album a) {
            return new Tombstone(a.id(), a.revision(), a.rating(), a.cover(), a.coverPinned(), a.internal().edited(),
                    a.internal().seq(), a.hidden());
        }
    }
}
