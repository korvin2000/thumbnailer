package thumbnailer.web.ws;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.xmp.XmpDirectory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.album.Entry;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.CatalogRepository.Listed;
import thumbnailer.image.ImageProbe;
import thumbnailer.metadata.Extraction;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Containers;
import thumbnailer.source.Exclusions;

/**
 * Answers the single-photo messages: marks, the photos of a favorites list, an image's metadata (what it learns about
 * an entry's metadata is stored back as {@link Entry#flags}), an album's text files. File reads are counted album reads
 * ({@link AlbumService#read}: refused while an operation holds the album) in a turn on the UI's lane of the album's disk;
 * a mark checks its name against the entries inside such a read, so an operation renaming them cannot land in between.
 */
@Component
class PhotosApi {

    static final int PAGE = 500, TEXT = Extraction.TEXT_TOTAL, VALUE = 4096;
    /** The largest file {@code photoMeta} reads whole (a 7-Zip member is read whole onto the heap). */
    static final long WHOLE = 256L << 20;

    private final Catalog catalog;
    private final AlbumRepository repo;
    private final AlbumService albums;
    private final Containers containers;
    private final SettingsStore settings;

    PhotosApi(Catalog catalog, AlbumRepository repo, AlbumService albums, Containers containers, SettingsStore settings) {
        this.catalog = catalog;
        this.repo = repo;
        this.albums = albums;
        this.containers = containers;
        this.settings = settings;
    }

    Event handle(Request.PhotosRequest request) throws IOException {
        return switch (request) {
            case Request.MarkPhoto(var albumId, var name, var rating, var lists) -> {
                int seq;
                try (var _ = albums.read(albumId)) { // an operation renaming the files waits for the mark, or refuses it (busy)
                    seq = album(albumId).internal().seq();
                    if (repo.entries(albumId).stream().noneMatch(e -> e.name().equals(name)))
                        throw new IllegalArgumentException("No entry " + name);
                    catalog.mark(seq, name, rating, lists);
                }
                yield new Event.PhotoMarks(albumId, catalog.marks(seq));
            }
            case Request.Photos(var listId, var offset, var limit) -> photos(listId, Math.max(0, offset), Math.clamp(limit, 0, PAGE));
            case Request.PhotoMeta(var albumId, var index) -> {
                var album = album(albumId);
                var entry = repo.entry(albumId, index).orElseThrow(() -> new NoSuchElementException("No image " + index + " in " + albumId));
                var meta = read(album, c -> metadata(c, entry));
                if (flags(meta) != entry.flags()) albums.setFlags(albumId, entry, flags(meta));
                if (meta == null) throw new IllegalArgumentException("No metadata in " + entry.name());
                yield new Event.PhotoMetaRows(rows(meta));
            }
            case Request.AlbumText(var albumId, var name) -> {
                if (name == null || !Extraction.isText(name)) throw new IllegalArgumentException("Not a text file: " + name);
                var album = album(albumId);
                yield new Event.Text(name, read(album, c -> {
                    var item = c.items().stream().filter(i -> i.name().equals(name)).findFirst() // a member, never a path out of it
                            .orElseThrow(() -> new IllegalArgumentException("No text " + name));
                    var b = c.head(name, TEXT + 4); // lookahead: the charset is chosen on more than is shown
                    return Extraction.decode(b, Math.min(b.length, TEXT), b.length < item.bytes());
                }));
            }
        };
    }

    /** One page by album path, then photo name; {@code index} is -1 for a marked file the album no longer lists. */
    private Event.PhotoList photos(int listId, int offset, int limit) {
        var indexes = new HashMap<String, Map<String, Integer>>();
        var items = new ArrayList<Event.PhotoList.Photo>();
        for (Listed r : catalog.photos(listId, offset, limit)) {
            var index = indexes.computeIfAbsent(r.albumId(), id -> repo.entries(id).stream()
                    .collect(Collectors.toMap(Entry::name, Entry::index, (a, b) -> a))).getOrDefault(r.name(), -1);
            items.add(new Event.PhotoList.Photo(r.albumId(), r.name(), index, r.rating(),
                    new Event.PhotoList.AlbumRef(r.albumId(), r.album(), r.revision())));
        }
        return new Event.PhotoList(items, catalog.photoCount(listId));
    }

    /**
     * metadata-extractor over the head; the whole file (up to {@link #WHOLE}) when the head alone does not parse, or
     * parses without an Exif/XMP block while it is only a prefix (the parser stops quietly where a skipped segment runs
     * past it); null when nothing parses (a format it does not read: no metadata to show).
     */
    private static Metadata metadata(AlbumContainer c, Entry entry) throws IOException {
        try {
            var head = c.head(entry.name(), ImageProbe.HEAD);
            var meta = ImageMetadataReader.readMetadata(new ByteArrayInputStream(head));
            if (head.length < ImageProbe.HEAD || (flags(meta) & Entry.META) != 0 || entry.bytes() > WHOLE) return meta;
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            if (entry.bytes() > WHOLE) return null;
        }
        try (var in = c.open(entry.name())) {
            return ImageMetadataReader.readMetadata(in);
        } catch (ImageProcessingException e) {
            return null;
        }
    }

    /** What a metadata read learned about an entry: an Exif or XMP block is metadata (JPEG APP1, TIFF, PNG eXIf). */
    private static int flags(Metadata meta) {
        if (meta != null) for (var d : meta.getDirectories())
            if (d instanceof ExifDirectoryBase || d instanceof XmpDirectory) return Entry.META_KNOWN | Entry.META;
        return Entry.META_KNOWN;
    }

    private static List<Event.PhotoMetaRows.Row> rows(Metadata meta) {
        var rows = new ArrayList<Event.PhotoMetaRows.Row>();
        for (var dir : meta.getDirectories())
            for (var tag : dir.getTags()) {
                var value = tag.getDescription();
                if (value != null) rows.add(new Event.PhotoMetaRows.Row(dir.getName(), tag.getTagName(),
                        value.length() > VALUE ? value.substring(0, VALUE) : value));
            }
        return rows;
    }

    private interface Read<T> { T apply(AlbumContainer container) throws IOException; }

    private <T> T read(Album album, Read<T> body) throws IOException {
        var s = settings.get();
        try (var reading = albums.read(album.id());
             var turn = albums.lane(s, album.path()).enter();
             var container = containers.open(album.kind(), s.locate(album.path()), Exclusions.NONE)) {
            return body.apply(container);
        }
    }

    private Album album(String id) { return repo.find(id).orElseThrow(() -> new NoSuchElementException("No album " + id)); }
}
