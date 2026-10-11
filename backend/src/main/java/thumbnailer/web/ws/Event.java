package thumbnailer.web.ws;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import thumbnailer.album.Album;
import thumbnailer.album.Entry;
import thumbnailer.album.Facets;
import thumbnailer.index.ScanProgress;
import thumbnailer.settings.Settings;

/** Server to client messages: replies (carrying the request {@code id}) and broadcasts. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Event {

    @JsonTypeName("results") record Results(List<Album> albums, int total) implements Event {}

    @JsonTypeName("albumDetails") record AlbumDetails(Album album, List<Entry> entries) implements Event {}

    @JsonTypeName("albumChanged") record AlbumChanged(Album album) implements Event {}

    @JsonTypeName("albumRemoved") record AlbumRemoved(String albumId) implements Event {}

    @JsonTypeName("scanProgress") record Progress(@JsonUnwrapped ScanProgress progress) implements Event {}

    @JsonTypeName("settings") record SettingsInfo(Settings settings, String dataDir, List<RootStatus> roots) implements Event {}

    @JsonTypeName("facets") record FacetCounts(@JsonUnwrapped Facets facets) implements Event {}

    @JsonTypeName("ok") record Ok() implements Event {}

    @JsonTypeName("error") record Error(String message) implements Event {}

    record RootStatus(String path, boolean exists, int albumCount, Instant lastScanAt) {}
}
