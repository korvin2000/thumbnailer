package thumbnailer.store.jdbc;

import static java.util.stream.Collectors.joining;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import thumbnailer.album.Link;
import thumbnailer.catalog.Card;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.CatalogRepository;
import thumbnailer.catalog.Entity;
import thumbnailer.catalog.EntityKind;
import thumbnailer.catalog.EntityLink;
import thumbnailer.catalog.PhotoMark;
import thumbnailer.catalog.Resolution;
import thumbnailer.spi.AlbumMeta;
import tools.jackson.databind.json.JsonMapper;

/** The catalog tables of {@code schema.sql}, next to the albums in the same H2 database (counts and filters join them). */
@Repository
@ConditionalOnProperty(name = "thumbnailer.store.albums", havingValue = "jdbc", matchIfMissing = true)
class JdbcCatalogRepository implements CatalogRepository {

    /** A list's photos of visible albums. */
    private static final String LISTED = " FROM photo_link l JOIN photo p ON p.id = l.photo_id JOIN album a ON a.seq = p.album_seq"
            + " WHERE l.entity_id = ? AND NOT a.hidden";
    /** A list's photo count is computed: lists are few, and only photos of visible albums count. */
    private static final String ENTITY = "SELECT id, kind, name, rating, auto, albums, profile, CASE WHEN kind = 6 THEN "
            + "(SELECT COUNT(*) FROM photo_link l JOIN photo p ON p.id = l.photo_id JOIN album a ON a.seq = p.album_seq "
            + "WHERE l.entity_id = e.id AND NOT a.hidden) END FROM entity e";
    /** Per entity of a kind, the visible albums it shares with {@code :under}. */
    private static final String SHARED = "SELECT l.entity_id, COUNT(*) AS shared FROM album_link u "
            + "JOIN album a ON a.seq = u.album_seq AND NOT a.hidden JOIN album_link l ON l.album_seq = u.album_seq AND l.role = :kind "
            + "WHERE u.entity_id = :under AND l.entity_id <> :under GROUP BY l.entity_id";
    private static final String VISIBLE = "SELECT COUNT(*) FROM album_link l JOIN album a ON a.seq = l.album_seq "
            + "WHERE l.entity_id = entity.id AND NOT a.hidden";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int CHUNK = 1000;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    JdbcCatalogRepository(JdbcTemplate template) {
        this.jdbc = JdbcClient.create(template);
        this.template = template;
    }

    @Override
    public List<Entity> list(EntityKind kind, String prefix, Integer under, boolean unused, boolean byCount, int offset, int limit) {
        var sql = under == null ? ENTITY + where(prefix, unused) + " ORDER BY " + (byCount ? "albums DESC, " : "") + "sort_name, id"
                : "SELECT e.id, e.kind, e.name, e.rating, e.auto, s.shared, e.profile, NULL FROM entity e JOIN (" + SHARED
                + ") s ON s.entity_id = e.id" + named(prefix, unused, " WHERE") + " ORDER BY " + (byCount ? "s.shared DESC, " : "") + "e.sort_name, e.id";
        return jdbc.sql(sql + " LIMIT :limit OFFSET :offset").param("kind", kind.code()).param("prefix", like(prefix))
                .param("under", under).param("limit", limit).param("offset", offset).query(JdbcCatalogRepository::entity).list();
    }

    @Override
    public int count(EntityKind kind, String prefix, Integer under, boolean unused) {
        var sql = under == null ? "SELECT COUNT(*) FROM entity e" + where(prefix, unused)
                : "SELECT COUNT(*) FROM entity e JOIN (" + SHARED + ") s ON s.entity_id = e.id" + named(prefix, unused, " WHERE");
        return jdbc.sql(sql).param("kind", kind.code()).param("prefix", like(prefix)).param("under", under).query(Integer.class).single();
    }

    @Override
    public Optional<Entity> find(int id) {
        return jdbc.sql(ENTITY + " WHERE id = ?").params(id).query(JdbcCatalogRepository::entity).optional();
    }

    @Override
    public Optional<Integer> resolve(EntityKind kind, String norm) {
        return jdbc.sql("SELECT entity_id FROM alias WHERE kind = ? AND norm = ?").params(kind.code(), norm).query(Integer.class).optional();
    }

    @Override
    public Map<String, Entity> named(EntityKind kind, Collection<String> norms) {
        var named = new HashMap<String, Entity>();
        jdbc.sql("SELECT e.id, e.kind, e.name, e.rating, e.auto, e.albums, e.profile, NULL, a.norm FROM alias a "
                        + "JOIN entity e ON e.id = a.entity_id WHERE a.kind = :kind AND a.norm IN (:norms)")
                .param("kind", kind.code()).param("norms", List.copyOf(norms))
                .query((RowCallbackHandler) rs -> named.put(rs.getString(9), entity(rs, 0)));
        return named;
    }

    @Override
    @Transactional
    public int insert(Entity e, String norm) {
        int id = row(e);
        addAlias(id, e.kind(), norm);
        return id;
    }

    private int row(Entity e) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO entity (kind, name, rating, auto, albums, profile) VALUES (?, ?, ?, ?, ?, ?)")
                .params(e.kind().code(), e.name(), e.rating(), e.auto(), e.albums(), e.profile()).update(keys, "id");
        return keys.getKeyAs(Integer.class);
    }

    @Override
    public void update(Entity e) {
        jdbc.sql("UPDATE entity SET name = ?, rating = ?, auto = ?, profile = ? WHERE id = ?")
                .params(e.name(), e.rating(), e.auto(), e.profile(), e.id()).update();
    }

    @Override
    @Transactional
    public int store(Entity e, List<String> norms, List<EntityLink> links) {
        var old = e.id() == 0 ? null : find(e.id()).orElse(null);
        var names = old == null ? Set.<String>of() : Set.copyOf(aliases(e.id()));
        int id = e.id() == 0 ? row(e) : e.id();
        if (e.id() != 0) update(e);
        jdbc.sql("DELETE FROM alias WHERE entity_id = ?").params(id).update();
        template.batchUpdate("INSERT INTO alias (kind, norm, entity_id) VALUES (?, ?, ?)",
                norms.stream().map(n -> new Object[]{e.kind().code(), n, id}).toList());
        if (links != null) {
            jdbc.sql("DELETE FROM entity_link WHERE from_id = ?").params(id).update();
            template.batchUpdate("INSERT INTO entity_link (from_id, to_id, alias, roles) VALUES (?, ?, ?, ?)",
                    links.stream().map(l -> new Object[]{id, l.to(), l.alias(), l.roles()}).toList());
        }
        if (old != null && !old.name().equals(e.name())) {
            if (!Catalog.norm(e.kind(), old.name()).equals(Catalog.norm(e.kind(), e.name()))) keepName(id, e.kind(), old.name());
            unalias(id, e.kind(), e.name());
        }
        if (old != null && !names.equals(Set.copyOf(norms))) research(seqs(id));
        return id;
    }

    @Override
    @Transactional
    public void delete(int id) {
        var seqs = seqs(id);
        for (var table : List.of("alias", "album_link", "photo_link"))
            jdbc.sql("DELETE FROM " + table + " WHERE entity_id = ?").params(id).update();
        jdbc.sql("DELETE FROM entity_link WHERE from_id = ? OR to_id = ?").params(id, id).update();
        jdbc.sql("DELETE FROM entity WHERE id = ?").params(id).update();
        research(seqs);
    }

    @Override
    public List<String> aliases(int id) {
        return jdbc.sql("SELECT norm FROM alias WHERE entity_id = ? ORDER BY norm").params(id).query(String.class).list();
    }

    @Override
    public void addAlias(int id, EntityKind kind, String norm) {
        jdbc.sql("INSERT INTO alias (kind, norm, entity_id) VALUES (?, ?, ?)").params(kind.code(), norm, id).update();
    }

    @Override
    public String aliasText(Collection<Integer> ids) {
        return String.join("\n", jdbc.sql("SELECT DISTINCT norm FROM alias WHERE entity_id IN (:ids) ORDER BY norm")
                .param("ids", List.copyOf(ids)).query(String.class).list());
    }

    @Override
    public List<Link> albumLinks(int seq) {
        return jdbc.sql("SELECT l.entity_id, l.role, e.name, l.alias FROM album_link l JOIN entity e ON e.id = l.entity_id "
                        + "WHERE l.album_seq = ? ORDER BY l.role, e.sort_name")
                .params(seq).query((rs, row) -> new Link(rs.getInt(1), EntityKind.of(rs.getInt(2)), rs.getString(3), rs.getString(4))).list();
    }

    @Override
    @Transactional
    public boolean setListLink(int seq, int entity, boolean linked) {
        boolean has = jdbc.sql("SELECT COUNT(*) FROM album_link WHERE album_seq = ? AND role = 6 AND entity_id = ?")
                .params(seq, entity).query(Integer.class).single() > 0;
        if (has == linked || jdbc.sql(linked ? "INSERT INTO album_link (album_seq, role, entity_id) VALUES (?, 6, ?)"
                : "DELETE FROM album_link WHERE album_seq = ? AND role = 6 AND entity_id = ?").params(seq, entity).update() == 0)
            return false;
        jdbc.sql("UPDATE entity SET albums = albums + ? WHERE id = ? AND EXISTS (SELECT 1 FROM album WHERE seq = ? AND NOT hidden)")
                .params(linked ? 1 : -1, entity, seq).update();
        return true;
    }

    @Override
    public List<EntityLink> entityLinks(int from) {
        return jdbc.sql("SELECT to_id, alias, roles FROM entity_link WHERE from_id = ? ORDER BY to_id").params(from)
                .query((rs, row) -> new EntityLink(rs.getInt(1), rs.getString(2), rs.getString(3))).list();
    }

    @Override
    public void adjust(Map<Integer, Integer> deltas) {
        template.batchUpdate("UPDATE entity SET albums = albums + ? WHERE id = ?",
                deltas.entrySet().stream().map(d -> new Object[]{d.getValue(), d.getKey()}).toList());
    }

    @Override
    public void recount() { template.update("UPDATE entity SET albums = (" + VISIBLE + ")"); }

    @Override
    public List<Listed> photos(int listId, int offset, int limit) {
        return jdbc.sql("SELECT a.id, a.name, a.revision, p.name, p.rating" + LISTED + " ORDER BY a.path, p.name LIMIT ? OFFSET ?")
                .params(listId, limit, offset).query((rs, i) -> new Listed(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getInt(5))).list();
    }

    @Override
    public int photoCount(int listId) { return jdbc.sql("SELECT COUNT(*)" + LISTED).params(listId).query(Integer.class).single(); }

    @Override
    public Map<String, PhotoMark> marks(int seq) {
        var marks = new LinkedHashMap<String, PhotoMark>();
        jdbc.sql("SELECT p.name, p.rating, l.entity_id FROM photo p LEFT JOIN photo_link l ON l.photo_id = p.id "
                + "WHERE p.album_seq = ? ORDER BY p.name, l.entity_id").params(seq).query((RowCallbackHandler) rs -> {
            int rating = rs.getInt(2);
            var list = rs.getObject(3, Integer.class);
            var lists = marks.computeIfAbsent(rs.getString(1), n -> new PhotoMark(rating, new ArrayList<>())).lists();
            if (list != null) lists.add(list);
        });
        return marks;
    }

    @Override
    @Transactional
    public void mark(int seq, String name, Integer rating, List<Integer> lists) {
        var row = jdbc.sql("SELECT id, rating FROM photo WHERE album_seq = ? AND name = ?").params(seq, name)
                .query((rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}).optional();
        int r = rating != null ? rating : row.map(x -> x[1]).orElse(0);
        var l = lists != null ? lists.stream().distinct().toList() : row.map(x -> jdbc.sql("SELECT entity_id FROM photo_link WHERE photo_id = ?")
                .params(x[0]).query(Integer.class).list()).orElse(List.of());
        if (r == 0 && l.isEmpty()) {
            row.ifPresent(x -> {
                jdbc.sql("DELETE FROM photo_link WHERE photo_id = ?").params(x[0]).update();
                jdbc.sql("DELETE FROM photo WHERE id = ?").params(x[0]).update();
            });
            return;
        }
        int id;
        if (row.isPresent()) {
            id = row.get()[0];
            jdbc.sql("UPDATE photo SET rating = ? WHERE id = ?").params(r, id).update();
        } else {
            var keys = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO photo (album_seq, name, rating) VALUES (?, ?, ?)").params(seq, name, r).update(keys, "id");
            id = keys.getKeyAs(Integer.class);
        }
        if (lists == null) return;
        jdbc.sql("DELETE FROM photo_link WHERE photo_id = ?").params(id).update();
        template.batchUpdate("INSERT INTO photo_link (photo_id, entity_id) VALUES (?, ?)", l.stream().map(e -> new Object[]{id, e}).toList());
    }

    @Override
    public int version() {
        return jdbc.sql("SELECT val FROM meta WHERE name = 'catalog_version'").query(String.class).optional().map(Integer::parseInt).orElse(0);
    }

    @Override
    public void version(int version) {
        jdbc.sql("MERGE INTO meta (name, val) KEY (name) VALUES ('catalog_version', ?)").params(String.valueOf(version)).update();
    }

    /** The columns hold the effective text; genres live in the two JSON layers only (edited over extracted). */
    @Override
    public List<Text> texts(int afterSeq, int limit) {
        return jdbc.sql("SELECT seq, model, studio, photographer, tags, extracted_json, edited_json FROM album WHERE seq > ? ORDER BY seq LIMIT ?")
                .params(afterSeq, limit).query((rs, row) -> {
                    var tags = rs.getString(5).strip();
                    var edited = genres(rs.getString(7));
                    var genres = edited != null ? edited : genres(rs.getString(6));
                    return new Text(rs.getInt(1), new AlbumMeta(null, rs.getString(2), rs.getString(3), rs.getString(4), null, null,
                            tags.isEmpty() ? List.of() : List.of(tags.split(" ")), genres));
                }).list();
    }

    @Override
    @Transactional
    public void relink(Map<Integer, Resolution> resolved) {
        if (resolved.isEmpty()) return;
        template.batchUpdate("DELETE FROM album_link WHERE album_seq = ? AND role <> 6", resolved.keySet().stream().map(s -> new Object[]{s}).toList());
        template.batchUpdate("INSERT INTO album_link (album_seq, role, entity_id, alias) VALUES (?, ?, ?, ?)", resolved.entrySet().stream()
                .flatMap(r -> r.getValue().links().stream().map(l -> new Object[]{r.getKey(), l.role().code(), l.entity(), l.alias()})).toList());
        research(resolved.keySet());
    }

    @Override
    @Transactional
    public void merge(List<Integer> from, Entity into) {
        int b = into.id();
        for (int a : from) {
            keepName(a, into.kind(), find(a).orElseThrow().name());
            jdbc.sql("DELETE FROM album_link l WHERE l.entity_id = ? AND EXISTS (SELECT 1 FROM album_link o WHERE o.album_seq = l.album_seq "
                    + "AND o.role = l.role AND o.entity_id = ?)").params(a, b).update();
            jdbc.sql("UPDATE album_link SET entity_id = ? WHERE entity_id = ?").params(b, a).update();
            jdbc.sql("DELETE FROM photo_link WHERE entity_id = ? AND photo_id IN (SELECT photo_id FROM photo_link WHERE entity_id = ?)").params(a, b).update();
            jdbc.sql("UPDATE photo_link SET entity_id = ? WHERE entity_id = ?").params(b, a).update();
            jdbc.sql("DELETE FROM entity_link WHERE from_id = ? AND to_id = ? OR from_id = ? AND to_id = ?").params(a, b, b, a).update();
            jdbc.sql("DELETE FROM entity_link WHERE from_id = ? AND to_id IN (SELECT to_id FROM entity_link WHERE from_id = ?)").params(a, b).update();
            jdbc.sql("DELETE FROM entity_link WHERE to_id = ? AND from_id IN (SELECT from_id FROM entity_link WHERE to_id = ?)").params(a, b).update();
            jdbc.sql("UPDATE entity_link SET from_id = ? WHERE from_id = ?").params(b, a).update();
            jdbc.sql("UPDATE entity_link SET to_id = ? WHERE to_id = ?").params(b, a).update();
            jdbc.sql("UPDATE alias SET entity_id = ? WHERE entity_id = ?").params(b, a).update();
            jdbc.sql("DELETE FROM entity WHERE id = ?").params(a).update();
        }
        update(into);
        unalias(b, into.kind(), into.name());
        jdbc.sql("UPDATE entity SET albums = (" + VISIBLE + ") WHERE id = ?").params(b).update();
        research(seqs(b));
    }

    @Override
    @Transactional
    public void credit(int model, int studio, String alias, String norm, String roles) {
        var set = roles == null ? jdbc.sql("UPDATE entity_link SET alias = ? WHERE from_id = ? AND to_id = ?").params(alias, model, studio)
                : jdbc.sql("UPDATE entity_link SET alias = ?, roles = ? WHERE from_id = ? AND to_id = ?").params(alias, roles, model, studio);
        if (set.update() == 0)
            jdbc.sql("INSERT INTO entity_link (from_id, to_id, alias, roles) VALUES (?, ?, ?, ?)").params(model, studio, alias, roles).update();
        if (resolve(EntityKind.MODEL, norm).isPresent()) return;
        addAlias(model, EntityKind.MODEL, norm);
        research(seqs(model));
    }

    @Override
    public List<String> albumIds(int entity, Integer with) {
        return jdbc.sql("SELECT a.id FROM album_link l JOIN album a ON a.seq = l.album_seq WHERE l.entity_id = :entity"
                        + (with == null ? "" : " AND EXISTS (SELECT 1 FROM album_link w WHERE w.album_seq = l.album_seq AND w.entity_id = :with)")
                        + " ORDER BY a.seq")
                .param("entity", entity).param("with", with).query(String.class).list();
    }

    @Override
    public int albumCount(int entity) {
        return jdbc.sql("SELECT COUNT(*) FROM album_link l JOIN album a ON a.seq = l.album_seq WHERE l.entity_id = ? AND NOT a.hidden")
                .params(entity).query(Integer.class).single();
    }

    @Override
    public List<Card.Credit> credits(int subject) {
        record Credit(Entity studio, Set<Integer> albums, Set<String> aliases, Set<String> genres) {}
        var credits = new LinkedHashMap<Integer, Credit>();
        jdbc.sql("SELECT e.id, e.kind, e.name, e.rating, e.auto, e.albums, e.profile, NULL, s.album_seq, s.alias, g.name "
                + "FROM album_link s JOIN album a ON a.seq = s.album_seq AND NOT a.hidden "
                + "JOIN album_link x ON x.album_seq = s.album_seq AND x.role = 2 AND x.entity_id <> s.entity_id JOIN entity e ON e.id = x.entity_id "
                + "LEFT JOIN album_link gl ON gl.album_seq = s.album_seq AND gl.role = 4 AND gl.entity_id <> s.entity_id "
                + "LEFT JOIN entity g ON g.id = gl.entity_id WHERE s.entity_id = ?").params(subject).query((RowCallbackHandler) rs -> {
            var c = credits.get(rs.getInt(1));
            if (c == null) credits.put(rs.getInt(1), c = new Credit(entity(rs, 0), new HashSet<>(), new TreeSet<>(),
                    new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
            c.albums().add(rs.getInt(9));
            if (rs.getString(10) != null) c.aliases().add(rs.getString(10));
            if (rs.getString(11) != null) c.genres().add(rs.getString(11));
        });
        return credits.values().stream().map(c -> new Card.Credit(c.studio(), List.copyOf(c.aliases()), List.copyOf(c.genres()), c.albums().size()))
                .sorted(Comparator.comparingInt(Card.Credit::albums).reversed().thenComparing(c -> c.entity().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The albums linked to the entity by text (seq). */
    private List<Integer> seqs(int id) {
        return jdbc.sql("SELECT album_seq FROM album_link WHERE entity_id = ? AND role <> 6").params(id).query(Integer.class).list();
    }

    /** The album links that named the entity by {@code name} (no alias: its primary name until now) keep it as their alias. */
    private void keepName(int id, EntityKind kind, String name) {
        if (kind != EntityKind.TAG) jdbc.sql("UPDATE album_link SET alias = ? WHERE entity_id = ? AND alias IS NULL").params(name, id).update();
    }

    /** An alias that is the entity's primary name is none: those album links lose it. */
    private void unalias(int id, EntityKind kind, String primary) {
        var p = Catalog.norm(kind, primary);
        var stale = jdbc.sql("SELECT album_seq, alias FROM album_link WHERE entity_id = ? AND alias IS NOT NULL").params(id)
                .query((rs, row) -> Catalog.norm(kind, rs.getString(2)).equals(p) ? new Object[]{rs.getInt(1), id} : null).list();
        template.batchUpdate("UPDATE album_link SET alias = NULL WHERE album_seq = ? AND entity_id = ?", stale.stream().filter(Objects::nonNull).toList());
    }

    /**
     * The search text of these albums from their columns and every name of the entities of their text links, as
     * {@code JdbcAlbumRepository.save} writes it (columns, then {@link #aliasText}); column only, never the album row.
     */
    private void research(Collection<Integer> seqs) {
        var all = List.copyOf(new HashSet<>(seqs));
        for (int i = 0; i < all.size(); i += CHUNK) {
            var chunk = all.subList(i, Math.min(all.size(), i + CHUNK));
            var names = new HashMap<Integer, Set<String>>();
            jdbc.sql("SELECT l.album_seq, a.norm FROM album_link l JOIN alias a ON a.entity_id = l.entity_id WHERE l.album_seq IN (:seqs) AND l.role <> 6")
                    .param("seqs", chunk).query((RowCallbackHandler) rs -> names.computeIfAbsent(rs.getInt(1), k -> new TreeSet<>()).add(rs.getString(2)));
            var rows = jdbc.sql("SELECT seq, name, model, studio, photographer, description, tags FROM album WHERE seq IN (:seqs)").param("seqs", chunk)
                    .query((rs, row) -> new Object[]{Stream.of(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                    rs.getString(6), rs.getString(7).strip(), names.containsKey(rs.getInt(1)) ? String.join("\n", names.get(rs.getInt(1))) : null)
                            .filter(Objects::nonNull).collect(joining("\n")).toLowerCase(Locale.ROOT), rs.getInt(1)}).list();
            template.batchUpdate("UPDATE album SET search = ? WHERE seq = ?", rows);
        }
    }

    /** A layer's genres; null = not set (an explicit empty list is set). */
    private static List<String> genres(String json) {
        var node = JSON.readTree(json).get("genres");
        if (node == null || node.isNull()) return null;
        var genres = new ArrayList<String>();
        node.forEach(g -> genres.add(g.asString()));
        return genres;
    }

    private static Entity entity(ResultSet rs, int row) throws SQLException {
        return new Entity(rs.getInt(1), EntityKind.of(rs.getInt(2)), rs.getString(3), rs.getInt(4), rs.getBoolean(5),
                rs.getInt(6), rs.getString(7), rs.getObject(8, Integer.class));
    }

    private static String where(String prefix, boolean unused) { return " WHERE kind = :kind" + named(prefix, unused, " AND"); }

    /** The name prefix, then {@code unused} (auto, and no album links to it: a hidden one's link counts); {@code join} first. */
    private static String named(String prefix, boolean unused, String join) {
        var named = prefix == null ? "" : join + " e.id IN (SELECT entity_id FROM alias WHERE kind = :kind AND norm LIKE :prefix ESCAPE '!')";
        return !unused ? named : named + (named.isEmpty() ? join : " AND") + " e.auto AND NOT EXISTS (SELECT 1 FROM album_link x WHERE x.entity_id = e.id)";
    }

    private static String like(String prefix) { return prefix == null ? null : JdbcAlbumRepository.Where.escape(prefix) + "%"; }
}
