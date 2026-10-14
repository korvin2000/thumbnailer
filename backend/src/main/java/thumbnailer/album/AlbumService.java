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
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import thumbnailer.album.AlbumRepository.Tombstone;
import thumbnailer.cover.CoverSelector;
import thumbnailer.image.Limiter;
import thumbnailer.image.Render;
import thumbnailer.image.Thumbnailer;
import thumbnailer.image.Thumbnailer.Thumb;
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
 * from one {@link Gallery} pass.
 */
@Service
public class AlbumService {

    private static final Logger log = LoggerFactory.getLogger(AlbumService.class);

    private final AlbumRepository repo;
    private final BlobStore blobs;
    private final Containers containers;
    private final Thumbnailer thumbnailer;
    private final CoverSelector covers;
    private final SettingsStore settings;
    private final ApplicationEventPublisher events;
    private final Disks disks;
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
                        CoverSelector covers, SettingsStore settings, ApplicationEventPublisher events, Disks disks) {
        this.repo = repo;
        this.blobs = blobs;
        this.containers = containers;
        this.thumbnailer = thumbnailer;
        this.covers = covers;
        this.settings = settings;
        this.events = events;
        this.disks = disks;
        Arrays.setAll(locks, i -> new Object());
    }

    /**
     * Stores an indexed album. {@code base} is the stored album the scan matched (null = new); it is kept if it still
     * sits at the indexed path or, for a move, at its old path that no longer exists. Otherwise the album takes over
     * the tombstone of a removed album with the same content, if any (so a copy of a deleted album inherits its
     * edits), else it is new. Id, rating, edits and a pinned cover come from that prior state, revision + 1. The save
     * consumes the tombstone, so a failed save keeps it; one whose id is live again (a copy took it) is ignored. The
     * cover is tagged with the epoch its bytes were rendered with, whatever the settings say by now.
     */
    public Album commit(Album base, Album indexed, List<Entry> entries, Thumb cover) {
        Album album;
        synchronized (lock(base != null ? base.id() : indexed.internal().contentId())) {
            var prior = Optional.ofNullable(base).flatMap(b -> repo.find(b.id())).filter(a -> a.path().equals(indexed.path())
                            || a.path().equals(base.path()) && Files.notExists(settings.get().locate(a.path())))
                    .map(Tombstone::of).or(() -> repo.recall(indexed.internal().contentId())
                            .filter(t -> repo.find(t.id()).isEmpty()));
            var pin = prior.filter(Tombstone::coverPinned).flatMap(p -> entry(entries, p.cover()));
            // The indexer chose with the pin it saw when it started; choose again if that is no longer the pin.
            var chosen = pin.isPresent() || indexed.coverPinned()
                    ? pin.or(() -> covers.candidates(entries, null).findFirst()) : Optional.<Entry>empty();
            boolean rendered = chosen.map(e -> e.name().equals(indexed.cover())).orElse(true);
            album = indexed.with(prior.map(Tombstone::id).orElseGet(() -> UUID.randomUUID().toString()),
                            prior.map(Tombstone::revision).orElse(0L) + 1, prior.map(Tombstone::rating).orElse(0))
                    .withEdits(prior.map(Tombstone::edited).orElse(AlbumMeta.EMPTY))
                    .withCover(rendered ? indexed.cover() : chosen.get().name(), pin.isPresent(),
                            rendered ? indexed.coverPortrait() : portrait(chosen.get()));
            repo.save(album, entries);
            if (rendered && cover != null) blobs.put(album.id(), value(album, cover));
        }
        return changed(album);
    }

    /** Edits keep the files, so an unchanged cover keeps its tag; a new one is rendered on the next request. */
    public Album patch(String id, AlbumPatch patch) {
        if (patch.rating() != null && (patch.rating() < 0 || patch.rating() > 10))
            throw new IllegalArgumentException("Rating must be 0..10");
        Album album;
        synchronized (lock(id)) {
            var stored = repo.find(id).orElseThrow(() -> new NoSuchElementException("No album " + id));
            album = stored.withEdits(patch.meta().over(stored.internal().edited()))
                    .with(id, stored.revision() + 1, patch.rating() != null ? patch.rating() : stored.rating());
            if (patch.cover() != null) {
                var entries = repo.entries(id);
                var cover = (patch.cover().isEmpty() ? covers.candidates(entries, null) : entry(entries, patch.cover()).stream())
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("No entry " + patch.cover()));
                album = album.withCover(cover.name(), !patch.cover().isEmpty(), portrait(cover));
            }
            repo.save(album, null);
        }
        return changed(album);
    }

    public void remove(String id) {
        synchronized (lock(id)) {
            repo.delete(id);
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
        var hit = storedCover(album);
        if (hit.isPresent() || album.cover() == null) return hit;
        var fresh = render(album, album.cover(), coverQueue, coverSlots, Settings::covers);
        fresh.ifPresent(t -> blobs.put(album.id(), value(album, t)));
        return fresh;
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
        try {
            var gallery = gallery(album);
            var thumb = gallery == null ? null : gallery.take(entry.name());
            if (thumb != null) return Optional.of(thumb);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot render {} of {}: {}", entry.name(), album.path(), e.toString());
            return Optional.empty();
        }
        return render(album, entry.name(), thumbQueue, thumbSlots, Settings::thumbs); // taken before, or random access
    }

    /**
     * The one-pass gallery of an archive that reads only front to back, started by its first thumbnail; else null.
     * The archive is looked at outside the lock; the first of racing requests publishes its gallery.
     */
    private Gallery gallery(Album album) throws IOException {
        if (album.kind() != Kind.ARCHIVE) return null;
        var s = settings.get();
        var key = album.revision() + "/" + s.thumbs().epoch() + "/" + s.thumbs().permits();
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
                s.thumbs(), thumbnailer);
        synchronized (galleries) {
            var pass = galleries.get(album.id());
            if (closing || pass != null && pass.live(key)) return closing ? null : pass.gallery();
            if (pass != null) pass.close();
            galleries.put(album.id(), new Pass(key, fresh));
            if (fresh != null) fresh.start();
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
        var s = settings.get();
        try (var container = containers.open(album.kind(), s.locate(album.path()), Exclusions.NONE)) {
            return Optional.of(render(container, entry, queue, slots, spec.apply(s), lane(s, album.path()).enter()));
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot render {} of {}: {}", entry, album.path(), e.toString());
            return Optional.empty();
        }
    }

    /** The UI's lane on the disk of the root holding {@code path}. */
    private Disks.Lane lane(Settings s, String path) {
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

    private static Optional<Entry> entry(List<Entry> entries, String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst();
    }

    private static boolean portrait(Entry e) { return e.height() > e.width(); }

    private Object lock(String id) { return locks[Math.floorMod(id.hashCode(), locks.length)]; }
}
