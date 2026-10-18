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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import thumbnailer.album.Link;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.EntityKind;
import thumbnailer.catalog.Resolution;
import thumbnailer.source.Containers;
import thumbnailer.source.Kind;
import tools.jackson.databind.json.JsonMapper;

/**
 * Albums in SQL ({@code schema.sql}; H2 dialect for {@code MERGE ... KEY} upserts and {@code SHUTDOWN}). Columns hold the effective metadata so search is plain SQL; tags are
 * stored as {@code " a b "} for {@link #album} and the search text (tag filters and facets go through {@code album_link},
 * alias-resolved); {@code search} is the lower-cased searchable text,
 * extended by the aliases of the album's catalog links. {@code seq} (an identity) keys the catalog tables.
 */
@Repository
@ConditionalOnProperty(name = "thumbnailer.store.albums", havingValue = "jdbc", matchIfMissing = true)
class JdbcAlbumRepository implements AlbumRepository, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcAlbumRepository.class);
    private static final String COLUMNS = "id, root, path, kind, name, cover, cover_pinned, revision, cover_portrait, "
            + "image_count, bytes, avg_pixels, max_width, max_height, shot_date, modified_at, indexed_at, model, studio, "
            + "photographer, description, tags, rating, content_id, signature, extracted_json, edited_json, search, hidden, bio";
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
        keyTombstonesPerAlbum();
    }

    /**
     * One-time upgrade: tombstones were one per content, so removing two copies of a gallery lost the first one's state;
     * now one per (content, album id). No key at all is an upgrade stopped between its two statements (each DDL commits).
     * ({@code album.seq} needs none: H2 numbers existing rows when it adds the identity.)
     */
    private void keyTombstonesPerAlbum() {
        int columns = jdbc.sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k "
                + "ON k.CONSTRAINT_NAME = t.CONSTRAINT_NAME AND k.TABLE_NAME = t.TABLE_NAME "
                + "WHERE t.TABLE_SCHEMA = 'PUBLIC' AND t.TABLE_NAME = 'REMOVED' AND t.CONSTRAINT_TYPE = 'PRIMARY KEY'")
                .query(Integer.class).single();
        if (columns == 2) return;
        log.info("Keying tombstones per album");
        if (columns == 1) template.execute("ALTER TABLE removed DROP PRIMARY KEY");
        template.execute("ALTER TABLE removed ADD PRIMARY KEY (content_id, id)");
    }

    /** One-time upgrade: the per-image rows of older versions become packed values, then their table goes. */
    private void packLegacyEntries() {
        if (jdbc.sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'ENTRY'")
                .query(Integer.class).single() == 0) return;
        var albums = new LinkedHashMap<String, List<Entry>>();
        jdbc.sql("SELECT * FROM entry ORDER BY album_id, idx").query((RowCallbackHandler) rs -> albums
                .computeIfAbsent(rs.getString("album_id"), id -> new ArrayList<>())
                .add(new Entry(rs.getInt("idx"), rs.getString("name"), rs.getLong("bytes"), rs.getLong("mtime"),
                        rs.getInt("width"), rs.getInt("height"), rs.getObject("shot_at", LocalDateTime.class), 0)));
        albums.forEach(this::saveEntries);
        template.execute("DROP TABLE entry");
    }

    @Override
    public void saveEntries(String albumId, List<Entry> entries) {
        jdbc.sql("MERGE INTO entries (album_id, data) KEY (album_id) VALUES (?, ?)").params(albumId, EntryCodec.pack(entries)).update();
    }

    @Override
    public List<Album> search(AlbumQuery q) {
        var w = Where.of(q);
        return withCatalog(jdbc.sql("SELECT * FROM album" + w.sql() + " ORDER BY " + order(q.sort()) + " LIMIT :limit OFFSET :offset")
                .params(w.params()).param("limit", q.limit()).param("offset", q.offset()).query(this::album).list());
    }

    @Override
    public int count(AlbumQuery q) {
        var w = Where.of(q);
        return jdbc.sql("SELECT COUNT(*) FROM album" + w.sql()).params(w.params()).query(Integer.class).single();
    }

    /** One {@code GROUP BY} over the text links of the matching albums: primary names, top 50 per role. */
    @Override
    public Facets facets(AlbumQuery q) {
        var w = Where.of(q);
        var top = new EnumMap<EntityKind, List<Facets.Count>>(EntityKind.class);
        jdbc.sql("SELECT l.role, e.name, COUNT(*) AS n FROM album_link l JOIN entity e ON e.id = l.entity_id WHERE l.role <> 6 "
                        + "AND l.album_seq IN (SELECT seq FROM album" + w.sql() + ") GROUP BY l.role, l.entity_id, e.name, e.sort_name "
                        + "ORDER BY l.role, n DESC, e.sort_name")
                .params(w.params()).query((RowCallbackHandler) rs -> {
                    var counts = top.computeIfAbsent(EntityKind.of(rs.getInt(1)), k -> new ArrayList<>());
                    if (counts.size() < 50) counts.add(new Facets.Count(rs.getString(2), rs.getInt(3)));
                });
        return new Facets(top.getOrDefault(EntityKind.TAG, List.of()), top.getOrDefault(EntityKind.MODEL, List.of()),
                top.getOrDefault(EntityKind.STUDIO, List.of()), top.getOrDefault(EntityKind.PHOTOGRAPHER, List.of()),
                top.getOrDefault(EntityKind.GENRE, List.of()));
    }

    @Override
    public Optional<Album> find(String id) {
        return withCatalog(jdbc.sql("SELECT * FROM album WHERE id = ?").params(id).query(this::album).list()).stream().findFirst();
    }

    @Override
    public List<Album> byRoot(String dir) {
        return withCatalog(jdbc.sql("SELECT * FROM album WHERE " + UNDER).params(dir, below(dir)).query(this::album).list());
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
        return withCatalog(jdbc.sql("SELECT * FROM album WHERE content_id = ?").params(contentId).query(this::album).list());
    }

    @Override
    public Map<String, RootStats> rootStats() {
        var stats = new HashMap<String, RootStats>();
        // hidden albums do not count, but their root is listed: retainRoots must still meet it
        jdbc.sql("SELECT root, SUM(CASE WHEN hidden THEN 0 ELSE 1 END), MAX(indexed_at) FROM album GROUP BY root").query((RowCallbackHandler) rs ->
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
        jdbc.sql("SELECT path, kind FROM album WHERE path LIKE ? ESCAPE '!' AND NOT hidden").params(below(parent)).query((RowCallbackHandler) rs -> {
            var rest = rs.getString(1).substring(prefix.length());
            int slash = rest.indexOf('/');
            if (rest.isEmpty() || slash < 0 && !Kind.DIRECTORY.name().equals(rs.getString(2))) return;
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

    /**
     * The album row ({@code seq} stays out of it: the identity numbers inserts), then its seq, text links, counts and
     * renamed photo marks. Counts go over every role: favorites links are not replaced, so they count only when the
     * visibility flips.
     */
    @Override
    @Transactional
    public void save(Album a, List<Entry> entries, Resolution r, Save s) {
        var tags = a.tags().isEmpty() ? " " : " " + String.join(" ", a.tags()) + " ";
        var search = Stream.of(a.name(), a.model(), a.studio(), a.photographer(), a.description(), tags.strip(),
                        r.aliasText().isBlank() ? null : r.aliasText())
                .filter(Objects::nonNull).collect(Collectors.joining("\n")).toLowerCase(Locale.ROOT);
        jdbc.sql(UPSERT).params(a.id(), a.root(), a.path(), a.kind().name(), a.name(), a.cover(), a.coverPinned(),
                a.revision(), a.coverPortrait(), a.imageCount(), a.bytes(), a.avgPixels(), a.maxWidth(), a.maxHeight(),
                a.shotDate(), a.modifiedAt().toEpochMilli(), a.indexedAt().toEpochMilli(), a.model(), a.studio(),
                a.photographer(), a.description(), tags, a.rating(), a.internal().contentId(), a.internal().signature(),
                json.writeValueAsString(a.internal().extracted()), json.writeValueAsString(a.internal().edited()),
                search, a.hidden(), a.bio()).update();
        int seq = a.internal().seq();
        if (seq > 0) jdbc.sql("UPDATE album SET seq = ? WHERE id = ? AND seq <> ?").params(seq, a.id(), seq).update(); // adopted
        else seq = jdbc.sql("SELECT seq FROM album WHERE id = ?").params(a.id()).query(Integer.class).single();
        boolean counted = !s.suspend() && (s.visibleBefore() || s.visibleAfter());
        var before = counted && s.visibleBefore() ? linked(seq) : Set.<Integer>of();
        jdbc.sql("DELETE FROM album_link WHERE album_seq = ? AND role <> 6").params(seq).update();
        int key = seq;
        template.batchUpdate("MERGE INTO album_link (album_seq, role, entity_id, alias) KEY (album_seq, role, entity_id) VALUES (?, ?, ?, ?)",
                r.links().stream().map(l -> new Object[]{key, l.role().code(), l.entity(), l.alias()}).toList());
        if (counted) count(before, s.visibleAfter() ? linked(seq) : Set.of());
        renamePhotos(seq, s.nameMap());
        jdbc.sql("DELETE FROM removed WHERE id = ?").params(a.id()).update();
        if (entries == null) return;
        saveEntries(a.id(), entries);
    }

    private Set<Integer> linked(int seq) {
        return jdbc.sql("SELECT entity_id FROM album_link WHERE album_seq = ?").params(seq).query(Integer.class).set();
    }

    /**
     * {@code entity.albums}: +1 for an entity linked only after, −1 for one linked only before; rows in id order, so two
     * saves at once lock shared entity rows in one order (no deadlock).
     */
    private void count(Set<Integer> before, Set<Integer> after) {
        var deltas = new TreeMap<Integer, Integer>();
        after.forEach(e -> deltas.merge(e, 1, Integer::sum));
        before.forEach(e -> deltas.merge(e, -1, Integer::sum));
        template.batchUpdate("UPDATE entity SET albums = albums + ? WHERE id = ?", deltas.entrySet().stream()
                .filter(d -> d.getValue() != 0).map(d -> new Object[]{d.getValue(), d.getKey()}).toList());
    }

    /**
     * Photo marks follow renamed entries in two steps, old → a temporary name → new, so swaps and chains work; a mark
     * still under a new name afterwards belonged to a file that no longer has it, and goes.
     */
    private void renamePhotos(int seq, Map<String, String> nameMap) {
        var moves = nameMap.entrySet().stream().filter(e -> !e.getKey().equals(e.getValue())).toList();
        if (moves.isEmpty()) return;
        var rename = "UPDATE photo SET name = ? WHERE album_seq = ? AND name = ?";
        template.batchUpdate(rename, IntStream.range(0, moves.size()).mapToObj(i -> new Object[]{"\0" + i, seq, moves.get(i).getKey()}).toList());
        var taken = moves.stream().map(m -> new Object[]{seq, m.getValue()}).toList();
        template.batchUpdate("DELETE FROM photo_link WHERE photo_id IN (SELECT id FROM photo WHERE album_seq = ? AND name = ?)", taken);
        template.batchUpdate("DELETE FROM photo WHERE album_seq = ? AND name = ?", taken);
        template.batchUpdate(rename, IntStream.range(0, moves.size()).mapToObj(i -> new Object[]{moves.get(i).getValue(), seq, "\0" + i}).toList());
    }

    @Override
    @Transactional
    public void delete(String id) {
        jdbc.sql("MERGE INTO removed (content_id, id, revision, rating, cover, cover_pinned, edited_json, removed_at, seq, hidden) "
                + "KEY (content_id, id) SELECT content_id, id, revision, rating, cover, cover_pinned, edited_json, ?, seq, hidden "
                + "FROM album WHERE id = ?").params(System.currentTimeMillis(), id).update();
        count(jdbc.sql("SELECT l.entity_id FROM album_link l JOIN album a ON a.seq = l.album_seq WHERE a.id = ? AND NOT a.hidden")
                .params(id).query(Integer.class).set(), Set.of());
        jdbc.sql("DELETE FROM entries WHERE album_id = ?").params(id).update();
        jdbc.sql("DELETE FROM album WHERE id = ?").params(id).update();
    }

    @Override
    public Optional<Tombstone> recall(String contentId) {
        return jdbc.sql("SELECT id, revision, rating, cover, cover_pinned, edited_json, seq, hidden FROM removed "
                        + "WHERE content_id = ? ORDER BY removed_at DESC LIMIT 1")
                .params(contentId).query((rs, row) -> new Tombstone(rs.getString(1), rs.getLong(2), rs.getInt(3),
                        rs.getString(4), rs.getBoolean(5), json.readValue(rs.getString(6), AlbumMeta.class),
                        rs.getInt(7), rs.getBoolean(8))).optional();
    }

    @Override
    public void reroot(String from, String to) {
        jdbc.sql("UPDATE album SET root = ? WHERE root = ? AND " + UNDER).params(to, from, to, below(to)).update();
    }

    @Override
    public void relocate(String id, String path, String root) {
        jdbc.sql("UPDATE album SET path = ?, root = ?, revision = revision + 1 WHERE id = ?").params(path, root, id).update();
    }

    @Override
    @Transactional
    public int purge(Instant before) {
        var old = "SELECT seq FROM removed WHERE removed_at < ?";
        long t = before.toEpochMilli();
        jdbc.sql("DELETE FROM photo_link WHERE photo_id IN (SELECT id FROM photo WHERE album_seq IN (" + old + "))").params(t).update();
        jdbc.sql("DELETE FROM photo WHERE album_seq IN (" + old + ")").params(t).update();
        jdbc.sql("DELETE FROM album_link WHERE album_seq IN (" + old + ")").params(t).update();
        return jdbc.sql("DELETE FROM removed WHERE removed_at < ?").params(t).update();
    }

    @Override
    public Stats stats() {
        long files = fileBytes();
        return jdbc.sql("SELECT COUNT(*), COALESCE(SUM(image_count), 0), COALESCE(SUM(bytes), 0), MAX(indexed_at), "
                + "DISK_SPACE_USED('ALBUM') + DISK_SPACE_USED('ENTRIES') + DISK_SPACE_USED('REMOVED') FROM album WHERE NOT hidden")
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

    /**
     * Without links and copies ({@link #withCatalog} adds them). A row written before the shot date became the effective
     * date holds the computed date in the column only: without an edited date, the column is the extracted date.
     */
    private Album album(ResultSet rs, int row) throws SQLException {
        var tags = rs.getString("tags").strip();
        var shot = rs.getObject("shot_date", LocalDate.class);
        var edited = json.readValue(rs.getString("edited_json"), AlbumMeta.class);
        var extracted = json.readValue(rs.getString("extracted_json"), AlbumMeta.class);
        if (edited.date() == null) extracted = extracted.withDate(shot);
        return new Album(rs.getString("id"), rs.getString("root"), rs.getString("path"), Kind.valueOf(rs.getString("kind")),
                rs.getString("name"), rs.getString("cover"), rs.getBoolean("cover_pinned"), rs.getLong("revision"),
                rs.getBoolean("cover_portrait"), rs.getInt("image_count"), rs.getLong("bytes"), rs.getLong("avg_pixels"),
                rs.getInt("max_width"), rs.getInt("max_height"), shot,
                Instant.ofEpochMilli(rs.getLong("modified_at")), Instant.ofEpochMilli(rs.getLong("indexed_at")),
                rs.getString("model"), rs.getString("studio"), rs.getString("photographer"), rs.getString("description"),
                tags.isEmpty() ? List.of() : List.of(tags.split(" ")), rs.getInt("rating"), rs.getBoolean("hidden"),
                rs.getString("bio"), Objects.requireNonNullElse(edited.over(extracted).genres(), List.of()), List.of(), 0,
                new Album.Internal(rs.getString("content_id"), rs.getString("signature"), extracted, edited, rs.getInt("seq")));
    }

    /** Adds each album's catalog links (by role, then name) and copies count: two queries for the whole list. */
    private List<Album> withCatalog(List<Album> albums) {
        if (albums.isEmpty()) return albums;
        var links = new HashMap<Integer, List<Link>>();
        jdbc.sql("SELECT l.album_seq, l.entity_id, l.role, e.name, l.alias FROM album_link l JOIN entity e ON e.id = l.entity_id "
                        + "WHERE l.album_seq IN (:seqs) ORDER BY l.role, e.sort_name")
                .param("seqs", albums.stream().map(a -> a.internal().seq()).toList()).query((RowCallbackHandler) rs ->
                        links.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(new Link(rs.getInt(2),
                                EntityKind.of(rs.getInt(3)), rs.getString(4), rs.getString(5))));
        var copies = new HashMap<String, Integer>();
        jdbc.sql("SELECT content_id, COUNT(*) FROM album WHERE content_id IN (:ids) GROUP BY content_id")
                .param("ids", albums.stream().map(a -> a.internal().contentId()).distinct().toList())
                .query((RowCallbackHandler) rs -> copies.put(rs.getString(1), rs.getInt(2)));
        return albums.stream().map(a -> a.withCatalog(links.getOrDefault(a.internal().seq(), List.of()),
                copies.getOrDefault(a.internal().contentId(), 1))).toList();
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

    /** The LIKE pattern of every path strictly below directory {@code dir}. */
    private static String below(String dir) { return Where.escape(Containers.prefix(dir)) + "%"; }

    /** A parameterized WHERE clause built from the non-null query fields. */
    record Where(List<String> conditions, Map<String, Object> params) {

        static Where of(AlbumQuery q) {
            var w = new Where(new ArrayList<>(), new HashMap<>());
            w.add("hidden = :hidden", "hidden", Boolean.TRUE.equals(q.hidden()));
            if (Boolean.TRUE.equals(q.duplicates()))
                w.conditions.add("content_id IN (SELECT content_id FROM album GROUP BY content_id HAVING COUNT(*) > 1)");
            var entities = q.entities() == null ? List.<Integer>of() : q.entities().stream().filter(Objects::nonNull).toList();
            for (int i = 0; i < entities.size(); i++)
                w.add("EXISTS (SELECT 1 FROM album_link WHERE album_seq = album.seq AND entity_id = :entity" + i + ")",
                        "entity" + i, entities.get(i));
            w.add("NOT EXISTS (SELECT 1 FROM album_link WHERE album_seq = album.seq AND role = :missing)", "missing",
                    q.missing() == null ? null : q.missing().code());
            w.add("search LIKE :text ESCAPE '!'", "text", q.text() == null || q.text().isBlank() ? null : "%" + escape(q.text().strip().toLowerCase(Locale.ROOT)) + "%");
            w.named("model", EntityKind.MODEL, q.model());
            w.named("studio", EntityKind.STUDIO, q.studio());
            w.named("photographer", EntityKind.PHOTOGRAPHER, q.photographer());
            var genres = q.genres() == null ? List.<String>of() : q.genres().stream().filter(Objects::nonNull).toList();
            for (int i = 0; i < genres.size(); i++) w.named("genre" + i, EntityKind.GENRE, genres.get(i));
            var tags = q.tags() == null ? List.<String>of() : q.tags().stream().filter(Objects::nonNull).toList();
            for (int i = 0; i < tags.size(); i++) w.named("tag" + i, EntityKind.TAG, tags.get(i));
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

        /**
         * Linked to the entity of the kind known by this name (any alias, normalized like resolution): one alias primary
         * key lookup and one probe of the album's link key; a name no entity has matches nothing. Blank = no filter.
         */
        void named(String param, EntityKind kind, String name) {
            add("EXISTS (SELECT 1 FROM alias n JOIN album_link l ON l.entity_id = n.entity_id WHERE n.kind = " + kind.code()
                    + " AND n.norm = :" + param + " AND l.album_seq = album.seq AND l.role = " + kind.code() + ")", param,
                    name == null || name.isBlank() ? null : Catalog.norm(kind, name));
        }

        String sql() { return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions); }

        static String escape(String s) {
            return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }
    }
}
