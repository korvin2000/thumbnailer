package thumbnailer.store.jdbc;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.AlbumQuery;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.Entry;
import thumbnailer.album.Facets;
import thumbnailer.source.Containers;
import thumbnailer.source.Kind;
import tools.jackson.databind.json.JsonMapper;

/**
 * Albums in portable SQL ({@code schema.sql}). Columns hold the effective metadata so search is plain SQL; tags are
 * stored as {@code " a b "} so a tag filter is {@code LIKE '% a %'}; {@code search} is the lower-cased searchable text.
 */
@Repository
@ConditionalOnProperty(name = "thumbnailer.store.albums", havingValue = "jdbc", matchIfMissing = true)
class JdbcAlbumRepository implements AlbumRepository {

    private static final String COLUMNS = "id, root, path, kind, name, cover, cover_pinned, revision, cover_portrait, "
            + "image_count, bytes, avg_pixels, max_width, max_height, shot_date, modified_at, indexed_at, model, studio, "
            + "photographer, description, tags, rating, content_id, signature, extracted_json, edited_json, search";
    private static final String INSERT = "INSERT INTO album (" + COLUMNS + ") VALUES ("
            + "?, ".repeat(COLUMNS.split(",").length - 1) + "?)";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final JsonMapper json;

    JdbcAlbumRepository(JdbcTemplate template, JsonMapper json) {
        this.jdbc = JdbcClient.create(template);
        this.template = template;
        this.json = json;
    }

    @Override
    public List<Album> search(AlbumQuery q) {
        var w = Where.of(q);
        return jdbc.sql("SELECT * FROM album" + w.sql() + " ORDER BY " + order(q.sort()) + " LIMIT :limit OFFSET :offset")
                .params(w.params()).param("limit", q.limit()).param("offset", q.offset()).query(this::album).list();
    }

    @Override
    public int count(AlbumQuery q) {
        var w = Where.of(q);
        return jdbc.sql("SELECT COUNT(*) FROM album" + w.sql()).params(w.params()).query(Integer.class).single();
    }

    @Override
    public Facets facets(AlbumQuery q) {
        var w = Where.of(q);
        List<Map<String, Integer>> counts = Stream.generate(HashMap<String, Integer>::new).limit(4)
                .collect(Collectors.toList());
        jdbc.sql("SELECT tags, model, studio, photographer FROM album" + w.sql()).params(w.params())
                .query((RowCallbackHandler) rs -> {
                    Arrays.stream(rs.getString(1).split(" ")).forEach(tag -> count(counts.get(0), tag));
                    for (int i = 2; i <= 4; i++) count(counts.get(i - 1), rs.getString(i));
                });
        return new Facets(top(counts.get(0)), top(counts.get(1)), top(counts.get(2)), top(counts.get(3)));
    }

    @Override
    public Optional<Album> find(String id) {
        return jdbc.sql("SELECT * FROM album WHERE id = ?").params(id).query(this::album).optional();
    }

    @Override
    public List<Album> byRoot(String root) {
        return jdbc.sql("SELECT * FROM album WHERE root = ?").params(root).query(this::album).list();
    }

    @Override
    public List<Album> byContentId(String contentId) {
        return jdbc.sql("SELECT * FROM album WHERE content_id = ?").params(contentId).query(this::album).list();
    }

    @Override
    public Map<String, RootStats> rootStats() {
        var stats = new HashMap<String, RootStats>();
        jdbc.sql("SELECT root, COUNT(*), MAX(indexed_at) FROM album GROUP BY root").query((RowCallbackHandler) rs ->
                stats.put(rs.getString(1), new RootStats(rs.getInt(2), Instant.ofEpochMilli(rs.getLong(3)))));
        return stats;
    }

    @Override
    public List<Entry> entries(String albumId) {
        return jdbc.sql("SELECT * FROM entry WHERE album_id = ? ORDER BY idx").params(albumId).query(JdbcAlbumRepository::entry).list();
    }

    @Override
    public Optional<Entry> entry(String albumId, int index) {
        return jdbc.sql("SELECT * FROM entry WHERE album_id = ? AND idx = ?").params(albumId, index)
                .query(JdbcAlbumRepository::entry).optional();
    }

    @Override
    @Transactional
    public void save(Album a, List<Entry> entries) {
        jdbc.sql("DELETE FROM album WHERE id = ?").params(a.id()).update();
        var tags = a.tags().isEmpty() ? " " : " " + String.join(" ", a.tags()) + " ";
        var search = Stream.of(a.name(), a.model(), a.studio(), a.photographer(), a.description(), tags.strip())
                .filter(Objects::nonNull).collect(Collectors.joining("\n")).toLowerCase(Locale.ROOT);
        jdbc.sql(INSERT).params(a.id(), a.root(), a.path(), a.kind().name(), a.name(), a.cover(), a.coverPinned(),
                a.revision(), a.coverPortrait(), a.imageCount(), a.bytes(), a.avgPixels(), a.maxWidth(), a.maxHeight(),
                a.shotDate(), a.modifiedAt().toEpochMilli(), a.indexedAt().toEpochMilli(), a.model(), a.studio(),
                a.photographer(), a.description(), tags, a.rating(), a.internal().contentId(), a.internal().signature(),
                json.writeValueAsString(a.internal().extracted()), json.writeValueAsString(a.internal().edited()),
                search).update();
        if (entries == null) return;
        jdbc.sql("DELETE FROM entry WHERE album_id = ?").params(a.id()).update();
        template.batchUpdate("INSERT INTO entry (album_id, idx, name, bytes, mtime, width, height, shot_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                entries.stream().map(e -> new Object[] {a.id(), e.index(), e.name(), e.bytes(), e.mtime(), e.width(),
                        e.height(), e.shotAt()}).toList());
    }

    @Override
    @Transactional
    public void delete(String id) {
        jdbc.sql("DELETE FROM entry WHERE album_id = ?").params(id).update();
        jdbc.sql("DELETE FROM album WHERE id = ?").params(id).update();
    }

    private Album album(ResultSet rs, int row) throws SQLException {
        var tags = rs.getString("tags").strip();
        return new Album(rs.getString("id"), rs.getString("root"), rs.getString("path"), Kind.valueOf(rs.getString("kind")),
                rs.getString("name"), rs.getString("cover"), rs.getBoolean("cover_pinned"), rs.getLong("revision"),
                rs.getBoolean("cover_portrait"), rs.getInt("image_count"), rs.getLong("bytes"), rs.getLong("avg_pixels"),
                rs.getInt("max_width"), rs.getInt("max_height"), rs.getObject("shot_date", LocalDate.class),
                Instant.ofEpochMilli(rs.getLong("modified_at")), Instant.ofEpochMilli(rs.getLong("indexed_at")),
                rs.getString("model"), rs.getString("studio"), rs.getString("photographer"), rs.getString("description"),
                tags.isEmpty() ? List.of() : List.of(tags.split(" ")), rs.getInt("rating"),
                new Album.Internal(rs.getString("content_id"), rs.getString("signature"),
                        json.readValue(rs.getString("extracted_json"), AlbumMeta.class),
                        json.readValue(rs.getString("edited_json"), AlbumMeta.class)));
    }

    private static Entry entry(ResultSet rs, int row) throws SQLException {
        return new Entry(rs.getInt("idx"), rs.getString("name"), rs.getLong("bytes"), rs.getLong("mtime"),
                rs.getInt("width"), rs.getInt("height"), rs.getObject("shot_at", LocalDateTime.class));
    }

    private static String order(AlbumQuery.Sort sort) {
        var primary = switch (sort) {
            case NEWEST -> "shot_date DESC NULLS LAST";
            case OLDEST -> "shot_date ASC NULLS LAST";
            case LARGEST -> "bytes DESC";
            case SMALLEST -> "bytes ASC";
            case MOST_PHOTOS -> "image_count DESC";
            case FEWEST_PHOTOS -> "image_count ASC";
            case BEST_QUALITY -> "avg_pixels DESC";
            case TOP_RATED -> "rating DESC";
            case NAME -> "LOWER(name) ASC";
            case RECENTLY_INDEXED -> "indexed_at DESC";
        };
        return primary + ", LOWER(name), id";
    }

    private static void count(Map<String, Integer> counts, String value) {
        if (value != null && !value.isBlank()) counts.merge(value, 1, Integer::sum);
    }

    private static List<Facets.Count> top(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(50).map(e -> new Facets.Count(e.getKey(), e.getValue())).toList();
    }

    /** A parameterized WHERE clause built from the non-null query fields. */
    private record Where(List<String> conditions, Map<String, Object> params) {

        static Where of(AlbumQuery q) {
            var w = new Where(new ArrayList<>(), new HashMap<>());
            w.add("search LIKE :text ESCAPE '!'", "text", q.text() == null || q.text().isBlank() ? null : "%" + escape(q.text().strip().toLowerCase(Locale.ROOT)) + "%");
            w.add("LOWER(model) = :model", "model", lower(q.model()));
            w.add("LOWER(studio) = :studio", "studio", lower(q.studio()));
            w.add("LOWER(photographer) = :photographer", "photographer", lower(q.photographer()));
            w.add("kind = :kind", "kind", q.kind() == null ? null : q.kind().name());
            w.add("bytes >= :minBytes", "minBytes", q.minBytes());
            w.add("bytes <= :maxBytes", "maxBytes", q.maxBytes());
            w.add("image_count >= :minCount", "minCount", q.minCount());
            w.add("image_count <= :maxCount", "maxCount", q.maxCount());
            w.add("avg_pixels >= :minPixels", "minPixels", q.minMegapixels() == null ? null : Math.round(q.minMegapixels() * 1e6));
            w.add("avg_pixels <= :maxPixels", "maxPixels", q.maxMegapixels() == null ? null : Math.round(q.maxMegapixels() * 1e6));
            w.add("shot_date >= :dateFrom", "dateFrom", q.dateFrom());
            w.add("shot_date <= :dateTo", "dateTo", q.dateTo());
            w.add("rating >= :minRating", "minRating", q.minRating());
            w.add("rating <= :maxRating", "maxRating", q.maxRating());
            var tags = q.tags() == null ? List.<String>of() : q.tags().stream().filter(Objects::nonNull).map(AlbumMeta::tag).toList();
            for (int i = 0; i < tags.size(); i++) w.add("tags LIKE :tag" + i + " ESCAPE '!'", "tag" + i, "% " + escape(tags.get(i)) + " %");
            var roots = q.roots() == null ? List.<String>of() : q.roots().stream().filter(Objects::nonNull).toList();
            var anyRoot = new ArrayList<String>();
            for (int i = 0; i < roots.size(); i++) {
                var root = Containers.normalize(Path.of(roots.get(i)));
                anyRoot.add("path = :root" + i + " OR path LIKE :under" + i + " ESCAPE '!'");
                w.params.put("root" + i, root);
                w.params.put("under" + i, escape(Containers.prefix(root)) + "%");
            }
            if (!anyRoot.isEmpty()) w.conditions.add("(" + String.join(" OR ", anyRoot) + ")");
            return w;
        }

        void add(String condition, String param, Object value) {
            if (value == null) return;
            conditions.add(condition);
            params.put(param, value);
        }

        String sql() { return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions); }

        private static String lower(String s) { return s == null ? null : s.strip().toLowerCase(Locale.ROOT); }

        private static String escape(String s) {
            return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }
    }
}
