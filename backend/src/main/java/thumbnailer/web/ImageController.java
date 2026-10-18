package thumbnailer.web;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.album.Entry;
import thumbnailer.image.Thumbnailer.Thumb;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.source.Cry;
import thumbnailer.source.Exclusions;

/**
 * Image bytes; URLs carry {@code ?v=revision} (and {@code &r=render epoch} for covers and thumbnails), so responses are
 * cacheable for a day; the ETag is the revision, plus the epoch of the render spec for rendered images. An album an
 * operation holds answers 503 with {@code Retry-After: 2} and opens nothing, also when it became busy mid-request.
 */
@RestController
@RequestMapping("/img/{albumId}")
class ImageController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    private final AlbumRepository repo;
    private final AlbumService albums;
    private final Containers containers;
    private final SettingsStore settings;

    ImageController(AlbumRepository repo, AlbumService albums, Containers containers, SettingsStore settings) {
        this.repo = repo;
        this.albums = albums;
        this.containers = containers;
        this.settings = settings;
    }

    /** The album and entry are looked up inside the read, so an operation's quiesce waits for the whole request (busy → 503). */
    @GetMapping("/cover")
    ResponseEntity<byte[]> cover(@PathVariable String albumId, WebRequest request) {
        try (var _ = albums.read(albumId)) {
            var album = album(albumId);
            return request.checkNotModified(etag(album, settings.get().covers().epoch())) ? null : image(album, albums.cover(album));
        }
    }

    @GetMapping("/{index}/thumb")
    ResponseEntity<byte[]> thumb(@PathVariable String albumId, @PathVariable int index, WebRequest request) {
        try (var _ = albums.read(albumId)) {
            var album = album(albumId);
            return request.checkNotModified(etag(album, settings.get().thumbs().epoch())) ? null
                    : image(album, albums.thumb(album, entry(albumId, index)));
        }
    }

    /** Streamed: the read of the album lasts until the stream is closed, so an operation's quiesce waits for it. */
    @GetMapping("/{index}")
    ResponseEntity<InputStreamResource> original(@PathVariable String albumId, @PathVariable int index,
                                                 WebRequest request) throws IOException {
        var reading = albums.read(albumId); // busy → 503
        try {
            var album = album(albumId);
            var entry = entry(albumId, index);
            if (request.checkNotModified(etag(album))) {
                reading.close();
                return null;
            }
            var container = containers.open(album.kind(), settings.get().locate(album.path()), Exclusions.NONE);
            InputStream in;
            try {
                in = container.open(entry.name());
            } catch (IOException | RuntimeException e) {
                container.close();
                throw e;
            }
            var body = new FilterInputStream(in) { // closing the stream releases the container (zip file handle) and the read
                @Override
                public void close() throws IOException {
                    try (reading; container) {
                        super.close();
                    }
                }
            };
            var response = ResponseEntity.ok().cacheControl(CACHE).eTag(etag(album));
            if (!Cry.is(entry.name())) response.contentLength(entry.bytes()); // a .cry entry's size is the file's, not the image's
            return response.contentType(MediaTypeFactory.getMediaType(entry.name()).orElse(MediaType.APPLICATION_OCTET_STREAM))
                    .body(new InputStreamResource(body));
        } catch (IOException | RuntimeException e) {
            reading.close();
            throw e;
        }
    }

    /** An album that became busy between the check and the read (an operation's quiesce): 503 like the check. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Void> raced(IllegalStateException e) {
        if (!AlbumService.BUSY.equals(e.getMessage())) throw e; // anything else: the default handling (500)
        return busy();
    }

    private Album album(String albumId) {
        return repo.find(albumId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Entry entry(String albumId, int index) {
        return repo.entry(albumId, index).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static <T> ResponseEntity<T> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "2").build();
    }

    private static String etag(Album album) { return String.valueOf(album.revision()); }

    private static String etag(Album album, long epoch) { return album.revision() + "-" + Long.toHexString(epoch); }

    /** Tagged with the epoch the bytes carry, which differs from the requested one only while the spec changes. */
    private static ResponseEntity<byte[]> image(Album album, Optional<Thumb> thumb) {
        return thumb.map(t -> ResponseEntity.ok().cacheControl(CACHE).eTag(etag(album, t.epoch()))
                        .contentType(MediaType.parseMediaType(t.format().mediaType())).body(t.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
