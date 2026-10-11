package thumbnailer.album;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import thumbnailer.cover.CoverSelector;
import thumbnailer.image.Thumbnailer;
import thumbnailer.source.Containers;
import thumbnailer.source.Exclusions;

/**
 * The single owner of album mutation. Mutations of one album are serialized (striped locks) and re-read the stored
 * state, so a scan never overwrites a concurrent user edit; events go out after the lock is released. Thumbnails are
 * a regenerable cache keyed by revision, so a late render of an old revision can never replace a current one.
 */
@Service
public class AlbumService {

    private static final Logger log = LoggerFactory.getLogger(AlbumService.class);

    private final AlbumRepository repo;
    private final BlobStore blobs;
    private final Containers containers;
    private final Thumbnailer thumbnailer;
    private final CoverSelector covers;
    private final ApplicationEventPublisher events;
    private final Object[] locks = new Object[64];

    public AlbumService(AlbumRepository repo, BlobStore blobs, Containers containers, Thumbnailer thumbnailer,
                        CoverSelector covers, ApplicationEventPublisher events) {
        this.repo = repo;
        this.blobs = blobs;
        this.containers = containers;
        this.thumbnailer = thumbnailer;
        this.covers = covers;
        this.events = events;
        Arrays.setAll(locks, i -> new Object());
    }

    /**
     * Stores an indexed album. {@code base} is the stored album the scan matched (null = new); it is kept if it still
     * sits at the indexed path or, for a move, at its old path that no longer exists, otherwise the album is new.
     * Id, rating, edits and a pinned cover come from the latest stored state.
     */
    public Album commit(Album base, Album indexed, List<Entry> entries, byte[] cover) {
        var key = base != null ? base.id() : UUID.randomUUID().toString();
        Album album;
        synchronized (lock(key)) {
            var prev = Optional.ofNullable(base).flatMap(b -> repo.find(b.id())).filter(a -> a.path().equals(indexed.path())
                    || a.path().equals(base.path()) && Files.notExists(Path.of(a.path())));
            var pin = prev.filter(Album::coverPinned).flatMap(a -> entry(entries, a.cover()));
            // The indexer chose with the pin it saw when it started; choose again if that is no longer the pin.
            var chosen = pin.isPresent() || indexed.coverPinned()
                    ? pin.or(() -> covers.candidates(entries, null).findFirst()) : Optional.<Entry>empty();
            boolean rendered = chosen.map(e -> e.name().equals(indexed.cover())).orElse(true);
            album = indexed.with(prev.isPresent() ? key : UUID.randomUUID().toString(),
                            prev.map(Album::revision).orElse(0L) + 1, prev.map(Album::rating).orElse(0))
                    .withEdits(prev.map(a -> a.internal().edited()).orElse(AlbumMeta.EMPTY))
                    .withCover(rendered ? indexed.cover() : chosen.get().name(), pin.isPresent(),
                            rendered ? indexed.coverPortrait() : portrait(chosen.get()));
            store(album, entries, rendered ? cover : null);
        }
        return changed(album);
    }

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
            // An unchanged cover keeps its rendered bytes; a new one is rendered on the next request.
            store(album, null, Objects.equals(album.cover(), stored.cover()) ? blobs.get(coverKey(stored)).orElse(null) : null);
        }
        return changed(album);
    }

    public void remove(String id) {
        synchronized (lock(id)) {
            repo.delete(id);
            blobs.remove("cover/" + id + "/");
            blobs.remove("thumb/" + id + "/");
        }
        events.publishEvent(new AlbumEvent.Removed(id));
    }

    /** Removes the albums of every root not in {@code roots}, so the library follows the configuration. */
    public void retainRoots(Collection<String> roots) {
        repo.rootStats().keySet().stream().filter(root -> !roots.contains(root))
                .flatMap(root -> repo.byRoot(root).stream()).forEach(a -> remove(a.id()));
    }

    public Optional<byte[]> cover(Album album) { return cached(coverKey(album), album, album.cover()); }

    public Optional<byte[]> thumb(Album album, Entry entry) {
        return cached("thumb/%s/%d/%d".formatted(album.id(), album.revision(), entry.index()), album, entry.name());
    }

    private void store(Album album, List<Entry> entries, byte[] cover) {
        repo.save(album, entries);
        blobs.remove("cover/" + album.id() + "/"); // keys of older revisions
        blobs.remove("thumb/" + album.id() + "/");
        if (cover != null) blobs.put(coverKey(album), cover);
    }

    private Album changed(Album album) {
        events.publishEvent(new AlbumEvent.Changed(album));
        return album;
    }

    private Optional<byte[]> cached(String key, Album album, String entry) {
        var hit = blobs.get(key);
        if (hit.isPresent() || entry == null) return hit;
        try (var container = containers.open(album.kind(), Path.of(album.path()), Exclusions.NONE)) {
            var jpeg = thumbnailer.render(container, entry).jpeg();
            blobs.put(key, jpeg);
            return Optional.of(jpeg);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot render {} of {}: {}", entry, album.path(), e.toString());
            return Optional.empty();
        }
    }

    private static String coverKey(Album album) { return "cover/%s/%d".formatted(album.id(), album.revision()); }

    private static Optional<Entry> entry(List<Entry> entries, String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst();
    }

    private static boolean portrait(Entry e) { return e.height() > e.width(); }

    private Object lock(String id) { return locks[Math.floorMod(id.hashCode(), locks.length)]; }
}
