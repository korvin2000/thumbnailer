package thumbnailer.album;

import com.dynatrace.hash4j.hashing.Hashing;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
import thumbnailer.source.Exclusions;

/**
 * The single owner of album mutation. Mutations of one album are serialized (striped locks) and re-read the stored
 * state, so a scan never overwrites a concurrent user edit; events go out after the lock is released. Covers are a
 * regenerable cache under the album id, each value tagged with the album state it shows (cover entry, files,
 * rendering): a value of any other state is a miss, so a lost write, a crash, a late render or a rolled-back save can
 * never serve a stale cover. Entry thumbnails are never stored. Renders are CPU-bound, so covers (indexing and cover
 * misses alike) and entry thumbnails each pass a {@link Limiter} sized by the settings.
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
    private final Limiter coverSlots = new Limiter(), thumbSlots = new Limiter();
    private final Object[] locks = new Object[64];

    public AlbumService(AlbumRepository repo, BlobStore blobs, Containers containers, Thumbnailer thumbnailer,
                        CoverSelector covers, SettingsStore settings, ApplicationEventPublisher events) {
        this.repo = repo;
        this.blobs = blobs;
        this.containers = containers;
        this.thumbnailer = thumbnailer;
        this.covers = covers;
        this.settings = settings;
        this.events = events;
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
        var fresh = render(album, album.cover(), coverSlots, Settings::covers);
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

    /** A cover candidate rendered under the covers spec and limit, which indexing and cover misses share. */
    public Thumb renderCover(AlbumContainer container, String name) throws IOException {
        return render(container, name, coverSlots, settings.get().covers());
    }

    /** Rendered per request from the album itself; browsers cache it by revision and render epoch. */
    public Optional<Thumb> thumb(Album album, Entry entry) { return render(album, entry.name(), thumbSlots, Settings::thumbs); }

    private Album changed(Album album) {
        events.publishEvent(new AlbumEvent.Changed(album));
        return album;
    }

    private Optional<Thumb> render(Album album, String entry, Limiter slots, Function<Settings, Render> spec) {
        var s = settings.get();
        try (var container = containers.open(album.kind(), s.locate(album.path()), Exclusions.NONE)) {
            return Optional.of(render(container, entry, slots, spec.apply(s)));
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot render {} of {}: {}", entry, album.path(), e.toString());
            return Optional.empty();
        }
    }

    private Thumb render(AlbumContainer container, String entry, Limiter slots, Render spec) throws IOException {
        slots.enter(spec.permits());
        try {
            return thumbnailer.render(container, entry, spec);
        } finally {
            slots.release();
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
