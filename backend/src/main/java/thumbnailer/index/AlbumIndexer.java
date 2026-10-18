package thumbnailer.index;

import static java.util.stream.Collectors.toMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.album.Entry;
import thumbnailer.cover.CoverSelector;
import thumbnailer.image.Header;
import thumbnailer.image.ImageProbe;
import thumbnailer.image.Thumbnailer.Thumb;
import thumbnailer.metadata.Extraction;
import thumbnailer.metadata.Metadata;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Cry;
import thumbnailer.source.Disks;
import thumbnailer.source.Kind;

/**
 * Builds an album from an open container: entry probes and their metadata flags, the {@link Extraction} context (text
 * members read with the image heads, in one pass; a sequential archive's pass also takes the heads the built-in
 * extractors read, {@code album.json} and the first image, which it keeps), extracted metadata, the bio file,
 * statistics, cover thumbnail.
 * Unchanged files reuse their stored probe and the stored cover; covers render through {@link
 * AlbumService#renderCover}, which bounds them.
 */
@Component
class AlbumIndexer {

    private static final Logger log = LoggerFactory.getLogger(AlbumIndexer.class);
    private static final Pattern BIO = Pattern.compile("bio|model");

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

    /**
     * Entries whose name, size and mtime are unchanged since {@code base} reuse the stored probe and its flags; entries an
     * operation renamed ({@code nameMap}: old name → new name, else empty) are looked up by their old name. Reads during
     * {@code turn}, the album's turn on its disk, which the first cover read ends (or the end of the reads when the
     * stored cover still holds); a further candidate waits for a new turn on {@code lane}. A {@link
     * AlbumContainer#cpuBound} archive gives the turn back after its listing. Between head reads the scan gives way
     * to the UI reading the same disk.
     */
    Optional<Indexed> index(AlbumContainer container, String root, String path, Kind kind, Album base, Disks.Hold turn,
                            Disks.Lane lane, Map<String, String> nameMap) {
        var stored = base == null ? Map.<String, Entry>of()
                : repo.entries(base.id()).stream().collect(toMap(Entry::name, Function.identity()));
        var was = nameMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        Function<String, Entry> known = name -> stored.get(was.getOrDefault(name, name));
        var images = container.images();
        var items = container.items();
        var budget = Extraction.budget(items);
        var texts = new HashMap<String, String>();
        var flags = new HashMap<String, Integer>();
        boolean cpuBound = container.cpuBound();
        if (cpuBound) turn.close(); // decompression, not the disk, limits it: let other albums read meanwhile
        var probes = new HashMap<String, Object>(); // name -> Probe, or the reason it is unreadable
        var todo = new ArrayList<String>();
        for (var item : images) {
            var old = known.apply(item.name());
            if (unchanged(old, item.bytes(), item.mtime())) probes.put(item.name(), new ImageProbe.Probe(old.width(), old.height(), old.shotAt()));
            else todo.add(item.name());
        }
        var later = new ArrayList<String>(); // formats the head alone cannot probe
        var indexer = Thread.currentThread();
        boolean sequential = container.sequential();
        var batch = new LinkedHashSet<>(todo);
        batch.addAll(budget.keySet());
        if (sequential) // in the pass too, kept by the container for the built-in extractors' head reads: no pass of their own
            Stream.concat(images.stream().limit(1), items.stream().filter(i -> Extraction.fileName(i.name()).equals("album.json")).limit(1))
                    .forEach(i -> batch.add(i.name()));
        var probing = Set.copyOf(todo);
        try { // the container picks the read order: one decompression pass for a solid archive, maybe on its threads
            container.heads(List.copyOf(batch), ImageProbe.HEAD, (name, head) -> {
                if (indexer.isInterrupted()) throw new InterruptedIOException(); // shutting down
                lane.giveWay();
                var allowed = budget.get(name);
                if (allowed != null) { // a text member, never an image
                    var text = Extraction.decode(head, Math.min(head.length, allowed), head.length < bytes(items, name));
                    synchronized (probes) { texts.put(name, text); }
                    return;
                }
                if (!probing.contains(name)) return; // read for the extractors only
                try {
                    var probe = ImageProbe.probe(head); // pure: a sink must not read the container
                    int f = flags(head);
                    synchronized (probes) {
                        if (probe != null) probes.put(name, probe);
                        else later.add(name);
                        flags.put(name, f);
                    }
                } catch (RuntimeException e) {
                    synchronized (probes) { probes.put(name, e.toString()); }
                }
            });
            synchronized (probes) {} // what the sink wrote on 7-Zip's threads is visible from here on
            if (sequential) { // each file costs a pass over the ones packed before it: all in one pass
                container.heads(later, Integer.MAX_VALUE, (name, whole) -> {
                    if (indexer.isInterrupted()) throw new InterruptedIOException();
                    Object probe;
                    try {
                        probe = ImageProbe.probe(whole, name, () -> new ByteArrayInputStream(whole));
                    } catch (IOException | RuntimeException e) {
                        probe = e.toString();
                    }
                    int f = flags(whole);
                    synchronized (probes) {
                        probes.put(name, probe);
                        flags.put(name, f);
                    }
                });
                synchronized (probes) {}
            } else {
                for (var name : later) {
                    if (indexer.isInterrupted()) return Optional.empty();
                    try {
                        lane.giveWay();
                        probes.put(name, ImageProbe.probe(container, name));
                    } catch (IOException | RuntimeException e) {
                        probes.put(name, e.toString());
                    }
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
            var old = known.apply(item.name());
            if (probes.get(item.name()) instanceof ImageProbe.Probe probe)
                entries.add(new Entry(entries.size(), item.name(), item.bytes(), item.mtime(), probe.width(), probe.height(),
                        probe.shotAt(), unchanged(old, item.bytes(), item.mtime()) ? old.flags() : flags.getOrDefault(item.name(), 0)));
            else log.warn("Skipping unreadable image {} in {}: {}", item.name(), path, probes.getOrDefault(item.name(), "no data"));
        }
        if (entries.isEmpty()) return Optional.empty();

        var ctx = new Extraction(container.name(), path, kind, entries, items, kind == Kind.DIRECTORY ? null : Extraction.longestDir(items),
                Extraction.sample(entries), texts, container);
        var meta = metadata.extract(ctx);
        var bio = items.stream().map(AlbumContainer.Item::name)
                .filter(n -> Extraction.isText(n) && BIO.matcher(Extraction.fileName(n)).find()).findFirst().orElse(null);
        var baseCover = base == null || base.cover() == null ? null : nameMap.getOrDefault(base.cover(), base.cover());
        String pinned = base != null && base.coverPinned() ? baseCover : null;
        var storedCover = base == null ? Optional.<Thumb>empty() : albums.storedCover(base);
        Entry cover = null;
        Thumb thumb = null;
        for (var it = covers.candidates(ctx, pinned).iterator(); cover == null && it.hasNext(); ) {
            if (Thread.currentThread().isInterrupted()) return Optional.empty();
            var candidate = it.next();
            if (storedCover.isPresent() && candidate.name().equals(baseCover) && unchanged(known.apply(candidate.name()), candidate.bytes(), candidate.mtime())) {
                cover = candidate; // the stored bytes still show this file: no decode, no encode
                thumb = storedCover.get();
                break;
            }
            try {
                var read = turn;
                turn = null; // spent: a further candidate waits for a new turn
                thumb = albums.renderCover(container, candidate.name(), read != null ? read : cpuBound ? Disks.Hold.NONE : lane.enter());
                cover = candidate;
            } catch (IOException | RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) return Optional.empty();
                log.warn("Cover candidate {} in {} is not decodable: {}", candidate.name(), path, e.toString());
            }
        }

        if (turn != null) turn.close(); // the stored cover needed no read
        var mtime = entries.stream().mapToLong(Entry::mtime).summaryStatistics();
        var pixels = entries.stream().mapToLong(e -> (long) e.width() * e.height()).summaryStatistics();
        var shotDate = entries.stream().map(Entry::shotAt).filter(Objects::nonNull).min(Comparator.naturalOrder())
                .map(LocalDateTime::toLocalDate).orElse(meta.date() != null ? meta.date()
                        : LocalDate.ofInstant(Instant.ofEpochMilli(mtime.getMin()), ZoneId.systemDefault()));
        var extracted = meta.withDate(shotDate); // the computed date is the extracted one: an edited date overrides it
        var album = new Album(null, root, path, kind, null, cover == null ? null : cover.name(),
                cover != null && cover.name().equals(pinned), 0, thumb != null && thumb.portrait(), entries.size(),
                entries.stream().mapToLong(Entry::bytes).sum(), (long) pixels.getAverage(),
                entries.stream().mapToInt(Entry::width).max().orElse(0),
                entries.stream().mapToInt(Entry::height).max().orElse(0), shotDate,
                Instant.ofEpochMilli(mtime.getMax()), Instant.now(), null, null, null, null, List.of(), 0, false, bio,
                List.of(), List.of(), 0, new Album.Internal(container.contentId(), Cry.signature(container), extracted, AlbumMeta.EMPTY, 0));
        return Optional.of(new Indexed(album.withEdits(AlbumMeta.EMPTY), List.copyOf(entries), thumb));
    }

    private static long bytes(List<AlbumContainer.Item> items, String name) {
        return items.stream().filter(i -> i.name().equals(name)).findFirst().map(AlbumContainer.Item::bytes).orElse(0L);
    }

    /** {@link Entry#flags} from what a head shows: 0 when it could not be parsed. */
    private static int flags(byte[] head) {
        var meta = Header.meta(head);
        return meta == null ? 0 : Entry.META_KNOWN | (meta ? Entry.META : 0);
    }

    /** Never a {@code .cry} entry: a re-index may be due to a key change, which its file does not show. */
    private static boolean unchanged(Entry old, long bytes, long mtime) {
        return old != null && old.bytes() == bytes && old.mtime() == mtime && !Cry.is(old.name());
    }
}
