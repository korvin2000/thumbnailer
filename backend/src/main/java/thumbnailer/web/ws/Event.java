package thumbnailer.web.ws;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.Facets;
import thumbnailer.catalog.Card;
import thumbnailer.catalog.Entity;
import thumbnailer.catalog.EntityKind;
import thumbnailer.catalog.PhotoMark;
import thumbnailer.index.ScanProgress;
import thumbnailer.ops.OpProgress;
import thumbnailer.ops.OpResult;
import thumbnailer.ops.Preflight;
import thumbnailer.settings.Settings;
import thumbnailer.source.Disks;
import thumbnailer.spi.Entry;

/** Server to client messages: replies (carrying the request {@code id}) and broadcasts. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface Event {

    @JsonTypeName("results") record Results(List<Album> albums, int total) implements Event {}

    /** {@code marks}: the album's marked photos by entry name. */
    @JsonTypeName("albumDetails") record AlbumDetails(Album album, List<Entry> entries, Map<String, PhotoMark> marks) implements Event {}

    @JsonTypeName("albumChanged") record AlbumChanged(Album album) implements Event {}

    @JsonTypeName("albumRemoved") record AlbumRemoved(String albumId) implements Event {}

    @JsonTypeName("scanProgress") record Progress(@JsonUnwrapped ScanProgress progress) implements Event {}

    /**
     * Each session's first broadcast: {@code library} (as {@code SettingsInfo.dataDir}) tells a reconnecting client
     * whether the server now serves another one; {@code scan} is the last scan progress, {@code op} the last operation
     * progress with no result after it, {@code opResult} the last result (each null when none this process).
     */
    @JsonTypeName("hello") record Hello(String library, ScanProgress scan, OpProgress op, OpResult opResult) implements Event {}

    /** {@code renderEpoch}: hex hash of the covers and thumbs render epochs, appended to image URLs as {@code &r=}. */
    @JsonTypeName("settings") record SettingsInfo(Settings settings, String dataDir, List<RootStatus> roots, String renderEpoch)
            implements Event {}

    @JsonTypeName("facets") record FacetCounts(@JsonUnwrapped Facets facets) implements Event {}

    /** {@code count}: how many albums a bulk request changed. */
    @JsonTypeName("ok") record Ok(@JsonInclude(NON_NULL) Integer count) implements Event {}

    @JsonTypeName("error") record Error(String message) implements Event {}

    /**
     * {@code location}: where the root is on disk after the path map; {@code exists} tests it; {@code storage}: how its
     * disk is read (SSD or HDD), detected or forced by the root's setting.
     */
    record RootStatus(String path, String location, boolean exists, int albumCount, Instant lastScanAt, Disks.Storage storage) {}

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

    /** {@code total}: entities matching, ignoring offset and limit. */
    @JsonTypeName("entityList") record EntityList(List<Entity> items, int total) implements Event {}

    @JsonTypeName("entityCard") record EntityCard(@JsonUnwrapped Card card) implements Event {}

    /** Broadcast: views showing entities of these kinds (grid, viewer, trees, facets, open cards) reload. */
    @JsonTypeName("catalogChanged") record CatalogChanged(List<EntityKind> kinds) implements Event {}

    @JsonTypeName("opAccepted") record OpAccepted(String opId) implements Event {}

    @JsonTypeName("opProgress") record OpProgressEvent(@JsonUnwrapped OpProgress progress) implements Event {}

    @JsonTypeName("opResult") record OpResultEvent(@JsonUnwrapped OpResult result) implements Event {}

    @JsonTypeName("photoMarks") record PhotoMarks(String albumId, Map<String, PhotoMark> marks) implements Event {}

    @JsonTypeName("photoList") record PhotoList(List<Photo> items, int total) implements Event {
        record Photo(String albumId, String name, int index, int rating, AlbumRef album) {}

        record AlbumRef(String id, String name, long revision) {}
    }

    @JsonTypeName("photoMeta") record PhotoMetaRows(List<Row> rows) implements Event {
        record Row(String dir, String tag, String value) {}
    }

    @JsonTypeName("text") record Text(String name, String text) implements Event {}

    @JsonTypeName("copyList") record CopyList(List<Album> albums) implements Event {}

    @JsonTypeName("comparison") record Comparison(boolean identical, List<String> differing) implements Event {}

    @JsonTypeName("preflight") record PreflightEvent(@JsonUnwrapped Preflight preflight) implements Event {}
}
