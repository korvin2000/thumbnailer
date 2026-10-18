package thumbnailer.album;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import thumbnailer.album.AlbumRepository.Tombstone;
import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Kind;

/**
 * An album as stored and sent on the wire; {@link #internal()} never leaves the server. {@code hidden}: safe-deleted
 * (out of every default view and count); {@code bio}: entry name of its bio text; {@code links}: its catalog links;
 * {@code copies}: albums sharing its content stamp, itself included (0 = not computed).
 */
public record Album(String id, String root, String path, Kind kind, String name, String cover, boolean coverPinned,
                    long revision, boolean coverPortrait, int imageCount, long bytes, long avgPixels, int maxWidth,
                    int maxHeight, LocalDate shotDate, Instant modifiedAt, Instant indexedAt, String model,
                    String studio, String photographer, String description, List<String> tags, int rating,
                    boolean hidden, String bio, List<String> genres, List<Link> links, int copies, Internal internal) {

    /**
     * Identity across scans ({@code contentId}, {@code signature}), the two metadata layers and the surrogate key of
     * the catalog tables ({@code seq}, 0 = not assigned yet).
     */
    public record Internal(String contentId, String signature, AlbumMeta extracted, AlbumMeta edited, int seq) {}

    /** The content stamp on the wire, for grouping copies. */
    @JsonProperty("stamp")
    public String stamp() { return internal.contentId(); }

    /** Recomputes the effective metadata, {@code edited} over the extracted layer; the shot date is the effective date. */
    public Album withEdits(AlbumMeta edited) {
        var m = edited.over(internal.extracted()).effective();
        return new Album(id, root, path, kind, m.name() != null ? m.name() : internal.extracted().name(), cover,
                coverPinned, revision, coverPortrait, imageCount, bytes, avgPixels, maxWidth, maxHeight, m.date(),
                modifiedAt, indexedAt, m.model(), m.studio(), m.photographer(), m.description(), m.tags(), rating,
                hidden, bio, m.genres(), links, copies,
                new Internal(internal.contentId(), internal.signature(), internal.extracted(), edited, internal.seq()));
    }

    /** Takes over a prior album's user state (stored or removed): id, revision + 1, rating, hidden, seq and edits. */
    public Album adopt(Tombstone prior) {
        return new Album(prior.id(), root, path, kind, name, cover, coverPinned, prior.revision() + 1, coverPortrait,
                imageCount, bytes, avgPixels, maxWidth, maxHeight, shotDate, modifiedAt, indexedAt, model, studio,
                photographer, description, tags, prior.rating(), prior.hidden(), bio, genres, links, copies,
                new Internal(internal.contentId(), internal.signature(), internal.extracted(), internal.edited(), prior.seq()))
                .withEdits(prior.edited());
    }

    public Album with(String id, long revision, int rating) {
        return new Album(id, root, path, kind, name, cover, coverPinned, revision, coverPortrait, imageCount, bytes,
                avgPixels, maxWidth, maxHeight, shotDate, modifiedAt, indexedAt, model, studio, photographer,
                description, tags, rating, hidden, bio, genres, links, copies, internal);
    }

    public Album withHidden(boolean hidden) {
        return new Album(id, root, path, kind, name, cover, coverPinned, revision, coverPortrait, imageCount, bytes,
                avgPixels, maxWidth, maxHeight, shotDate, modifiedAt, indexedAt, model, studio, photographer,
                description, tags, rating, hidden, bio, genres, links, copies, internal);
    }

    public Album withCatalog(List<Link> links, int copies) {
        return new Album(id, root, path, kind, name, cover, coverPinned, revision, coverPortrait, imageCount, bytes,
                avgPixels, maxWidth, maxHeight, shotDate, modifiedAt, indexedAt, model, studio, photographer,
                description, tags, rating, hidden, bio, genres, links, copies, internal);
    }

    public Album withCover(String cover, boolean pinned, boolean portrait) {
        return new Album(id, root, path, kind, name, cover, pinned, revision, portrait, imageCount, bytes, avgPixels,
                maxWidth, maxHeight, shotDate, modifiedAt, indexedAt, model, studio, photographer, description, tags,
                rating, hidden, bio, genres, links, copies, internal);
    }
}
