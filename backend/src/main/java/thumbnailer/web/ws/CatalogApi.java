package thumbnailer.web.ws;

import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumQuery;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.EntityKind;

/** Answers the catalog messages: entities, their cards and curation, bulk album edits, links and copies. */
@Component
class CatalogApi {

    private static final Event OK = new Event.Ok(null);

    private final Catalog catalog;
    private final AlbumRepository repo;
    private final AlbumService albums;

    CatalogApi(Catalog catalog, AlbumRepository repo, AlbumService albums) {
        this.catalog = catalog;
        this.repo = repo;
        this.albums = albums;
    }

    Event handle(Request.CatalogRequest request) {
        return switch (request) {
            case Request.Entities(var kind, var text, var under, var sort, var offset, var limit) -> {
                if (under != null) throw notImplemented("entities under");
                yield new Event.EntityList(catalog.entities(kind, text, sort == Request.Entities.Sort.COUNT, offset, limit),
                        catalog.count(kind, text));
            }
            case Request.GetEntity(var entityId) -> new Event.EntityCard(catalog.get(entityId));
            case Request.SaveEntity(var entity) -> new Event.EntityCard(catalog.save(entity));
            case Request.DeleteEntities(var ids) -> {
                catalog.delete(ids);
                yield OK;
            }
            case Request.MergeEntities(var from, var into) -> new Event.EntityCard(catalog.get(catalog.merge(from, into).id()));
            case Request.ConfirmEntities(var ids) -> {
                catalog.confirm(ids);
                yield OK;
            }
            case Request.CreditAs(var model, var studio, var alias, var roles) ->
                    new Event.EntityCard(catalog.get(catalog.creditAs(model, studio, alias, roles).id()));
            case Request.BulkUpdate(var ids, var query, var patch) -> {
                if (patch == null) throw new IllegalArgumentException("patch is required");
                var selected = repo.select(ids, query);
                selected.forEach(a -> albums.patch(a.id(), patch));
                yield new Event.Ok(selected.size());
            }
            case Request.LinkAlbums(var ids, var query, var entityId) -> link(ids, query, entityId, true);
            case Request.UnlinkAlbums(var ids, var query, var entityId) -> link(ids, query, entityId, false);
            case Request.Copies(var albumId) -> new Event.CopyList(repo.byContentId(find(albumId).stamp()));
            case Request.CompareCopies c -> throw notImplemented("compareCopies");
        };
    }

    /** Favorites lists are plain link rows; the text roles edit the albums' text (B1). */
    private Event link(List<String> ids, AlbumQuery query, int entityId, boolean linked) {
        if (catalog.get(entityId).entity().kind() != EntityKind.LIST) throw notImplemented(linked ? "link" : "unlink");
        return new Event.Ok(catalog.setList(repo.select(ids, query).stream().map(a -> a.internal().seq()).toList(), entityId, linked));
    }

    private Album find(String albumId) {
        return repo.find(albumId).orElseThrow(() -> new NoSuchElementException("No album " + albumId));
    }

    private static UnsupportedOperationException notImplemented(String what) {
        return new UnsupportedOperationException("not implemented: " + what);
    }
}
