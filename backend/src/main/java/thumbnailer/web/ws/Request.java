package thumbnailer.web.ws;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.AlbumQuery;
import thumbnailer.settings.Settings;

/** Client to server messages; the client-chosen {@code id} is handled by {@link AlbumSocket}'s envelope. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Request {

    @JsonTypeName("search") record Search(AlbumQuery query) implements Request {}

    @JsonTypeName("album") record Open(String albumId) implements Request {}

    @JsonTypeName("update") record Update(String albumId, AlbumPatch patch) implements Request {}

    @JsonTypeName("scan") record Scan(List<String> roots) implements Request {}

    @JsonTypeName("cancelScan") record CancelScan() implements Request {}

    @JsonTypeName("getSettings") record GetSettings() implements Request {}

    @JsonTypeName("setSettings") record SetSettings(Settings settings) implements Request {}

    @JsonTypeName("facets") record FacetsOf(AlbumQuery query) implements Request {}

    @JsonTypeName("reveal") record Reveal(String albumId) implements Request {}
}
