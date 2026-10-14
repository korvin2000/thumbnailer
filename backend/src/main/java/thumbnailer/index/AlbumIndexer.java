package thumbnailer.index;

import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.album.Entry;
import thumbnailer.cover.CoverSelector;
import thumbnailer.image.ImageProbe;
import thumbnailer.image.Thumbnailer.Thumb;
import thumbnailer.metadata.Metadata;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Kind;

/**
 * Builds an album from an open container: entry probes, extracted metadata, statistics, cover thumbnail. Unchanged
 * files reuse their stored probe and the stored cover; covers render through {@link AlbumService#renderCover}, which
 * bounds them.
 */
@Component
class AlbumIndexer {

    private static final Logger log = LoggerFactory.getLogger(AlbumIndexer.class);

    private final AlbumRepository repo;
    private final Metadata metadata;
    private final CoverSelector covers;
    private final AlbumService albums;

    AlbumIndexer(AlbumRepository repo, Metadata metadata, CoverSelector covers, AlbumService albums) {
        this.repo = repo;
        this.metadata = metadata;
        this.covers = covers;
        this.albums = albums;
    }

    /** An indexed album without id, revision or user state; {@link AlbumService#commit} adds them. */
    record Indexed(Album album, List<Entry> entries, Thumb cover) {}

    /** Entries whose name, size and mtime are unchanged since {@code base} reuse the stored probe. */
    Optional<Indexed> index(AlbumContainer container, String root, String path, Kind kind, Album base) {
        Map<String, Entry> known = base == null ? Map.of()
                : repo.entries(base.id()).stream().collect(toMap(Entry::name, Function.identity()));
        var images = container.images();
        var probes = new HashMap<String, Object>(); // name -> Probe, or the reason it is unreadable
        var todo = new ArrayList<String>();
        for (var item : images) {
            var old = known.get(item.name());
            if (unchanged(old, item.bytes(), item.mtime())) probes.put(item.name(), new ImageProbe.Probe(old.width(), old.height(), old.shotAt()));
            else todo.add(item.name());
        }
        var later = new ArrayList<String>(); // formats the head alone cannot probe
        var indexer = Thread.currentThread();
        try { // the container picks the read order: one decompression pass for a solid archive, maybe on its threads
            container.heads(todo, ImageProbe.HEAD, (name, head) -> {
                if (indexer.isInterrupted()) throw new InterruptedIOException(); // shutting down
                try {
                    var probe = ImageProbe.probe(head); // pure: a sink must not read the container
                    synchronized (probes) {
                        if (probe != null) probes.put(name, probe);
                        else later.add(name);
                    }
                } catch (RuntimeException e) {
                    synchronized (probes) { probes.put(name, e.toString()); }
                }
            });
            synchronized (probes) {} // what the sink wrote on 7-Zip's threads is visible from here on
            for (var name : later) {
                if (indexer.isInterrupted()) return Optional.empty();
                try {
                    probes.put(name, ImageProbe.probe(container, name));
                } catch (IOException | RuntimeException e) {
                    probes.put(name, e.toString());
                }
            }
        } catch (InterruptedIOException e) {
            return Optional.empty();
        } catch (IOException | RuntimeException e) { // the archive, not one image: keep the stored album for a retry
            log.warn("Cannot read the images of {}: {}", path, e.toString());
            return Optional.empty();
        }
        var entries = new ArrayList<Entry>();
        for (var item : images) {
            if (probes.get(item.name()) instanceof ImageProbe.Probe probe)
                entries.add(new Entry(entries.size(), item.name(), item.bytes(), item.mtime(), probe.width(), probe.height(), probe.shotAt()));
            else log.warn("Skipping unreadable image {} in {}: {}", item.name(), path, probes.getOrDefault(item.name(), "no data"));
        }
        if (entries.isEmpty()) return Optional.empty();

        var extracted = metadata.extract(container, entries);
        String pinned = base != null && base.coverPinned() ? base.cover() : null;
        var stored = base == null ? Optional.<Thumb>empty() : albums.storedCover(base);
        Entry cover = null;
        Thumb thumb = null;
        for (var it = covers.candidates(entries, pinned).iterator(); cover == null && it.hasNext(); ) {
            if (Thread.currentThread().isInterrupted()) return Optional.empty();
            var candidate = it.next();
            if (stored.isPresent() && candidate.name().equals(base.cover()) && unchanged(known.get(candidate.name()), candidate.bytes(), candidate.mtime())) {
                cover = candidate; // the stored bytes still show this file: no decode, no encode
                thumb = stored.get();
                break;
            }
            try {
                thumb = albums.renderCover(container, candidate.name());
                cover = candidate;
            } catch (IOException | RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) return Optional.empty();
                log.warn("Cover candidate {} in {} is not decodable: {}", candidate.name(), path, e.toString());
            }
        }

        var mtime = entries.stream().mapToLong(Entry::mtime).summaryStatistics();
        var pixels = entries.stream().mapToLong(e -> (long) e.width() * e.height()).summaryStatistics();
        var shotDate = entries.stream().map(Entry::shotAt).filter(Objects::nonNull).min(Comparator.naturalOrder())
                .map(LocalDateTime::toLocalDate).orElse(extracted.date() != null ? extracted.date()
                        : LocalDate.ofInstant(Instant.ofEpochMilli(mtime.getMin()), ZoneId.systemDefault()));
        var album = new Album(null, root, path, kind, null, cover == null ? null : cover.name(),
                cover != null && cover.name().equals(pinned), 0, thumb != null && thumb.portrait(), entries.size(),
                entries.stream().mapToLong(Entry::bytes).sum(), (long) pixels.getAverage(),
                entries.stream().mapToInt(Entry::width).max().orElse(0),
                entries.stream().mapToInt(Entry::height).max().orElse(0), shotDate,
                Instant.ofEpochMilli(mtime.getMax()), Instant.now(), null, null, null, null, List.of(), 0,
                new Album.Internal(container.contentId(), container.signature(), extracted, AlbumMeta.EMPTY));
        return Optional.of(new Indexed(album.withEdits(AlbumMeta.EMPTY), List.copyOf(entries), thumb));
    }

    private static boolean unchanged(Entry old, long bytes, long mtime) {
        return old != null && old.bytes() == bytes && old.mtime() == mtime;
    }
}
