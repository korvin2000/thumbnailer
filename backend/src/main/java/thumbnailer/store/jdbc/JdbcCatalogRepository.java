package thumbnailer.store.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import thumbnailer.album.Link;
import thumbnailer.catalog.CatalogRepository;
import thumbnailer.catalog.Entity;
import thumbnailer.catalog.EntityKind;
import thumbnailer.catalog.EntityLink;
import thumbnailer.catalog.PhotoMark;

/** The catalog tables of {@code schema.sql}, next to the albums in the same H2 database (counts and filters join them). */
@Repository
@ConditionalOnProperty(name = "thumbnailer.store.albums", havingValue = "jdbc", matchIfMissing = true)
class JdbcCatalogRepository implements CatalogRepository {

    /** A list's photo count is computed: lists are few, and only photos of visible albums count. */
    private static final String ENTITY = "SELECT id, kind, name, rating, auto, albums, profile, CASE WHEN kind = 6 THEN "
            + "(SELECT COUNT(*) FROM photo_link l JOIN photo p ON p.id = l.photo_id JOIN album a ON a.seq = p.album_seq "
            + "WHERE l.entity_id = e.id AND NOT a.hidden) END FROM entity e";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    JdbcCatalogRepository(JdbcTemplate template) {
        this.jdbc = JdbcClient.create(template);
        this.template = template;
    }

    @Override
    public List<Entity> list(EntityKind kind, String prefix, boolean byCount, int offset, int limit) {
        return jdbc.sql(ENTITY + where(prefix) + " ORDER BY " + (byCount ? "albums DESC, " : "") + "sort_name, id LIMIT :limit OFFSET :offset")
                .param("kind", kind.code()).param("prefix", like(prefix)).param("limit", limit).param("offset", offset)
                .query(JdbcCatalogRepository::entity).list();
    }

    @Override
    public int count(EntityKind kind, String prefix) {
        return jdbc.sql("SELECT COUNT(*) FROM entity e" + where(prefix)).param("kind", kind.code()).param("prefix", like(prefix))
                .query(Integer.class).single();
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
        int id = e.id() == 0 ? row(e) : e.id();
        if (e.id() != 0) update(e);
        jdbc.sql("DELETE FROM alias WHERE entity_id = ?").params(id).update();
        template.batchUpdate("INSERT INTO alias (kind, norm, entity_id) VALUES (?, ?, ?)",
                norms.stream().map(n -> new Object[]{e.kind().code(), n, id}).toList());
        if (links == null) return id;
        jdbc.sql("DELETE FROM entity_link WHERE from_id = ?").params(id).update();
        template.batchUpdate("INSERT INTO entity_link (from_id, to_id, alias, roles) VALUES (?, ?, ?, ?)",
                links.stream().map(l -> new Object[]{id, l.to(), l.alias(), l.roles()}).toList());
        return id;
    }

    @Override
    @Transactional
    public void delete(int id) {
        for (var table : List.of("alias", "album_link", "photo_link"))
            jdbc.sql("DELETE FROM " + table + " WHERE entity_id = ?").params(id).update();
        jdbc.sql("DELETE FROM entity_link WHERE from_id = ? OR to_id = ?").params(id, id).update();
        jdbc.sql("DELETE FROM entity WHERE id = ?").params(id).update();
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
    public void recount() {
        template.update("UPDATE entity SET albums = (SELECT COUNT(*) FROM album_link l JOIN album a ON a.seq = l.album_seq "
                + "WHERE l.entity_id = entity.id AND NOT a.hidden)");
    }

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

    private static Entity entity(ResultSet rs, int row) throws SQLException {
        return new Entity(rs.getInt(1), EntityKind.of(rs.getInt(2)), rs.getString(3), rs.getInt(4), rs.getBoolean(5),
                rs.getInt(6), rs.getString(7), rs.getObject(8, Integer.class));
    }

    private static String where(String prefix) {
        return " WHERE kind = :kind" + (prefix == null ? "" : " AND id IN (SELECT entity_id FROM alias WHERE kind = :kind AND norm LIKE :prefix ESCAPE '!')");
    }

    private static String like(String prefix) { return prefix == null ? null : JdbcAlbumRepository.Where.escape(prefix) + "%"; }
}
