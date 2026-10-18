package thumbnailer.store.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
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
 * Albums in SQL ({@code schema.sql}; H2 dialect for {@code MERGE ... KEY} upserts and {@code SHUTDOWN}). Columns hold the effective metadata so search is plain SQL; tags are
 * stored as {@code " a b "} so a tag filter is {@code LIKE '% a %'}; {@code search} is the lower-cased searchable text.
 */
@Repository
@ConditionalOnProperty(name = "thumbnailer.store.albums", havingValue = "jdbc", matchIfMissing = true)
class JdbcAlbumRepository implements AlbumRepository, AutoCloseable {

    private static final String COLUMNS = "id, root, path, kind, name, cover, cover_pinned, revision, cover_portrait, "
            + "image_count, bytes, avg_pixels, max_width, max_height, shot_date, modified_at, indexed_at, model, studio, "
            + "photographer, description, tags, rating, content_id, signature, extracted_json, edited_json, search";
    /** H2's upsert: an existing row is updated in place, so unchanged index keys are not rewritten. */
    private static final String UPSERT = "MERGE INTO album (" + COLUMNS + ") KEY (id) VALUES ("
            + "?, ".repeat(COLUMNS.split(",").length - 1) + "?)";
    /** A directory itself or anything below it; parameters: the directory, then {@link #below} of it. */
    private static final String UNDER = "(path = ? OR path LIKE ? ESCAPE '!')";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final JsonMapper json;
    private final Path dir; // holds the database files, for stats
    private volatile boolean compactOnClose;

    JdbcAlbumRepository(JdbcTemplate template, JsonMapper json,
                        @Value("${thumbnailer.library-dir:${thumbnailer.data-dir}}") Path dir) {
        this.jdbc = JdbcClient.create(template);
        this.template = template;
        this.json = json;
        this.dir = dir;
        packLegacyEntries();
    }

    /** One-time upgrade: the per-image rows of older versions become packed values, then their table goes. */
    private void packLegacyEntries() {
        if (jdbc.sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'ENTRY'")
                .query(Integer.class).single() == 0) return;
        var albums = new LinkedHashMap<String, List<Entry>>();
        jdbc.sql("SELECT * FROM entry ORDER BY album_id, idx").query((RowCallbackHandler) rs -> albums
                .computeIfAbsent(rs.getString("album_id"), id -> new ArrayList<>())
                .add(new Entry(rs.getInt("idx"), rs.getString("name"), rs.getLong("bytes"), rs.getLong("mtime"),
                        rs.getInt("width"), rs.getInt("height"), rs.getObject("shot_at", LocalDateTime.class))));
        albums.forEach(this::saveEntries);
        template.execute("DROP TABLE entry");
    }

    private void saveEntries(String albumId, List<Entry> entries) {
        jdbc.sql("MERGE INTO entries (album_id, data) KEY (album_id) VALUES (?, ?)").params(albumId, EntryCodec.pack(entries)).update();
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
    public List<Album> byRoot(String dir) {
        return jdbc.sql("SELECT * FROM album WHERE " + UNDER).params(dir, below(dir)).query(this::album).list();
    }

    @Override
    public Map<String, Stamp> stamps(String dir) {
        var stamps = new HashMap<String, Stamp>();
        jdbc.sql("SELECT path, id, signature FROM album WHERE " + UNDER).params(dir, below(dir)).query((RowCallbackHandler) rs ->
                stamps.put(rs.getString(1), new Stamp(rs.getString(2), rs.getString(3))));
        return stamps;
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

    /**
     * Grouped by the first path segment below the parent: an archive directly under it is an album, not a folder; a
     * directory album there is a folder that counts itself.
     */
    @Override
    public List<Folder> folders(String parent) {
        var prefix = Containers.prefix(parent);
        var folders = new HashMap<String, int[]>(); // name -> {albums, nested}
        jdbc.sql("SELECT path, kind FROM album WHERE path LIKE ? ESCAPE '!'").params(below(parent)).query((RowCallbackHandler) rs -> {
            var rest = rs.getString(1).substring(prefix.length());
            int slash = rest.indexOf('/');
            if (rest.isEmpty() || slash < 0 && Kind.ZIP.name().equals(rs.getString(2))) return;
            var f = folders.computeIfAbsent(slash < 0 ? rest : rest.substring(0, slash), n -> new int[2]);
            f[0]++;
            if (slash >= 0) f[1] = 1;
        });
        return folders.entrySet().stream().map(e -> new Folder(e.getKey(), prefix + e.getKey(), e.getValue()[0], e.getValue()[1] > 0))
                .sorted(Comparator.comparing(Folder::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @Override
    public List<Entry> entries(String albumId) {
        return jdbc.sql("SELECT data FROM entries WHERE album_id = ?").params(albumId).query(byte[].class).optional()
                .map(EntryCodec::unpack).orElse(List.of());
    }

    @Override
    public Optional<Entry> entry(String albumId, int index) {
        var entries = entries(albumId);
        return index >= 0 && index < entries.size() ? Optional.of(entries.get(index)) : Optional.empty();
    }

    @Override
    @Transactional
    public void save(Album a, List<Entry> entries) {
        var tags = a.tags().isEmpty() ? " " : " " + String.join(" ", a.tags()) + " ";
        var search = Stream.of(a.name(), a.model(), a.studio(), a.photographer(), a.description(), tags.strip())
                .filter(Objects::nonNull).collect(Collectors.joining("\n")).toLowerCase(Locale.ROOT);
        jdbc.sql(UPSERT).params(a.id(), a.root(), a.path(), a.kind().name(), a.name(), a.cover(), a.coverPinned(),
                a.revision(), a.coverPortrait(), a.imageCount(), a.bytes(), a.avgPixels(), a.maxWidth(), a.maxHeight(),
                a.shotDate(), a.modifiedAt().toEpochMilli(), a.indexedAt().toEpochMilli(), a.model(), a.studio(),
                a.photographer(), a.description(), tags, a.rating(), a.internal().contentId(), a.internal().signature(),
                json.writeValueAsString(a.internal().extracted()), json.writeValueAsString(a.internal().edited()),
                search).update();
        jdbc.sql("DELETE FROM removed WHERE id = ?").params(a.id()).update();
        if (entries == null) return;
        saveEntries(a.id(), entries);
    }

    @Override
    @Transactional
    public void delete(String id) {
        jdbc.sql("MERGE INTO removed (content_id, id, revision, rating, cover, cover_pinned, edited_json, removed_at) KEY (content_id) "
                + "SELECT content_id, id, revision, rating, cover, cover_pinned, edited_json, ? FROM album WHERE id = ?")
                .params(System.currentTimeMillis(), id).update();
        jdbc.sql("DELETE FROM entries WHERE album_id = ?").params(id).update();
        jdbc.sql("DELETE FROM album WHERE id = ?").params(id).update();
    }

    @Override
    public Optional<Tombstone> recall(String contentId) {
        return jdbc.sql("SELECT id, revision, rating, cover, cover_pinned, edited_json FROM removed WHERE content_id = ?")
                .params(contentId).query((rs, row) -> new Tombstone(rs.getString(1), rs.getLong(2), rs.getInt(3),
                        rs.getString(4), rs.getBoolean(5), json.readValue(rs.getString(6), AlbumMeta.class))).optional();
    }

    @Override
    public void reroot(String from, String to) {
        jdbc.sql("UPDATE album SET root = ? WHERE root = ? AND " + UNDER).params(to, from, to, below(to)).update();
    }

    @Override
    public Stats stats() {
        long files = fileBytes();
        return jdbc.sql("SELECT COUNT(*), COALESCE(SUM(image_count), 0), COALESCE(SUM(bytes), 0), MAX(indexed_at), "
                + "DISK_SPACE_USED('ALBUM') + DISK_SPACE_USED('ENTRIES') + DISK_SPACE_USED('REMOVED') FROM album")
                .query((rs, row) -> new Stats(rs.getInt(1), rs.getLong(2), rs.getLong(3),
                        rs.getObject(4) == null ? null : Instant.ofEpochMilli(rs.getLong(4)), rs.getLong(5), files)).single();
    }

    /** {@code split:27} files: {@code albums.mv.db}, {@code albums.mv.db.1.part}, ... */
    private long fileBytes() {
        long size = 0;
        try (var files = Files.newDirectoryStream(dir, "albums.mv.db{,.*.part}")) {
            for (var f : files) size += Files.size(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return size;
    }

    @Override
    public void compact() { compactOnClose = true; }

    /**
     * The URL keeps the database open without connections ({@code DB_CLOSE_DELAY=-1}) and not closed by H2's exit hook,
     * so it closes here: after the scans stopped (context-closed event) and before the pool goes. After {@link #compact}
     * the files are rewritten without free space, which takes the database offline: only at close.
     */
    @Override
    public void close() { template.execute(compactOnClose ? "SHUTDOWN COMPACT" : "SHUTDOWN"); }

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

    /** Each order matches one index of {@code schema.sql} exactly, so the database never sorts a whole result. */
    private static String order(AlbumQuery.Sort sort) {
        return switch (sort) {
            case NEWEST -> "shot_date DESC NULLS LAST, ";
            case OLDEST -> "shot_date ASC NULLS LAST, ";
            case LARGEST -> "bytes DESC, ";
            case SMALLEST -> "bytes, ";
            case MOST_PHOTOS -> "image_count DESC, ";
            case FEWEST_PHOTOS -> "image_count, ";
            case BEST_QUALITY -> "avg_pixels DESC, ";
            case TOP_RATED -> "rating DESC, ";
            case NAME, NAME_DESC -> "";
            case RECENTLY_INDEXED -> "indexed_at DESC, ";
            case WORST_QUALITY -> "avg_pixels, ";
            case LOWEST_RATED -> "rating, ";
            case FIRST_INDEXED -> "indexed_at, ";
        } + (sort == AlbumQuery.Sort.NAME_DESC ? "sort_name DESC, id" : "sort_name, id");
    }

    private static void count(Map<String, Integer> counts, String value) {
        if (value != null && !value.isBlank()) counts.merge(value, 1, Integer::sum);
    }

    private static List<Facets.Count> top(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(50).map(e -> new Facets.Count(e.getKey(), e.getValue())).toList();
    }

    /** The LIKE pattern of every path strictly below directory {@code dir}. */
    private static String below(String dir) { return Where.escape(Containers.prefix(dir)) + "%"; }

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
                w.params.put("under" + i, below(root));
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
