package thumbnailer.album;

import com.dynatrace.hash4j.hashing.Hashing;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import thumbnailer.album.AlbumRepository.Save;
import thumbnailer.album.AlbumRepository.Stamp;
import thumbnailer.album.AlbumRepository.Tombstone;
import thumbnailer.catalog.Catalog;
import thumbnailer.cover.CoverSelector;
import thumbnailer.image.Limiter;
import thumbnailer.image.Render;
import thumbnailer.image.Thumbnailer;
import thumbnailer.image.Thumbnailer.Thumb;
import thumbnailer.index.Gate;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Containers;
import thumbnailer.source.Disks;
import thumbnailer.source.Exclusions;
import thumbnailer.source.Kind;

/**
 * The single owner of album mutation. Mutations of one album are serialized (striped locks) and re-read the stored
 * state, so a scan never overwrites a concurrent user edit; events go out after the lock is released. Covers are a
 * regenerable cache under the album id, each value tagged with the album state it shows (cover entry, files,
 * rendering): a value of any other state is a miss, so a lost write, a crash, a late render or a rolled-back save can
 * never serve a stale cover. Entry thumbnails are never stored. Renders are CPU-bound, so covers (indexing and cover
 * misses alike) and entry thumbnails each pass a {@link Limiter} sized by the settings. Reading and decoding are a
 * producer-consumer pipeline: files are read one by one in turns on their disk ({@link Disks.Lane}, FIFO; at once on
 * an SSD) into a queue of {@code 2 × threads} read files, from which up to {@code threads} decode, resize and encode in
 * parallel while the disk reads the next ones. An archive that reads only front to back serves its entry thumbnails
 * from one {@link Gallery} pass. Every save resolves the album's text into catalog links, stored with the album.
 * An operation working on an album's files makes it busy ({@link #quiesce}): nothing renders from it until released.
 */
@Service
public class AlbumService {

    private static final Logger log = LoggerFactory.getLogger(AlbumService.class);
    /** The message of the {@code IllegalStateException} a read of a busy album throws (HTTP 503). */
    public static final String BUSY = "busy";
    private static final long QUIET = TimeUnit.SECONDS.toNanos(5); // the longest quiesce waits for reads in flight

    private final AlbumRepository repo;
    private final BlobStore blobs;
    private final Containers containers;
    private final Thumbnailer thumbnailer;
    private final CoverSelector covers;
    private final SettingsStore settings;
    private final ApplicationEventPublisher events;
    private final Disks disks;
    private final Catalog catalog;
    private final Gate gate;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> readers = new ConcurrentHashMap<>(); // reads in flight per album; notified when busy
    private final Limiter coverSlots = new Limiter(), thumbSlots = new Limiter(), coverQueue = new Limiter(), thumbQueue = new Limiter();
    private final Object[] locks = new Object[64];
    /** The last albums whose entry thumbnails were asked for; gallery null = entries read one by one. */
    private boolean closing; // no gallery starts once the context closes (guarded by galleries)
    private final Map<String, Pass> galleries = new LinkedHashMap<>(8, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pass> eldest) {
            if (size() <= 4) return false;
            eldest.getValue().close();
            return true;
        }
    };

    private record Pass(String key, Gallery gallery) {
        boolean live(String k) { return key.equals(k) && (gallery == null || !gallery.closed()); }

        void close() { if (gallery != null) gallery.close(); }
    }

    public AlbumService(AlbumRepository repo, BlobStore blobs, Containers containers, Thumbnailer thumbnailer,
                        CoverSelector covers, SettingsStore settings, ApplicationEventPublisher events, Disks disks,
                        Catalog catalog, Gate gate) {
        this.repo = repo;
        this.blobs = blobs;
        this.containers = containers;
        this.thumbnailer = thumbnailer;
        this.covers = covers;
        this.settings = settings;
        this.events = events;
        this.disks = disks;
        this.catalog = catalog;
        this.gate = gate;
        Arrays.setAll(locks, i -> new Object());
    }

    /**
     * Stores an indexed album. {@code base} is the stored album the scan matched (null = new); it is kept if it still
     * sits at the indexed path or, for a move, at its old path that no longer exists. Otherwise the album takes over
     * the tombstone of a removed album with the same content, if any (so a copy of a deleted album inherits its
     * edits), else it is new. Id, rating, edits, hidden, seq and a pinned cover come from that prior state, revision + 1.
     * The save consumes the tombstone, so a failed save keeps it; one whose id is live again (a copy took it) is ignored.
     * The cover is tagged with the epoch its bytes were rendered with, whatever the settings say by now.
     */
    public Album commit(Album base, Album indexed, List<Entry> entries, Thumb cover) {
        Album album;
        synchronized (lock(base != null ? base.id() : indexed.internal().contentId())) {
            var stored = Optional.ofNullable(base).flatMap(b -> repo.find(b.id())).filter(a -> a.path().equals(indexed.path())
                    || a.path().equals(base.path()) && Files.notExists(settings.get().locate(a.path())));
            var prior = stored.map(Tombstone::of).or(() -> repo.recall(indexed.internal().contentId())
                    .filter(t -> repo.find(t.id()).isEmpty()));
            album = store(prior.orElseGet(() -> new Tombstone(UUID.randomUUID().toString(), 0, 0, null, false, AlbumMeta.EMPTY, 0, false)),
                    stored.filter(a -> !a.hidden()).isPresent(), indexed, entries, cover, Map.of());
        }
        return changed(album);
    }

    /**
     * Stores an album an operation re-indexed: the operation asserts its identity, so the album stored under
     * {@code prior}'s id now (re-read under the lock: an edit made while indexing survives, its revision is not reused)
     * is adopted whatever the paths say, {@code prior} only if that row is gone. Entries it renamed ({@code nameMap}:
     * old → new) keep their pinned cover and photo marks.
     */
    public Album commit(Tombstone prior, Album indexed, List<Entry> entries, Thumb cover, Map<String, String> nameMap) {
        Album album;
        synchronized (lock(prior.id())) {
            var cur = repo.find(prior.id());
            album = store(cur.map(Tombstone::of).orElse(prior), cur.filter(a -> !a.hidden()).isPresent(), indexed, entries,
                    cover, nameMap);
        }
        return changed(album);
    }

    /** The commit both paths share; {@code visibleBefore}: the album was live and not hidden (never a revived one). */
    private Album store(Tombstone prior, boolean visibleBefore, Album indexed, List<Entry> entries, Thumb cover,
                        Map<String, String> nameMap) {
        var pin = Optional.of(prior).filter(Tombstone::coverPinned)
                .flatMap(p -> entry(entries, p.cover() == null ? null : nameMap.getOrDefault(p.cover(), p.cover())));
        // The indexer chose with the pin it saw when it started; choose again if that is no longer the pin.
        var chosen = pin.isPresent() || indexed.coverPinned()
                ? pin.or(() -> covers.candidates(entries, null).findFirst()) : Optional.<Entry>empty();
        boolean rendered = chosen.map(e -> e.name().equals(indexed.cover())).orElse(true);
        var album = save(indexed.adopt(prior).withCover(rendered ? indexed.cover() : chosen.get().name(), pin.isPresent(),
                rendered ? indexed.coverPortrait() : portrait(chosen.get())), entries, visibleBefore, nameMap);
        if (rendered && cover != null) blobs.put(album.id(), value(album, cover));
        return album;
    }

    /**
     * Resolves the album's text into catalog links and saves both under the catalog read lock (counts suspended while a
     * scan holds the gate: it recounts at its end, after this lock); returns the album as stored (seq, links, copies).
     * Photo marks follow {@code nameMap} (old → new entry name) in the same transaction.
     */
    private Album save(Album album, List<Entry> entries, boolean visibleBefore, Map<String, String> nameMap) {
        var in = album.internal();
        catalog.resolving(in.edited().over(in.extracted()).effective(), in.seq(), r -> repo.save(album, entries, r,
                new Save(visibleBefore, !album.hidden(), gate.current() == Gate.Activity.SCAN, nameMap)));
        return repo.find(album.id()).orElseThrow();
    }

    /** Edits keep the files, so an unchanged cover keeps its tag; a new one is rendered on the next request. */
    public Album patch(String id, AlbumPatch patch) {
        if (patch.rating() != null && (patch.rating() < 0 || patch.rating() > 10))
            throw new IllegalArgumentException("Rating must be 0..10");
        Album album;
        synchronized (lock(id)) {
            var stored = repo.find(id).orElseThrow(() -> new NoSuchElementException("No album " + id));
            album = stored.withEdits(patch.edit(stored.internal().edited(), stored.tags()))
                    .with(id, stored.revision() + 1, patch.rating() != null ? patch.rating() : stored.rating())
                    .withHidden(patch.hidden() != null ? patch.hidden() : stored.hidden());
            if (patch.cover() != null) {
                var entries = repo.entries(id);
                var cover = (patch.cover().isEmpty() ? covers.candidates(entries, null) : entry(entries, patch.cover()).stream())
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("No entry " + patch.cover()));
                album = album.withCover(cover.name(), !patch.cover().isEmpty(), portrait(cover));
            }
            album = save(album, null, !stored.hidden(), Map.of());
        }
        return changed(album);
    }

    /** A moved album keeps everything but its path and root; the move is a save, so it bumps the revision. */
    public Album relocate(String id, String path, String root) {
        Album album;
        synchronized (lock(id)) {
            repo.relocate(id, path, root);
            album = repo.find(id).orElseThrow(() -> new NoSuchElementException("No album " + id));
        }
        return changed(album);
    }

    /**
     * A renamed or moved folder: the albums at or below it follow, each under its own lock (a concurrent edit re-saves
     * its whole row); returns how many. Paths move only under the gate, so reading them first is safe.
     */
    public int rebase(String oldDir, String newDir, String newRoot) {
        var moved = repo.byRoot(oldDir);
        moved.forEach(a -> relocate(a.id(), newDir + a.path().substring(oldDir.length()), a.root().equals(oldDir) ? newRoot : a.root()));
        return moved.size();
    }

    /** Removes every album at or below {@code dir} (each leaves its tombstone). */
    public void removeBelow(String dir) { repo.stamps(dir).values().forEach(s -> remove(s.id())); }

    /**
     * Marks albums busy while an operation works on their files: their gallery passes close and nothing reads them
     * ({@code IllegalStateException("busy")}, HTTP 503) until {@link #release}d. Then waits until the reads that began
     * before the mark (requests, gallery passes) have left and closed their files; after 5 s it logs and proceeds.
     */
    public void quiesce(Collection<String> ids) {
        ids.forEach(id -> readers.compute(id, (k, n) -> { // under the key's lock, where a read counts itself
            busy.add(k);
            return n;
        }));
        synchronized (galleries) {
            ids.forEach(id -> {
                var pass = galleries.remove(id);
                if (pass != null) pass.close();
            });
        }
        long end = System.nanoTime() + QUIET;
        synchronized (readers) {
            while (ids.stream().anyMatch(readers::containsKey)) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    log.warn("Albums {} are still being read after 5 s; the operation goes on", ids.stream().filter(readers::containsKey).toList());
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(readers, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** {@link #quiesce} for every album at or below {@code dir}; returns their ids, for the release. */
    public List<String> quiesceBelow(String dir) {
        var ids = repo.stamps(dir).values().stream().map(Stamp::id).toList();
        quiesce(ids);
        return ids;
    }

    public void release(Collection<String> ids) { busy.removeAll(ids); }

    public boolean busy(String id) { return busy.contains(id); }

    /** A read of an album's files, counted until closed (closing it again is harmless); {@link #quiesce} waits for it. */
    public interface Reading extends AutoCloseable {
        @Override
        void close();
    }

    /** Starts a read of the album's files; refused ({@code IllegalStateException("busy")}) while the album is busy. */
    public Reading read(String id) {
        readers.compute(id, (k, n) -> { // checked and counted under the lock quiesce marks the key in: refused or waited for
            idle(k);
            return n == null ? 1 : n + 1;
        });
        var open = new AtomicBoolean(true);
        return () -> { if (open.getAndSet(false)) leave(id); };
    }

    private void leave(String id) {
        if (readers.computeIfPresent(id, (k, n) -> n > 1 ? n - 1 : null) == null && busy.contains(id))
            synchronized (readers) { readers.notifyAll(); }
    }

    public void remove(String id) {
        synchronized (lock(id)) {
            catalog.reading(() -> repo.delete(id)); // its count deltas must not interleave with a list change (write lock)
            blobs.remove(id);
        }
        events.publishEvent(new AlbumEvent.Removed(id));
    }

    /**
     * Makes the library follow the configured roots: the albums of a stored root that is no longer configured move to
     * the configured root they lie in (a widened or narrowed root keeps them), the rest are removed.
     */
    public void retainRoots(Collection<String> roots) {
        for (var stale : repo.rootStats().keySet()) {
            if (roots.contains(stale)) continue;
            roots.forEach(root -> repo.reroot(stale, root));
            repo.stamps(stale).forEach((path, s) -> {
                if (roots.stream().noneMatch(root -> Containers.within(path, root))) remove(s.id());
            });
        }
    }

    /** The stored cover of this album state, else a fresh render, stored for the next request. */
    public Optional<Thumb> cover(Album album) {
        try (var _ = read(album.id())) {
            var hit = storedCover(album);
            if (hit.isPresent() || album.cover() == null) return hit;
            var fresh = render(album, album.cover(), coverQueue, coverSlots, Settings::covers);
            fresh.ifPresent(t -> blobs.put(album.id(), value(album, t)));
            return fresh;
        }
    }

    /**
     * The stored cover if it was rendered for exactly this album state under the current covers spec, and is whole
     * (the store keeps no page checksums). Values are {@code [tag 8][len 4][bytes]}; the legacy spec keeps the older
     * {@code [tag 8][webp]}, whose RIFF size plays the length.
     */
    public Optional<Thumb> storedCover(Album album) {
        var spec = settings.get().covers();
        long epoch = spec.epoch(), tag = tag(album, epoch);
        boolean legacy = epoch == Render.LEGACY_EPOCH;
        int len = legacy ? 12 : 8, body = legacy ? 8 : 12; // the length field counts every byte after it
        return blobs.get(album.id()).map(v -> ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN))
                .filter(v -> v.capacity() > len + 4 && v.getLong(0) == tag && v.getInt(len) == v.capacity() - len - 4)
                .map(v -> new Thumb(Arrays.copyOfRange(v.array(), body, v.capacity()), spec.format(), epoch,
                        album.coverPortrait()));
    }

    /**
     * A cover candidate rendered under the covers spec and limit, which indexing and cover misses share; reads during
     * {@code turn}, the caller's turn on the disk, and ends it.
     */
    public Thumb renderCover(AlbumContainer container, String name, Disks.Hold turn) throws IOException {
        return render(container, name, coverQueue, coverSlots, settings.get().covers(), turn);
    }

    /**
     * Rendered per request from the album itself, or by the album's {@link Gallery} pass; browsers cache it by
     * revision and render epoch.
     */
    public Optional<Thumb> thumb(Album album, Entry entry) {
        try (var _ = read(album.id())) {
            try {
                var gallery = gallery(album);
                var thumb = gallery == null ? null : gallery.take(entry.name());
                if (thumb != null) return Optional.of(thumb);
            } catch (IOException | RuntimeException e) {
                log.warn("Cannot render {} of {}: {}", entry.name(), album.path(), e.toString());
                return Optional.empty();
            }
            // taken before, random access, or a pass closed meanwhile (render refuses a busy album)
            return render(album, entry.name(), thumbQueue, thumbSlots, Settings::thumbs);
        }
    }

    /**
     * The one-pass gallery of an archive that reads only front to back, started by its first thumbnail; else null
     * (also for a busy album: no new pass). The archive is looked at outside the lock; the first of racing requests
     * publishes its gallery, which counts as a read of the album until its pass ends.
     */
    private Gallery gallery(Album album) throws IOException {
        if (album.kind() != Kind.ARCHIVE) return null;
        var s = settings.get();
        var key = album.revision() + "/" + s.thumbs().epoch() + "/" + s.thumbs().permits() + "/" + s.cryKey();
        synchronized (galleries) {
            var pass = galleries.get(album.id());
            if (closing || pass != null && pass.live(key)) return closing ? null : pass.gallery();
        }
        var path = s.locate(album.path());
        boolean sequential;
        try (var c = containers.open(album.kind(), path, Exclusions.NONE)) {
            sequential = c.sequential();
        }
        var fresh = !sequential ? null : new Gallery(() -> containers.open(album.kind(), path, Exclusions.NONE),
                repo.entries(album.id()).stream().map(Entry::name).toList(), lane(s, album.path()), thumbQueue, thumbSlots,
                s.thumbs(), thumbnailer, () -> leave(album.id()));
        synchronized (galleries) { // quiesce marks busy, then removes passes under this lock: none is published after
            var pass = galleries.get(album.id());
            if (closing || busy.contains(album.id())) return null;
            if (pass != null && pass.live(key)) return pass.gallery();
            if (pass != null) pass.close();
            galleries.put(album.id(), new Pass(key, fresh));
            if (fresh != null) {
                readers.merge(album.id(), 1, Integer::sum);
                fresh.start();
            }
            return fresh;
        }
    }

    @EventListener(ContextClosedEvent.class)
    void closeGalleries() {
        synchronized (galleries) {
            closing = true;
            galleries.values().forEach(Pass::close);
            galleries.clear();
        }
    }

    private Album changed(Album album) {
        events.publishEvent(new AlbumEvent.Changed(album));
        return album;
    }

    private Optional<Thumb> render(Album album, String entry, Limiter queue, Limiter slots, Function<Settings, Render> spec) {
        idle(album.id());
        var s = settings.get();
        try (var container = containers.open(album.kind(), s.locate(album.path()), Exclusions.NONE)) {
            return Optional.of(render(container, entry, queue, slots, spec.apply(s), lane(s, album.path()).enter()));
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot render {} of {}: {}", entry, album.path(), e.toString());
            return Optional.empty();
        }
    }

    /** The UI's lane on the disk of the root holding {@code path}. */
    public Disks.Lane lane(Settings s, String path) {
        var disk = s.root(path).map(r -> disks.disk(s.locate(r.path()), r.storage()))
                .orElseGet(() -> disks.disk(s.locate(path).getRoot(), null));
        return disks.lane(disk, Disks.Use.VIEW);
    }

    /**
     * Reads during {@code turn} and ends it, then decodes. Taken in one order only, turn → queue place → decode slot, and
     * no holder waits for an earlier one: deadlock-free. A full queue holds the reader back, so memory stays bounded.
     */
    private Thumb render(AlbumContainer container, String entry, Limiter queue, Limiter slots, Render spec,
                         Disks.Hold turn) throws IOException {
        try (turn; var arena = Arena.ofConfined()) {
            queue.enter(2 * spec.permits());
            try {
                var data = container.read(entry, arena);
                turn.close(); // the disk serves the next reader while this file waits for and goes through a decoder
                slots.enter(spec.permits());
                try {
                    return thumbnailer.render(data, entry, spec);
                } finally {
                    slots.release();
                }
            } finally {
                queue.release();
            }
        }
    }

    /**
     * Seeded by the render epoch of the bytes; the signature covers every file's size and mtime, so the tag changes
     * whenever the cover could look different.
     */
    private static long tag(Album album, long epoch) {
        return Hashing.wyhashFinal4(epoch).hashStream().putString(String.valueOf(album.cover()))
                .putString(album.internal().signature()).getAsLong();
    }

    private static byte[] value(Album album, Thumb cover) {
        boolean legacy = cover.epoch() == Render.LEGACY_EPOCH;
        var v = ByteBuffer.allocate(cover.bytes().length + (legacy ? 8 : 12)).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(tag(album, cover.epoch()));
        if (!legacy) v.putInt(cover.bytes().length);
        return v.put(cover.bytes()).array();
    }

    /** The one busy check every read path makes (requests on entry, renders, a new gallery pass). */
    private void idle(String id) {
        if (busy.contains(id)) throw new IllegalStateException(BUSY);
    }

    private static Optional<Entry> entry(List<Entry> entries, String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst();
    }

    private static boolean portrait(Entry e) { return e.height() > e.width(); }

    private Object lock(String id) { return locks[Math.floorMod(id.hashCode(), locks.length)]; }
}
