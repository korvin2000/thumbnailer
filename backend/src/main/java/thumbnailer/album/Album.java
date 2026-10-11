package thumbnailer.album;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import thumbnailer.source.Kind;

/** An album as stored and sent on the wire; {@link #internal()} never leaves the server. */
public record Album(String id, String root, String path, Kind kind, String name, String cover, boolean coverPinned,
                    long revision, boolean coverPortrait, int imageCount, long bytes, long avgPixels, int maxWidth,
                    int maxHeight, LocalDate shotDate, Instant modifiedAt, Instant indexedAt, String model,
                    String studio, String photographer, String description, List<String> tags, int rating,
                    Internal internal) {

    /** Identity across scans ({@code contentId}, {@code signature}) and the two metadata layers. */
    public record Internal(String contentId, String signature, AlbumMeta extracted, AlbumMeta edited) {}

    /** Recomputes the effective metadata: {@code edited} over the extracted layer. */
    public Album withEdits(AlbumMeta edited) {
        var m = edited.over(internal.extracted()).effective();
        return new Album(id, root, path, kind, m.name() != null ? m.name() : internal.extracted().name(), cover,
                coverPinned, revision, coverPortrait, imageCount, bytes, avgPixels, maxWidth, maxHeight, shotDate,
                modifiedAt, indexedAt, m.model(), m.studio(), m.photographer(), m.description(), m.tags(), rating,
                new Internal(internal.contentId(), internal.signature(), internal.extracted(), edited));
    }

    public Album with(String id, long revision, int rating) {
        return new Album(id, root, path, kind, name, cover, coverPinned, revision, coverPortrait, imageCount, bytes,
                avgPixels, maxWidth, maxHeight, shotDate, modifiedAt, indexedAt, model, studio, photographer,
                description, tags, rating, internal);
    }

    public Album withCover(String cover, boolean pinned, boolean portrait) {
        return new Album(id, root, path, kind, name, cover, pinned, revision, portrait, imageCount, bytes, avgPixels,
                maxWidth, maxHeight, shotDate, modifiedAt, indexedAt, model, studio, photographer, description, tags,
                rating, internal);
    }
}
