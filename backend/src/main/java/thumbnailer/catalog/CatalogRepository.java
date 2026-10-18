package thumbnailer.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import thumbnailer.album.Link;
import thumbnailer.spi.AlbumMeta;

/**
 * Port: the catalog tables (entities, aliases, album and entity links, photo marks, the catalog version). Plain row
 * operations; {@link Catalog} owns the rules and the locking. Albums are referred to by their {@code seq}. An album's
 * {@code search} text (its columns and every alias of its text links) is recomputed here, column only, whenever the
 * names of its entities change.
 */
public interface CatalogRepository {

    /**
     * One page of a kind, by name or by album count; {@code prefix}: over any alias norm, null = all; {@code under}: only
     * entities linked to visible albums that entity is linked to, {@code albums} = those shared albums; null = all;
     * {@code unused}: only auto entities no album links to, hidden ones included.
     */
    List<Entity> list(EntityKind kind, String prefix, Integer under, boolean unused, boolean byCount, int offset, int limit);

    int count(EntityKind kind, String prefix, Integer under, boolean unused);

    Optional<Entity> find(int id);

    /** The entity of a kind known by this normalized name. */
    Optional<Integer> resolve(EntityKind kind, String norm);

    /** The entities of a kind known by these normalized names, by name. */
    Map<String, Entity> named(EntityKind kind, Collection<String> norms);

    /** Inserts the entity with its primary alias; returns its id. A taken alias fails (and inserts nothing). */
    int insert(Entity e, String norm);

    /** Name, rating, auto and profile. */
    void update(Entity e);

    /**
     * In one transaction: inserts the entity ({@code id} 0) or updates it ({@link #update}), replaces its names by
     * {@code norms} and, unless {@code links} is null, its curated links; returns its id. A rename keeps the old name on
     * the album links that said it ({@code alias}); changed names recompute its albums' search text. A taken name fails
     * and changes nothing.
     */
    int store(Entity e, List<String> norms, List<EntityLink> links);

    /** The entity with its aliases, album links, entity links (both sides) and photo links. */
    void delete(int id);

    /** Every normalized name of the entity, its primary name included. */
    List<String> aliases(int id);

    void addAlias(int id, EntityKind kind, String norm);

    /** What the search text of an album linked to these entities adds: their names, normalized, sorted, one per line. */
    String aliasText(Collection<Integer> ids);

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

    /** A photo of a favorites list with the album it lies in ({@code album}: its name). */
    record Listed(String albumId, String album, long revision, String name, int rating) {}

    /** One page of a list's photos in visible albums, by album path, then photo name. */
    List<Listed> photos(int listId, int offset, int limit);

    /** How many photos of visible albums the list holds. */
    int photoCount(int listId);

    int version();

    void version(int version);

    /** An album's effective text fields (name and description are not read). */
    record Text(int seq, AlbumMeta meta) {}

    /** The next albums by seq after {@code afterSeq} (the backfill's batches). */
    List<Text> texts(int afterSeq, int limit);

    /** In one transaction, per album (seq): its text links replaced by the resolution's and its search text recomputed. */
    void relink(Map<Integer, Resolution> resolved);

    /**
     * In one transaction, {@code from} merged into {@code into} (same kind): links that would collide and the links
     * between them go, the rest, the aliases and the curated links move over, the album links that said a merged name
     * keep it as their alias; {@code from} is deleted, {@code into} updated ({@link #update}) and recounted, the search
     * text of its albums recomputed.
     */
    void merge(List<Integer> from, Entity into);

    /**
     * In one transaction: the curated link model → studio says {@code alias} ({@code roles} null = unchanged) and the
     * model is known by it ({@code norm}); a new name recomputes its albums' search text.
     */
    void credit(int model, int studio, String alias, String norm, String roles);

    /** The ids of the albums (hidden ones too) linked to the entity and, unless null, to {@code with} as well. */
    List<String> albumIds(int entity, Integer with);

    /** Visible albums linked to the entity, counted now. */
    int albumCount(int entity);

    /**
     * The studios of the visible albums linked to {@code subject}: per studio the aliases those links of the subject
     * carry, the other genres of those albums and their count; most albums first.
     */
    List<Card.Credit> credits(int subject);
}
