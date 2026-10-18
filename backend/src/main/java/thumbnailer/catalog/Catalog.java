package thumbnailer.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import thumbnailer.album.AlbumMeta;

/**
 * The catalog: entities, their names and links. One read-write lock: album saves resolve under the read lock (and
 * write only to create a missing entity); merges, renames, aliases, deletes and list memberships take the write lock and never an album's
 * lock (the order is album stripe, then catalog). Changes are announced as {@link CatalogEvent}s after the lock.
 */
@Service
public class Catalog {

    private static final Logger log = LoggerFactory.getLogger(Catalog.class);
    private static final int NAME = 256, LIMIT = 500;

    private final CatalogRepository repo;
    private final ApplicationEventPublisher events;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public Catalog(CatalogRepository repo, ApplicationEventPublisher events) {
        this.repo = repo;
        this.events = events;
    }

    /** The lookup key of a name: stripped, lower case, whitespace collapsed, at most 256 chars; tags as {@link AlbumMeta#tag}. */
    public static String norm(EntityKind kind, String name) {
        var n = kind == EntityKind.TAG ? AlbumMeta.tag(name) : name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return n.length() > NAME ? n.substring(0, NAME) : n;
    }

    /** A page of a kind, {@code text} a prefix of any of their names; at most 500. */
    public List<Entity> entities(EntityKind kind, String text, boolean byCount, int offset, int limit) {
        return read(() -> repo.list(Objects.requireNonNull(kind, "kind is required"), prefix(kind, text), byCount,
                Math.max(0, offset), limit <= 0 ? 100 : Math.min(limit, LIMIT)));
    }

    public int count(EntityKind kind, String text) { return read(() -> repo.count(kind, prefix(kind, text))); }

    public Card get(int id) { return read(() -> card(id)); }

    /**
     * Creates or updates an entity with its whole alias set (a renamed entity keeps its old name as an alias, or texts
     * saying it would create a new one) and curated links; a name owned by another entity of the kind is refused. All
     * checks come first, then one transaction writes it all: a refused or failed save changes nothing. Every save is
     * announced (views cache entity lists, ratings included).
     */
    public Card save(EntitySave s) {
        Objects.requireNonNull(s.kind(), "kind is required");
        if (s.name() == null || s.name().isBlank()) throw new IllegalArgumentException("A name is required");
        if (s.rating() < 0 || s.rating() > 10) throw new IllegalArgumentException("Rating must be 0..10");
        var name = s.kind() == EntityKind.TAG ? norm(s.kind(), s.name()) : s.name().strip();
        int id = write(() -> {
            var old = s.id() == null ? null : repo.find(s.id()).orElseThrow(() -> new NoSuchElementException("No entity " + s.id()));
            if (old != null && old.kind() != s.kind()) throw new IllegalArgumentException("The kind of an entity cannot change");
            var names = new LinkedHashMap<String, String>(); // norm → as written
            names.put(norm(s.kind(), name), name);
            if (old != null) names.putIfAbsent(norm(s.kind(), old.name()), old.name());
            Stream.ofNullable(s.aliases()).flatMap(List::stream).filter(a -> a != null && !a.isBlank())
                    .forEach(a -> names.putIfAbsent(norm(s.kind(), a), a.strip()));
            if (s.aliases() == null && old != null) repo.aliases(old.id()).forEach(n -> names.putIfAbsent(n, n));
            names.forEach((n, written) -> repo.resolve(s.kind(), n).filter(o -> old == null || o != old.id()).ifPresent(o -> {
                throw new IllegalArgumentException("\"" + written + "\" is " + repo.find(o).map(Entity::name).orElse("another entity") + "; merge instead?");
            }));
            for (var l : s.links() == null ? List.<EntityLink>of() : s.links())
                if (repo.find(l.to()).isEmpty() || old != null && l.to() == old.id()) throw new IllegalArgumentException("Cannot link to " + l.to());
            return repo.store(old == null ? new Entity(0, s.kind(), name, s.rating(), false, 0, s.profile(), null)
                    : new Entity(old.id(), s.kind(), name, s.rating(), old.auto(), old.albums(), s.profile(), null),
                    List.copyOf(names.keySet()), s.links());
        });
        events.publishEvent(new CatalogEvent(List.of(s.kind())));
        return get(id);
    }

    /** Removes the entities with their names and links (B1 first rewrites the texts of their albums). */
    public void delete(List<Integer> ids) {
        var kinds = write(() -> ids.stream().distinct().map(id -> {
            var e = repo.find(id).orElseThrow(() -> new NoSuchElementException("No entity " + id));
            repo.delete(id);
            return e.kind();
        }).distinct().toList());
        events.publishEvent(new CatalogEvent(kinds));
    }

    /** Auto entities become confirmed ones. */
    public void confirm(List<Integer> ids) {
        var kinds = write(() -> ids.stream().distinct().map(id -> {
            var e = repo.find(id).orElseThrow(() -> new NoSuchElementException("No entity " + id));
            repo.update(new Entity(e.id(), e.kind(), e.name(), e.rating(), false, e.albums(), e.profile(), null));
            return e.kind();
        }).distinct().toList());
        events.publishEvent(new CatalogEvent(kinds));
    }

    /**
     * Adds albums (by seq) to a favorites list or takes them out, serialized with every other membership and count
     * change (the write lock); returns how many links actually changed, announced only when some did.
     */
    public int setList(List<Integer> seqs, int listId, boolean linked) {
        int changed = write(() -> {
            var e = repo.find(listId).orElseThrow(() -> new NoSuchElementException("No entity " + listId));
            if (e.kind() != EntityKind.LIST) throw new IllegalArgumentException(e.name() + " is not a favorites list");
            return (int) seqs.stream().filter(seq -> repo.setListLink(seq, listId, linked)).count();
        });
        if (changed > 0) events.publishEvent(new CatalogEvent(List.of(EntityKind.LIST)));
        return changed;
    }

    /** Rebuilds every stored album count (after a scan, which skips the per-save deltas, and on optimize). */
    public void recount() { write(() -> { repo.recount(); return null; }); }

    public Map<String, PhotoMark> marks(int seq) { return read(() -> repo.marks(seq)); }

    /** Rates a photo or sets its lists (null = unchanged); {@code lists} must be favorites lists. */
    public void mark(int seq, String name, Integer rating, List<Integer> lists) {
        if (rating != null && (rating < 0 || rating > 10)) throw new IllegalArgumentException("Rating must be 0..10");
        read(() -> {
            for (var id : lists == null ? List.<Integer>of() : lists)
                if (repo.find(id).filter(e -> e.kind() == EntityKind.LIST).isEmpty()) throw new IllegalArgumentException("No favorites list " + id);
            repo.mark(seq, name, rating, lists);
            return null;
        });
    }

    /**
     * Resolves an album's effective text into its links (roles 1–5) with the alias text for its search column, and runs
     * {@code save} with them, both under the catalog read lock: no merge, rename, alias or list change lands between the
     * resolution and the album's save, which also computes the count deltas from the links it replaces. This is the
     * only resolution boundary B1 may rely on (an entity it must create is inserted under the write lock, then the text
     * is resolved again under the read lock before {@code save} runs). The caller holds the album's stripe lock (order:
     * stripe → catalog). Phase 0: resolves nothing (catalog_plan.md B1).
     */
    public void resolving(AlbumMeta effective, int seq, Consumer<Resolution> save) {
        read(() -> {
            save.accept(Resolution.NONE);
            return null;
        });
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

    public Entity merge(List<Integer> from, int into) { throw new UnsupportedOperationException("not implemented: mergeEntities"); }

    public Entity creditAs(int model, int studio, String alias, String roles) {
        throw new UnsupportedOperationException("not implemented: creditAs");
    }

    /** Resolves the text of every album once ({@code meta.catalog_version} below 1). */
    public void backfill() { log.info("catalog backfill: not implemented"); }

    private Card card(int id) {
        var e = repo.find(id).orElseThrow(() -> new NoSuchElementException("No entity " + id));
        var links = repo.entityLinks(id).stream().flatMap(l -> repo.find(l.to()).map(to -> new Card.Relation(to, l.alias(), l.roles())).stream()).toList();
        var primary = norm(e.kind(), e.name());
        return new Card(e, repo.aliases(id).stream().filter(a -> !a.equals(primary)).toList(), links, null);
    }

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
