package thumbnailer.web.ws;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.AlbumQuery;
import thumbnailer.catalog.EntityKind;
import thumbnailer.catalog.EntitySave;
import thumbnailer.ops.Op;
import thumbnailer.settings.Settings;

/**
 * Client to server messages; the client-chosen {@code id} is handled by {@link AlbumSocket}'s envelope. The catalog,
 * operation and photo messages are grouped by the facade that answers them ({@link CatalogApi}, {@link OpsApi},
 * {@link PhotosApi}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Request {

    @JsonTypeName("search") record Search(AlbumQuery query) implements Request {}

    @JsonTypeName("album") record Open(String albumId) implements Request {}

    @JsonTypeName("update") record Update(String albumId, AlbumPatch patch) implements Request {}

    @JsonTypeName("scan") record Scan(List<String> paths) implements Request {}

    @JsonTypeName("cancelScan") record CancelScan() implements Request {}

    @JsonTypeName("getSettings") record GetSettings() implements Request {}

    @JsonTypeName("setSettings") record SetSettings(Settings settings) implements Request {}

    @JsonTypeName("facets") record FacetsOf(AlbumQuery query) implements Request {}

    @JsonTypeName("reveal") record Reveal(String albumId) implements Request {}

    /** The child folders of a stored album path (the library tree). */
    @JsonTypeName("folders") record Folders(String path) implements Request {}

    /** One directory of the local file system, for the folder picker; null = the user's home. */
    @JsonTypeName("browse") record Browse(String path) implements Request {}

    @JsonTypeName("library") record GetLibrary() implements Request {}

    @JsonTypeName("switchLibrary") record SwitchLibrary(String dir) implements Request {}

    @JsonTypeName("forgetLibrary") record ForgetLibrary(String dir) implements Request {}

    @JsonTypeName("optimize") record Optimize() implements Request {}

    sealed interface CatalogRequest extends Request {}

    /** {@code text}: a prefix of any name; {@code under}: entities linked to that one (not yet); at most 500. */
    @JsonTypeName("entities") record Entities(EntityKind kind, String text, Integer under, Sort sort, int offset, int limit)
            implements CatalogRequest {
        enum Sort { NAME, COUNT }
    }

    @JsonTypeName("entity") record GetEntity(int entityId) implements CatalogRequest {}

    @JsonTypeName("saveEntity") record SaveEntity(EntitySave entity) implements CatalogRequest {}

    @JsonTypeName("deleteEntities") record DeleteEntities(List<Integer> ids) implements CatalogRequest {}

    @JsonTypeName("mergeEntities") record MergeEntities(List<Integer> from, int into) implements CatalogRequest {}

    @JsonTypeName("confirmEntities") record ConfirmEntities(List<Integer> ids) implements CatalogRequest {}

    @JsonTypeName("creditAs") record CreditAs(int modelId, int studioId, String alias, String roles) implements CatalogRequest {}

    /** The albums by id, else those matching {@code query} (at most 500 either way). */
    @JsonTypeName("bulkUpdate") record BulkUpdate(List<String> albumIds, AlbumQuery query, AlbumPatch patch) implements CatalogRequest {}

    @JsonTypeName("link") record LinkAlbums(List<String> albumIds, AlbumQuery query, int entityId) implements CatalogRequest {}

    @JsonTypeName("unlink") record UnlinkAlbums(List<String> albumIds, AlbumQuery query, int entityId) implements CatalogRequest {}

    @JsonTypeName("copies") record Copies(String albumId) implements CatalogRequest {}

    @JsonTypeName("compareCopies") record CompareCopies(String a, String b) implements CatalogRequest {}

    sealed interface OpsRequest extends Request {}

    @JsonTypeName("preflight") record PreflightOp(Op op) implements OpsRequest {}

    @JsonTypeName("op") record RunOp(Op op) implements OpsRequest {}

    @JsonTypeName("cancelOp") record CancelOp() implements OpsRequest {}

    sealed interface PhotosRequest extends Request {}

    /** {@code rating}/{@code lists} null = unchanged. */
    @JsonTypeName("markPhoto") record MarkPhoto(String albumId, String name, Integer rating, List<Integer> lists) implements PhotosRequest {}

    @JsonTypeName("photos") record Photos(int listId, int offset, int limit) implements PhotosRequest {}

    @JsonTypeName("photoMeta") record PhotoMeta(String albumId, int index) implements PhotosRequest {}

    @JsonTypeName("albumText") record AlbumText(String albumId, String name) implements PhotosRequest {}
}
