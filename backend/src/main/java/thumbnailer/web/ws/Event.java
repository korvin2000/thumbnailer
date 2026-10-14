package thumbnailer.web.ws;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
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

    /** {@code renderEpoch}: hex hash of the covers and thumbs render epochs, appended to image URLs as {@code &r=}. */
    @JsonTypeName("settings") record SettingsInfo(Settings settings, String dataDir, List<RootStatus> roots, String renderEpoch)
            implements Event {}

    @JsonTypeName("facets") record FacetCounts(@JsonUnwrapped Facets facets) implements Event {}

    @JsonTypeName("ok") record Ok() implements Event {}

    @JsonTypeName("error") record Error(String message) implements Event {}

    /** {@code location}: where the root is on disk after the path map; {@code exists} tests it. */
    record RootStatus(String path, String location, boolean exists, int albumCount, Instant lastScanAt) {}

    @JsonTypeName("folders") record FolderList(String path, List<AlbumRepository.Folder> folders) implements Event {}

    /** A directory for the folder picker; {@code error} when the asked path could not be opened (then a fallback). */
    @JsonTypeName("listing") record Listing(String path, String parent, List<String> drives, List<Entry> folders, String error)
            implements Event {
        record Entry(String name, String path) {}
    }

    /** {@code pinned}: {@code dir} was forced from outside, so a switch takes no effect. */
    @JsonTypeName("libraryInfo") record LibraryInfo(String home, String dir, List<String> known, boolean pinned,
                                                    LibraryStats stats, About about) implements Event {}

    /** Covers = cover store, metadata = database; live = in use, file = on disk (estimates). */
    record LibraryStats(int albums, long images, long imageBytes, Instant lastIndexedAt, long coversCount, long coversBytes,
                        long coversFile, long metadataBytes, long metadataFile, long settingsBytes) {}

    record About(String version, String java, String os) {}
}
