package thumbnailer.catalog;

import static java.util.stream.Collectors.toCollection;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.Link;
import thumbnailer.index.Gate;
import thumbnailer.spi.AlbumMeta;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The catalog: entities, their names and links. One read-write lock: album saves resolve under the read lock (and
 * write only to create a missing entity); merges, renames, aliases, credits, deletes, list memberships, photo marks and
 * the backfill take the write lock and never an album's lock (the order is album stripe, then catalog): what must change
 * album text is a separate step through {@code AlbumService.patch}, for which this class computes the edits
 * ({@link #linkPatch}, {@link #creditPatch}). Changes are announced as {@link CatalogEvent}s after the lock.
 */
@Service
public class Catalog {

    private static final Logger log = LoggerFactory.getLogger(Catalog.class);
    private static final int NAME = 256, LIMIT = 500, RELATED = 100, TAGS = 50, BATCH = 1000, VERSION = 1;
    private static final List<EntityKind> TEXT = List.of(EntityKind.MODEL, EntityKind.STUDIO, EntityKind.PHOTOGRAPHER,
            EntityKind.GENRE, EntityKind.TAG);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CatalogRepository repo;
    private final ApplicationEventPublisher events;
    private final Gate gate;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean stopped; // the context closes: a waiting backfill gives up

    /** With a gate of its own (tests). */
    public Catalog(CatalogRepository repo, ApplicationEventPublisher events) { this(repo, events, new Gate()); }

    @Autowired
    public Catalog(CatalogRepository repo, ApplicationEventPublisher events, Gate gate) {
        this.repo = repo;
        this.events = events;
        this.gate = gate;
    }

    /** The lookup key of a name: stripped, lower case, whitespace collapsed, at most 256 chars; tags as {@link AlbumMeta#tag}. */
    public static String norm(EntityKind kind, String name) {
        var n = kind == EntityKind.TAG ? AlbumMeta.tag(name) : name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return cut(n);
    }

    /**
     * A page of a kind, {@code text} a prefix of any of their names, {@code under} (null = all) the entity whose visible
     * albums they share ({@code albums} = how many), {@code unused}: only auto ones no album links to (hidden albums
     * count); at most 500.
     */
    public List<Entity> entities(EntityKind kind, String text, Integer under, boolean unused, boolean byCount, int offset, int limit) {
        return read(() -> repo.list(Objects.requireNonNull(kind, "kind is required"), prefix(kind, text), under, unused, byCount,
                Math.max(0, offset), limit <= 0 ? 100 : Math.min(limit, LIMIT)));
    }

    public int count(EntityKind kind, String text, Integer under, boolean unused) {
        return read(() -> repo.count(kind, prefix(kind, text), under, unused));
    }

    public Entity entity(int id) { return read(() -> find(id)); }

    public Card get(int id) { return read(() -> card(id)); }

    /**
     * Creates or updates an entity with its whole alias set (a renamed entity keeps its old name as an alias, or texts
     * saying it would create a new one) and curated links; a name owned by another entity of the kind is refused. All
     * checks come first, then one transaction writes it all, the search text of its albums included when its names
     * changed: a refused or failed save changes nothing. Every save is announced (views cache entity lists, ratings
     * included).
     */
    public Card save(EntitySave s) {
        Objects.requireNonNull(s.kind(), "kind is required");
        if (s.name() == null || s.name().isBlank()) throw new IllegalArgumentException("A name is required");
        if (s.rating() < 0 || s.rating() > 10) throw new IllegalArgumentException("Rating must be 0..10");
        var name = s.kind() == EntityKind.TAG ? norm(s.kind(), s.name()) : cut(s.name().strip());
        int id = write(() -> {
            var old = s.id() == null ? null : find(s.id());
            if (old != null && old.kind() != s.kind()) throw new IllegalArgumentException("The kind of an entity cannot change");
            var names = new LinkedHashMap<String, String>(); // norm → as written
            names.put(norm(s.kind(), name), name);
            if (old != null) names.putIfAbsent(norm(s.kind(), old.name()), old.name());
            Stream.ofNullable(s.aliases()).flatMap(List::stream).filter(a -> a != null && !a.isBlank())
                    .forEach(a -> names.putIfAbsent(norm(s.kind(), a), a.strip()));
            if (s.aliases() == null && old != null) repo.aliases(old.id()).forEach(n -> names.putIfAbsent(n, n));
            names.forEach((n, written) -> refuseTaken(s.kind(), n, written, old == null ? 0 : old.id()));
            for (var l : s.links() == null ? List.<EntityLink>of() : s.links())
                if (repo.find(l.to()).isEmpty() || old != null && l.to() == old.id()) throw new IllegalArgumentException("Cannot link to " + l.to());
            if (s.links() != null) refuseParent(old, s.kind(), name, s.links());
            return repo.store(old == null ? new Entity(0, s.kind(), name, s.rating(), false, 0, s.profile(), null)
                    : new Entity(old.id(), s.kind(), name, s.rating(), old.auto(), old.albums(), s.profile(), null),
                    List.copyOf(names.keySet()), s.links());
        });
        events.publishEvent(new CatalogEvent(List.of(s.kind())));
        return get(id);
    }

    /**
     * Removes the entities with their names and links; the caller first rewrites the texts of their albums
     * ({@link #linkPatch}). Returns, not deleted, those an album's text still links to (a save named one after its
     * rewrite: deleting would leave that text without its link), unless {@code force}.
     */
    public List<Integer> delete(List<Integer> ids, boolean force) {
        var left = new ArrayList<Integer>();
        var kinds = EnumSet.noneOf(EntityKind.class);
        write(() -> {
            for (var id : ids.stream().distinct().toList()) {
                var e = find(id);
                if (!force && e.kind() != EntityKind.LIST && !repo.albumIds(id, null).isEmpty()) left.add(id);
                else {
                    repo.delete(id);
                    kinds.add(e.kind());
                }
            }
            return null;
        });
        if (!kinds.isEmpty()) events.publishEvent(new CatalogEvent(List.copyOf(kinds)));
        return left;
    }

    /** Auto entities become confirmed ones. */
    public void confirm(List<Integer> ids) {
        var kinds = write(() -> ids.stream().distinct().map(id -> {
            var e = find(id);
            repo.update(new Entity(e.id(), e.kind(), e.name(), e.rating(), false, e.albums(), e.profile(), null));
            return e.kind();
        }).distinct().toList());
        events.publishEvent(new CatalogEvent(kinds));
    }

    /**
     * Merges entities into one of their kind (§2.5): links, names and curated links move over (collisions and links
     * between them go), the album links that said a merged name keep it as their alias; the profile of {@code into}
     * wins and the merged ones fill its empty fields; rating = the highest, auto only if all were; then {@code into} is
     * recounted and its albums' search text recomputed, all in one transaction. Album texts are untouched: they still
     * say the merged names, which now resolve to {@code into}.
     */
    public Entity merge(List<Integer> from, int into) {
        Objects.requireNonNull(from, "from is required");
        var kind = write(() -> {
            var b = find(into);
            var merged = from.stream().distinct().filter(id -> id != into).map(this::find).toList();
            if (merged.isEmpty()) throw new IllegalArgumentException("Nothing to merge into " + b.name());
            merged.stream().filter(a -> a.kind() != b.kind()).findFirst().ifPresent(a -> {
                throw new IllegalArgumentException("Only entities of one kind merge: " + a.name() + " is no " + b.kind().name().toLowerCase(Locale.ROOT));
            });
            var ids = Set.copyOf(merged.stream().map(Entity::id).toList());
            var seen = new HashSet<Integer>(); // into below a merged one: the child link redirected to it would close a cycle
            for (int p = parent(b); p != 0 && seen.add(p); p = parent(find(p)))
                if (ids.contains(p)) throw new IllegalArgumentException(b.name() + " is inside " + find(p).name() + "; merge the other way round");
            var profile = b.profile();
            for (var a : merged) profile = fill(profile, a.profile());
            repo.merge(merged.stream().map(Entity::id).toList(), new Entity(b.id(), b.kind(), b.name(),
                    Math.max(b.rating(), merged.stream().mapToInt(Entity::rating).max().orElse(0)),
                    b.auto() && merged.stream().allMatch(Entity::auto), b.albums(), profile, null));
            return b.kind();
        });
        events.publishEvent(new CatalogEvent(List.of(kind)));
        return entity(into);
    }

    /**
     * The catalog half of "credited as Y at studio X" (§2.5): the curated link model → studio says {@code alias} (with
     * {@code roles}, null = unchanged) and the model is known by it; returns the albums of hers at that studio, whose
     * texts the caller then rewrites one by one ({@link #creditPatch}) after this lock is released.
     */
    public List<String> creditAs(int modelId, int studioId, String alias, String roles) {
        if (alias == null || alias.isBlank()) throw new IllegalArgumentException("An alias is required");
        var written = cut(alias.strip());
        var albums = write(() -> {
            var model = find(modelId);
            var studio = find(studioId);
            if (model.kind() != EntityKind.MODEL) throw new IllegalArgumentException(model.name() + " is not a model");
            if (studio.kind() != EntityKind.STUDIO) throw new IllegalArgumentException(studio.name() + " is not a studio");
            var norm = norm(EntityKind.MODEL, written);
            refuseTaken(EntityKind.MODEL, norm, written, modelId);
            repo.credit(modelId, studioId, written, norm, roles);
            return repo.albumIds(modelId, studioId);
        });
        events.publishEvent(new CatalogEvent(List.of(EntityKind.MODEL)));
        return albums;
    }

    /** The ids of every album (hidden ones too) linked to the entity: what deleting it rewrites first. */
    public List<String> albumIds(int entityId) { return read(() -> repo.albumIds(entityId, null)); }

    /**
     * The edit that puts the entity's name into the album's text of its kind ({@code linked}: added to the effective
     * list, so the extracted credits stay) or takes every name resolving to it out (an empty text is the explicit
     * {@code ""}, so a re-index does not link it again); null when the text already says so. Tags edit additively
     * ({@code addTags}/{@code removeTags}). Favorites lists are not text.
     */
    public AlbumPatch linkPatch(Album album, int entityId, boolean linked) {
        return read(() -> {
            var e = find(entityId);
            if (e.kind() == EntityKind.LIST) throw new IllegalArgumentException(e.name() + " is a favorites list");
            var names = names(e.kind(), texts(meta(album), e.kind()));
            if (names.stream().anyMatch(n -> n.is(entityId)) == linked) return null;
            if (e.kind() == EntityKind.TAG)
                return tags(linked ? List.of(e.name()) : null, linked ? null : names.stream().filter(n -> n.is(entityId)).map(Named::written).toList());
            var kept = names.stream().filter(n -> !n.is(entityId)).map(Named::written).collect(toCollection(ArrayList::new));
            if (linked) kept.add(e.name());
            return text(e.kind(), kept);
        });
    }

    /**
     * The edit that writes {@code alias} for every name of the album's model text that resolves to the model; null when
     * none says otherwise or the album's studio text no longer names the studio.
     */
    public AlbumPatch creditPatch(Album album, int modelId, int studioId, String alias) {
        var credited = cut(alias.strip());
        return read(() -> {
            var names = names(EntityKind.MODEL, texts(meta(album), EntityKind.MODEL));
            if (names.stream().noneMatch(n -> n.is(modelId) && !n.written().equals(credited))
                    || names(EntityKind.STUDIO, texts(meta(album), EntityKind.STUDIO)).stream().noneMatch(n -> n.is(studioId))) return null;
            var seen = new HashSet<String>();
            return text(EntityKind.MODEL, names.stream().map(n -> n.is(modelId) ? credited : n.written())
                    .filter(w -> seen.add(norm(EntityKind.MODEL, w))).toList());
        });
    }

    /**
     * Adds albums (by seq) to a favorites list or takes them out, serialized with every other membership and count
     * change (the write lock); returns how many links actually changed, announced only when some did.
     */
    public int setList(List<Integer> seqs, int listId, boolean linked) {
        int changed = write(() -> {
            var e = find(listId);
            if (e.kind() != EntityKind.LIST) throw new IllegalArgumentException(e.name() + " is not a favorites list");
            return (int) seqs.stream().filter(seq -> repo.setListLink(seq, listId, linked)).count();
        });
        if (changed > 0) events.publishEvent(new CatalogEvent(List.of(EntityKind.LIST)));
        return changed;
    }

    /**
     * Rebuilds every stored album count (after a scan, which skips the per-save deltas and the announcement of the
     * entities it created, and on optimize) and announces every kind.
     */
    public void recount() {
        write(() -> {
            repo.recount();
            return null;
        });
        events.publishEvent(new CatalogEvent(List.of(EntityKind.values())));
    }

    public Map<String, PhotoMark> marks(int seq) { return read(() -> repo.marks(seq)); }

    /** One page of a favorites list's photos in visible albums, by album path, then name. */
    public List<CatalogRepository.Listed> photos(int listId, int offset, int limit) { return read(() -> repo.photos(listId, offset, limit)); }

    public int photoCount(int listId) { return read(() -> repo.photoCount(listId)); }

    /**
     * Rates a photo or sets its lists (null = unchanged); {@code lists} must be favorites lists. Under the write lock: a
     * mark keeps the half it does not set, so two at once must not interleave (nor with an album save renaming marks).
     */
    public void mark(int seq, String name, Integer rating, List<Integer> lists) {
        if (rating != null && (rating < 0 || rating > 10)) throw new IllegalArgumentException("Rating must be 0..10");
        write(() -> {
            for (var id : lists == null ? List.<Integer>of() : lists)
                if (repo.find(id).filter(e -> e.kind() == EntityKind.LIST).isEmpty()) throw new IllegalArgumentException("No favorites list " + id);
            repo.mark(seq, name, rating, lists);
            return null;
        });
    }

    /**
     * Resolves an album's effective text into its links (roles 1–5) with the alias text for its search column, and runs
     * {@code save} with them, both under the catalog read lock: no merge, rename, alias or list change lands between the
     * resolution and the album's save, which also computes the count deltas from the links it replaces. A name no
     * entity has yet is created under the write lock (the read lock let go, taken again after), then the text is
     * resolved again before {@code save} runs: two albums naming a new entity at once create it once. New entities are
     * announced after the lock (not while a scan holds the gate: its recount announces). The caller holds the album's
     * stripe lock (order: stripe → catalog) and no catalog lock.
     */
    public void resolving(AlbumMeta effective, int seq, Consumer<Resolution> save) {
        var created = EnumSet.noneOf(EntityKind.class);
        var read = lock.readLock();
        read.lock();
        try {
            for (var missing = new ArrayList<Named>(); ; missing.clear()) {
                var r = resolve(effective, missing, created);
                if (r != null) {
                    save.accept(r);
                    break;
                }
                if (lock.getReadHoldCount() > 1) throw new IllegalStateException("An entity cannot be created inside a catalog read");
                read.unlock();
                try {
                    write(() -> {
                        missing.forEach(n -> create(n, created));
                        return null;
                    });
                } finally {
                    read.lock();
                }
            }
        } finally {
            read.unlock();
        }
        if (!created.isEmpty() && gate.current() != Gate.Activity.SCAN) events.publishEvent(new CatalogEvent(List.copyOf(created)));
    }

    /**
     * Runs {@code action} under the read lock, so no list change, merge or recount lands in the middle of a transaction
     * that adjusts counts (an album's delete). The caller holds the album's stripe lock (order: stripe → catalog).
     */
    public void reading(Runnable action) {
        read(() -> {
            action.run();
            return null;
        });
    }

    /**
     * Resolves the text of every album once ({@code meta.catalog_version} below 1): links and search text by plain SQL
     * in batches of 1000 under the write lock (no album save, event or revision), then {@link #recount}, then the marker.
     * Idempotent: links are replaced per album. The caller holds the gate.
     */
    public void backfill() {
        if (repo.version() >= VERSION) return;
        int albums = 0;
        for (int after = 0; ; ) {
            int from = after;
            var batch = write(() -> {
                var texts = repo.texts(from, BATCH);
                var resolved = new LinkedHashMap<Integer, Resolution>();
                var created = EnumSet.noneOf(EntityKind.class);
                texts.forEach(t -> resolved.put(t.seq(), resolve(t.meta(), null, created)));
                repo.relink(resolved);
                return texts;
            });
            if (batch.isEmpty()) break;
            albums += batch.size();
            after = batch.getLast().seq();
        }
        recount();
        repo.version(VERSION);
        log.info("Catalog backfill: the text of {} albums resolved", albums);
    }

    /** Before {@code scanOnStart}: the backfill holds the gate (as a settings save), else it waits for it on a thread of its own. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void backfillOnStart() {
        if (repo.version() >= VERSION || backfilled()) return;
        log.info("Catalog backfill waits for the gate: {}", gate.blocker());
        Thread.ofPlatform().name("catalog-backfill").daemon(true).start(() -> {
            try {
                while (!stopped && !backfilled()) TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @EventListener(ContextClosedEvent.class)
    void stop() { stopped = true; }

    /** Whether the backfill ran (or failed: logged, it runs again on the next start); false while the gate is held. */
    private boolean backfilled() {
        var hold = gate.tryEnter(Gate.Activity.SETTINGS);
        if (hold.isEmpty()) return false;
        try (var h = hold.get()) {
            backfill();
        } catch (RuntimeException e) {
            log.warn("Catalog backfill failed: {}", e.toString());
        }
        return true;
    }

    /** A name as an album's text writes it, normalized, and the entity known by it (null = none yet). */
    private record Named(EntityKind kind, String written, String norm, Entity entity) {
        boolean is(int id) { return entity != null && entity.id() == id; }
    }

    /**
     * The links an album's effective text resolves to, with its alias text (§2.5). A name no entity has yet is created
     * when {@code missing} is null (the write lock is held; its kind goes to {@code created}), else it is collected in
     * {@code missing} and the result is null. Two names of one entity are one link; its alias is the first name that is
     * not the entity's primary one (never for tags, whose names are normalized).
     */
    private Resolution resolve(AlbumMeta m, List<Named> missing, Set<EntityKind> created) {
        var links = new LinkedHashMap<Integer, Link>();
        for (var kind : TEXT)
            for (var n : names(kind, texts(m, kind))) {
                var e = n.entity();
                if (e == null && missing != null) {
                    missing.add(n);
                    continue;
                }
                if (e == null) e = create(n, created);
                var alias = kind == EntityKind.TAG || n.norm().equals(norm(kind, e.name())) ? null : cut(n.written());
                links.merge(e.id(), new Link(e.id(), kind, e.name(), alias), (a, b) -> a.alias() != null ? a : b);
            }
        if (missing != null && !missing.isEmpty()) return null;
        return new Resolution(List.copyOf(links.values()), links.isEmpty() ? "" : repo.aliasText(links.keySet()));
    }

    /** Insert-or-get under the write lock: an auto entity (tags: confirmed, named by their norm) with its primary alias. */
    private Entity create(Named n, Set<EntityKind> created) {
        var kind = n.kind();
        return repo.resolve(kind, n.norm()).map(this::find).orElseGet(() -> {
            var e = new Entity(0, kind, kind == EntityKind.TAG ? n.norm() : cut(n.written()), 0, kind != EntityKind.TAG, 0, null, null);
            created.add(kind);
            return new Entity(repo.insert(e, n.norm()), kind, e.name(), 0, e.auto(), 0, null, null);
        });
    }

    /**
     * The names texts of a kind list (§2.5): a whole text the kind knows stays one name ("Art &amp; Soul", "Smith, John"),
     * any other is split ({@link Names}); tags are names as they are. One lookup per kind.
     */
    private List<Named> names(EntityKind kind, List<String> texts) {
        if (texts.isEmpty()) return List.of();
        var norms = new LinkedHashSet<String>();
        for (var t : texts) {
            norms.add(norm(kind, t));
            if (kind != EntityKind.TAG) Names.parse(t).forEach(n -> norms.add(norm(kind, n)));
        }
        norms.remove("");
        var known = norms.isEmpty() ? Map.<String, Entity>of() : repo.named(kind, norms);
        var names = new ArrayList<Named>();
        for (var t : texts)
            for (var name : kind == EntityKind.TAG || known.containsKey(norm(kind, t)) ? List.of(t.strip()) : Names.parse(t)) {
                var n = norm(kind, name);
                if (!n.isEmpty()) names.add(new Named(kind, name, n, known.get(n)));
            }
        return names;
    }

    private static List<String> texts(AlbumMeta m, EntityKind kind) {
        return switch (kind) {
            case MODEL -> Stream.ofNullable(m.model()).toList();
            case STUDIO -> Stream.ofNullable(m.studio()).toList();
            case PHOTOGRAPHER -> Stream.ofNullable(m.photographer()).toList();
            case GENRE -> Objects.requireNonNullElse(m.genres(), List.of());
            case TAG -> Objects.requireNonNullElse(m.tags(), List.of());
            case LIST -> List.of();
        };
    }

    private static AlbumMeta meta(Album a) {
        return new AlbumMeta(null, a.model(), a.studio(), a.photographer(), null, null, a.tags(), a.genres());
    }

    /**
     * The edited text of a kind's field: a name list ({@code ""} when empty; every name quoted when the list as written
     * is itself a name of the kind, which would resolve whole); genres as a list.
     */
    private AlbumPatch text(EntityKind kind, List<String> names) {
        var s = Names.join(names);
        if (kind != EntityKind.GENRE && names.size() > 1 && repo.resolve(kind, norm(kind, s)).isPresent()) s = Names.quoted(names);
        return switch (kind) {
            case MODEL -> new AlbumPatch(null, s, null, null, null, null, null, null, null, null, null, null, null);
            case STUDIO -> new AlbumPatch(null, null, s, null, null, null, null, null, null, null, null, null, null);
            case PHOTOGRAPHER -> new AlbumPatch(null, null, null, s, null, null, null, null, null, null, null, null, null);
            case GENRE -> new AlbumPatch(null, null, null, null, null, null, null, null, null, List.copyOf(names), null, null, null);
            default -> throw new IllegalArgumentException("No text field for " + kind);
        };
    }

    private static AlbumPatch tags(List<String> add, List<String> remove) {
        return new AlbumPatch(null, null, null, null, null, null, null, null, null, null, null, add, remove);
    }

    /**
     * A link to the entity's own kind is its parent: one at most, and a new one must not lie below the entity (a walk up
     * from it, bounded by the ids seen, so a cycle a merge left cannot loop). A new entity has nothing below it.
     */
    private void refuseParent(Entity old, EntityKind kind, String name, List<EntityLink> links) {
        var parents = links.stream().map(l -> find(l.to())).filter(p -> p.kind() == kind).toList();
        if (parents.size() > 1) throw new IllegalArgumentException("One parent at most");
        if (old == null || parents.isEmpty() || parents.getFirst().id() == parent(old)) return;
        var seen = new HashSet<Integer>();
        for (int p = parents.getFirst().id(); p != 0 && seen.add(p); p = parent(find(p)))
            if (p == old.id()) throw new IllegalArgumentException(parents.getFirst().name() + " is inside " + name);
    }

    /** The entity's parent id (its curated link to its own kind), 0 = none. */
    private int parent(Entity e) {
        return repo.entityLinks(e.id()).stream().map(l -> repo.find(l.to())).flatMap(Optional::stream)
                .filter(p -> p.kind() == e.kind()).mapToInt(Entity::id).findFirst().orElse(0);
    }

    /** A name owned by another entity of the kind ({@code self}: the entity it may belong to, 0 = none) is refused. */
    private void refuseTaken(EntityKind kind, String norm, String written, int self) {
        repo.resolve(kind, norm).filter(o -> o != self).ifPresent(o -> {
            throw new IllegalArgumentException("\"" + written + "\" is " + repo.find(o).map(Entity::name).orElse("another entity") + "; merge instead?");
        });
    }

    private Entity find(int id) { return repo.find(id).orElseThrow(() -> new NoSuchElementException("No entity " + id)); }

    private Card card(int id) {
        var e = find(id);
        var links = repo.entityLinks(id).stream().flatMap(l -> repo.find(l.to()).map(to -> new Card.Relation(to, l.alias(), l.roles())).stream()).toList();
        var primary = norm(e.kind(), e.name());
        return new Card(e, repo.aliases(id).stream().filter(a -> !a.equals(primary)).toList(), links, derived(e), repo.children(id));
    }

    /**
     * What the albums say about an entity (read only, visible albums), the sections its kind shows (§7.3); related
     * entities carry the albums they share with it as {@code albums}, most first.
     */
    private Card.Derived derived(Entity e) {
        int albums = repo.albumCount(e.id());
        return switch (e.kind()) {
            case MODEL -> new Card.Derived(albums, null, repo.credits(e.id()), related(e, EntityKind.PHOTOGRAPHER),
                    related(e, EntityKind.GENRE), related(e, EntityKind.TAG).stream().limit(TAGS).map(Entity::name).toList());
            case STUDIO -> new Card.Derived(albums, related(e, EntityKind.MODEL), null, related(e, EntityKind.PHOTOGRAPHER),
                    related(e, EntityKind.GENRE), null);
            case PHOTOGRAPHER -> new Card.Derived(albums, related(e, EntityKind.MODEL), repo.credits(e.id()), null,
                    related(e, EntityKind.GENRE), null);
            case GENRE, TAG -> new Card.Derived(albums, related(e, EntityKind.MODEL), repo.credits(e.id()), null, null, null);
            case LIST -> new Card.Derived(albums, null, null, null, null, null);
        };
    }

    private List<Entity> related(Entity e, EntityKind kind) { return repo.list(kind, null, e.id(), false, true, 0, RELATED); }

    /** Profile JSON of a merge: the surviving entity's fields win, the merged one's fill its missing, null or blank ones. */
    static String fill(String into, String from) {
        if (from == null || from.isBlank()) return into;
        if (into == null || into.isBlank()) return from;
        try {
            if (!(JSON.readTree(into) instanceof ObjectNode b) || !(JSON.readTree(from) instanceof ObjectNode a)) return into;
            a.properties().forEach(f -> {
                var v = b.get(f.getKey());
                if (v == null || v.isNull() || v.isString() && v.stringValue().isBlank()) b.set(f.getKey(), f.getValue());
            });
            return JSON.writeValueAsString(b);
        } catch (JacksonException e) {
            return into;
        }
    }

    private static String cut(String s) { return s.length() > NAME ? s.substring(0, NAME) : s; }

    private static String prefix(EntityKind kind, String text) { return text == null || text.isBlank() ? null : norm(kind, text); }

    private <T> T read(Supplier<T> action) { return locked(lock.readLock(), action); }

    private <T> T write(Supplier<T> action) { return locked(lock.writeLock(), action); }

    private static <T> T locked(Lock lock, Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
