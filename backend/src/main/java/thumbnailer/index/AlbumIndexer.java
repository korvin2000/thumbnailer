package thumbnailer.index;

import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
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
import thumbnailer.album.Entry;
import thumbnailer.cover.CoverSelector;
import thumbnailer.image.ImageProbe;
import thumbnailer.image.Thumbnailer;
import thumbnailer.metadata.Metadata;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Kind;

/** Builds an album from an open container: entry probes, extracted metadata, statistics, cover thumbnail. */
@Component
class AlbumIndexer {

    private static final Logger log = LoggerFactory.getLogger(AlbumIndexer.class);

    private final AlbumRepository repo;
    private final Metadata metadata;
    private final CoverSelector covers;
    private final Thumbnailer thumbnailer;

    AlbumIndexer(AlbumRepository repo, Metadata metadata, CoverSelector covers, Thumbnailer thumbnailer) {
        this.repo = repo;
        this.metadata = metadata;
        this.covers = covers;
        this.thumbnailer = thumbnailer;
    }

    /** An indexed album without id, revision or user state; {@link thumbnailer.album.AlbumService#commit} adds them. */
    record Indexed(Album album, List<Entry> entries, byte[] cover) {}

    /** Entries whose name, size and mtime are unchanged since {@code base} reuse the stored probe. */
    Optional<Indexed> index(AlbumContainer container, String root, String path, Kind kind, Album base) {
        Map<String, Entry> known = base == null ? Map.of()
                : repo.entries(base.id()).stream().collect(toMap(Entry::name, Function.identity()));
        var entries = new ArrayList<Entry>();
        for (var item : container.images()) {
            if (Thread.currentThread().isInterrupted()) return Optional.empty(); // shutting down
            var old = known.get(item.name());
            try {
                var probe = old != null && old.bytes() == item.bytes() && old.mtime() == item.mtime()
                        ? new ImageProbe.Probe(old.width(), old.height(), old.shotAt())
                        : ImageProbe.probe(container, item.name());
                entries.add(new Entry(entries.size(), item.name(), item.bytes(), item.mtime(), probe.width(),
                        probe.height(), probe.shotAt()));
            } catch (IOException | RuntimeException e) {
                log.warn("Skipping unreadable image {} in {}: {}", item.name(), path, e.toString());
            }
        }
        if (entries.isEmpty()) return Optional.empty();

        var extracted = metadata.extract(container, entries);
        String pinned = base != null && base.coverPinned() ? base.cover() : null;
        Entry cover = null;
        Thumbnailer.Thumb thumb = null;
        for (var it = covers.candidates(entries, pinned).iterator(); thumb == null && it.hasNext(); ) {
            if (Thread.currentThread().isInterrupted()) return Optional.empty();
            var candidate = it.next();
            try {
                thumb = thumbnailer.render(container, candidate.name());
                cover = candidate;
            } catch (IOException | RuntimeException e) {
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
        return Optional.of(new Indexed(album.withEdits(AlbumMeta.EMPTY), List.copyOf(entries),
                thumb == null ? null : thumb.jpeg()));
    }
}
