package thumbnailer.web;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
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
import thumbnailer.source.Containers;
import thumbnailer.source.Exclusions;

/** Image bytes; URLs carry {@code ?v=revision}, so responses are cacheable for a day and the ETag is the revision. */
@RestController
@RequestMapping("/img/{albumId}")
class ImageController {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    private final AlbumRepository repo;
    private final AlbumService albums;
    private final Containers containers;

    ImageController(AlbumRepository repo, AlbumService albums, Containers containers) {
        this.repo = repo;
        this.albums = albums;
        this.containers = containers;
    }

    @GetMapping("/cover")
    ResponseEntity<byte[]> cover(@PathVariable String albumId, WebRequest request) {
        var album = album(albumId);
        return request.checkNotModified(etag(album)) ? null : jpeg(album, albums.cover(album));
    }

    @GetMapping("/{index}/thumb")
    ResponseEntity<byte[]> thumb(@PathVariable String albumId, @PathVariable int index, WebRequest request) {
        var album = album(albumId);
        return request.checkNotModified(etag(album)) ? null : jpeg(album, albums.thumb(album, entry(albumId, index)));
    }

    @GetMapping("/{index}")
    ResponseEntity<InputStreamResource> original(@PathVariable String albumId, @PathVariable int index,
                                                 WebRequest request) throws IOException {
        var album = album(albumId);
        var entry = entry(albumId, index);
        if (request.checkNotModified(etag(album))) return null;
        var container = containers.open(album.kind(), Path.of(album.path()), Exclusions.NONE);
        InputStream in;
        try {
            in = container.open(entry.name());
        } catch (IOException | RuntimeException e) {
            container.close();
            throw e;
        }
        var body = new FilterInputStream(in) { // closing the stream releases the container (zip file handle)
            @Override
            public void close() throws IOException {
                try (container) {
                    super.close();
                }
            }
        };
        return ResponseEntity.ok().cacheControl(CACHE).eTag(etag(album)).contentLength(entry.bytes())
                .contentType(MediaTypeFactory.getMediaType(entry.name()).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .body(new InputStreamResource(body));
    }

    private Album album(String albumId) {
        return repo.find(albumId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Entry entry(String albumId, int index) {
        return repo.entry(albumId, index).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String etag(Album album) { return String.valueOf(album.revision()); }

    private static ResponseEntity<byte[]> jpeg(Album album, Optional<byte[]> bytes) {
        return bytes.map(b -> ResponseEntity.ok().cacheControl(CACHE).eTag(etag(album)).contentType(MediaType.IMAGE_JPEG).body(b))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
