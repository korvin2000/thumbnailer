package thumbnailer.web.ws;

import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;
import thumbnailer.album.AlbumRepository;
import thumbnailer.catalog.Catalog;

/** Answers the single-photo messages: marks, favorites lists of photos, metadata, text files. */
@Component
class PhotosApi {

    private final Catalog catalog;
    private final AlbumRepository repo;

    PhotosApi(Catalog catalog, AlbumRepository repo) {
        this.catalog = catalog;
        this.repo = repo;
    }

    Event handle(Request.PhotosRequest request) {
        return switch (request) {
            case Request.MarkPhoto(var albumId, var name, var rating, var lists) -> {
                var album = repo.find(albumId).orElseThrow(() -> new NoSuchElementException("No album " + albumId));
                if (repo.entries(albumId).stream().noneMatch(e -> e.name().equals(name)))
                    throw new IllegalArgumentException("No entry " + name);
                int seq = album.internal().seq();
                catalog.mark(seq, name, rating, lists);
                yield new Event.PhotoMarks(albumId, catalog.marks(seq));
            }
            case Request.Photos p -> throw new UnsupportedOperationException("not implemented: photos");
            case Request.PhotoMeta p -> throw new UnsupportedOperationException("not implemented: photoMeta");
            case Request.AlbumText t -> throw new UnsupportedOperationException("not implemented: albumText");
        };
    }
}
