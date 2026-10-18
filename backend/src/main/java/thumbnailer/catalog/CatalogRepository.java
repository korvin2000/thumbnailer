package thumbnailer.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import thumbnailer.album.Link;

/**
 * Port: the catalog tables (entities, aliases, album and entity links, photo marks, the catalog version). Plain row
 * operations; {@link Catalog} owns the rules and the locking. Albums are referred to by their {@code seq}.
 */
public interface CatalogRepository {

    /** One page of a kind, by name or by stored album count; {@code prefix}: over any alias norm, null = all. */
    List<Entity> list(EntityKind kind, String prefix, boolean byCount, int offset, int limit);

    int count(EntityKind kind, String prefix);

    Optional<Entity> find(int id);

    /** The entity of a kind known by this normalized name. */
    Optional<Integer> resolve(EntityKind kind, String norm);

    /** Inserts the entity with its primary alias; returns its id. A taken alias fails (and inserts nothing). */
    int insert(Entity e, String norm);

    /** Name, rating, auto and profile. */
    void update(Entity e);

    /**
     * In one transaction: inserts the entity ({@code id} 0) or updates it ({@link #update}), replaces its names by
     * {@code norms} and, unless {@code links} is null, its curated links; returns its id. A taken name fails and
     * changes nothing.
     */
    int store(Entity e, List<String> norms, List<EntityLink> links);

    /** The entity with its aliases, album links, entity links (both sides) and photo links. */
    void delete(int id);

    /** Every normalized name of the entity, its primary name included. */
    List<String> aliases(int id);

    void addAlias(int id, EntityKind kind, String norm);

    List<Link> albumLinks(int seq);

    /**
     * Adds the album to (or takes it out of) a favorites list; returns whether a row was inserted or deleted, and only
     * then, if the album is visible, the list's count follows.
     */
    boolean setListLink(int seq, int entity, boolean linked);

    List<EntityLink> entityLinks(int from);

    void adjust(Map<Integer, Integer> deltas);

    /** Every stored album count from the links of visible albums. */
    void recount();

    /** The marked photos of an album by entry name. */
    Map<String, PhotoMark> marks(int seq);

    /** {@code rating}/{@code lists} null = unchanged; a photo with neither a rating nor a list loses its row. */
    void mark(int seq, String name, Integer rating, List<Integer> lists);

    int version();

    void version(int version);
}
