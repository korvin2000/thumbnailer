package thumbnailer.album;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Port: album metadata storage. Implementations are thread-safe. */
public interface AlbumRepository {

    List<Album> search(AlbumQuery query);

    int count(AlbumQuery query);

    Facets facets(AlbumQuery query);

    Optional<Album> find(String id);

    List<Album> byRoot(String root);

    List<Album> byContentId(String contentId);

    Map<String, RootStats> rootStats();

    List<Entry> entries(String albumId);

    Optional<Entry> entry(String albumId, int index);

    /** Inserts or replaces the album; {@code entries == null} keeps the stored entry list. */
    void save(Album album, List<Entry> entries);

    void delete(String id);

    record RootStats(int albums, Instant lastIndexed) {}
}
