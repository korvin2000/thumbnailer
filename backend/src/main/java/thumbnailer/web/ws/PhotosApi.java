package thumbnailer.web.ws;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.album.Entry;
import thumbnailer.catalog.Catalog;
import thumbnailer.image.ImageProbe;
import thumbnailer.metadata.Extraction;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Containers;
import thumbnailer.source.Exclusions;

/**
 * Answers the single-photo messages: marks, the photos of a favorites list, an image's metadata, an album's text
 * files. File reads are counted album reads ({@link AlbumService#read}: refused while an operation holds the album) in
 * a turn on the UI's lane of the album's disk.
 */
@Component
class PhotosApi {

    static final int PAGE = 500, TEXT = Extraction.TEXT_TOTAL, VALUE = 4096;

    /** A list's photos of visible albums; the catalog port has no such page yet, so the query lives here (see PLAN). */
    private static final String LISTED = " FROM photo_link l JOIN photo p ON p.id = l.photo_id JOIN album a ON a.seq = p.album_seq"
            + " WHERE l.entity_id = ? AND NOT a.hidden";

    private final Catalog catalog;
    private final AlbumRepository repo;
    private final AlbumService albums;
    private final Containers containers;
    private final SettingsStore settings;
    private final JdbcTemplate jdbc;

    PhotosApi(Catalog catalog, AlbumRepository repo, AlbumService albums, Containers containers, SettingsStore settings,
              JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.repo = repo;
        this.albums = albums;
        this.containers = containers;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    Event handle(Request.PhotosRequest request) throws IOException {
        return switch (request) {
            case Request.MarkPhoto(var albumId, var name, var rating, var lists) -> {
                var album = album(albumId);
                if (repo.entries(albumId).stream().noneMatch(e -> e.name().equals(name)))
                    throw new IllegalArgumentException("No entry " + name);
                int seq = album.internal().seq();
                catalog.mark(seq, name, rating, lists);
                yield new Event.PhotoMarks(albumId, catalog.marks(seq));
            }
            case Request.Photos(var listId, var offset, var limit) -> photos(listId, Math.max(0, offset), Math.clamp(limit, 0, PAGE));
            case Request.PhotoMeta(var albumId, var index) -> {
                var album = album(albumId);
                var entry = repo.entry(albumId, index).orElseThrow(() -> new NoSuchElementException("No image " + index + " in " + albumId));
                yield new Event.PhotoMetaRows(read(album, c -> rows(c, entry)));
            }
            case Request.AlbumText(var albumId, var name) -> {
                if (name == null || !Extraction.isText(name)) throw new IllegalArgumentException("Not a text file: " + name);
                var album = album(albumId);
                yield new Event.Text(name, read(album, c -> {
                    var b = c.head(name, TEXT + 1);
                    return Extraction.decode(b.length > TEXT ? Arrays.copyOf(b, TEXT) : b, b.length > TEXT);
                }));
            }
        };
    }

    /** One page by album path, then photo name; {@code index} is -1 for a marked file the album no longer lists. */
    private Event.PhotoList photos(int listId, int offset, int limit) {
        int total = jdbc.queryForObject("SELECT COUNT(*)" + LISTED, Integer.class, listId);
        record Row(String albumId, String album, long revision, String name, int rating) {}
        var rows = jdbc.query("SELECT a.id, a.name, a.revision, p.name, p.rating" + LISTED + " ORDER BY a.path, p.name LIMIT ? OFFSET ?",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getInt(5)), listId, limit, offset);
        var indexes = new HashMap<String, Map<String, Integer>>();
        var items = new ArrayList<Event.PhotoList.Photo>();
        for (var r : rows) {
            var index = indexes.computeIfAbsent(r.albumId(), id -> repo.entries(id).stream()
                    .collect(Collectors.toMap(Entry::name, Entry::index, (a, b) -> a))).getOrDefault(r.name(), -1);
            items.add(new Event.PhotoList.Photo(r.albumId(), r.name(), index, r.rating(),
                    new Event.PhotoList.AlbumRef(r.albumId(), r.album(), r.revision())));
        }
        return new Event.PhotoList(items, total);
    }

    /** metadata-extractor over the head; the whole file when the head alone does not parse (metadata past it). */
    private static List<Event.PhotoMetaRows.Row> rows(AlbumContainer c, Entry entry) throws IOException {
        Metadata meta;
        try {
            meta = ImageMetadataReader.readMetadata(new ByteArrayInputStream(c.head(entry.name(), ImageProbe.HEAD)));
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            try (var in = c.open(entry.name())) {
                meta = ImageMetadataReader.readMetadata(in);
            } catch (ImageProcessingException e2) {
                throw new IllegalArgumentException("No metadata in " + entry.name() + ": " + e2.getMessage());
            }
        }
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
