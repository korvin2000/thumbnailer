package thumbnailer.album;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One metadata layer. A null field is "not set"; in the edited layer {@code ""} and {@code []} are explicit empties.
 * Tags are normalized to lower case without {@code #}.
 */
public record AlbumMeta(String name, String model, String studio, String photographer, String description,
                        LocalDate date, List<String> tags) {

    public static final AlbumMeta EMPTY = new AlbumMeta(null, null, null, null, null, null, null);

    public AlbumMeta {
        tags = tags == null ? null : tags.stream().filter(Objects::nonNull).map(AlbumMeta::tag)
                .filter(t -> !t.isEmpty()).distinct().toList();
    }

    public static String tag(String raw) {
        return raw.strip().replaceFirst("^#+", "").replaceAll("\\s+", "-").toLowerCase(Locale.ROOT);
    }

    /** Field by field, this layer's set values win: edited over extracted, a patch over edited. */
    public AlbumMeta over(AlbumMeta base) {
        return new AlbumMeta(or(name, base.name), or(model, base.model), or(studio, base.studio),
                or(photographer, base.photographer), or(description, base.description), or(date, base.date),
                or(tags, base.tags));
    }

    /** Composite extraction: this extractor's non-blank values win, tags are unioned. */
    public AlbumMeta orElse(AlbumMeta later) {
        return new AlbumMeta(text(name, later.name), text(model, later.model), text(studio, later.studio),
                text(photographer, later.photographer), text(description, later.description), or(date, later.date),
                tags == null || later.tags == null ? or(tags, later.tags)
                        : Stream.concat(tags.stream(), later.tags.stream()).toList());
    }

    /** What the columns and the wire carry: explicit empties become absent. */
    AlbumMeta effective() {
        return new AlbumMeta(blankToNull(name), blankToNull(model), blankToNull(studio), blankToNull(photographer),
                blankToNull(description), date, tags == null ? List.of() : tags);
    }

    private static <T> T or(T value, T fallback) { return value != null ? value : fallback; }

    private static String text(String value, String fallback) { return blankToNull(value) != null ? value : fallback; }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
