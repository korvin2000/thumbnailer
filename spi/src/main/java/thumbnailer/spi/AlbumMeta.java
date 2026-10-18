package thumbnailer.spi;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One metadata layer. A null field is "not set"; in the edited layer {@code ""} and {@code []} are explicit empties.
 * Tags are normalized to lower case without {@code #}; genres are names (stripped, distinct).
 */
public record AlbumMeta(String name, String model, String studio, String photographer, String description,
                        LocalDate date, List<String> tags, List<String> genres) {

    public static final AlbumMeta EMPTY = new AlbumMeta(null, null, null, null, null, null, null, null);

    public AlbumMeta {
        tags = tags == null ? null : tags.stream().filter(Objects::nonNull).map(AlbumMeta::tag)
                .filter(t -> !t.isEmpty()).distinct().toList();
        genres = genres == null ? null : genres.stream().filter(Objects::nonNull).map(String::strip)
                .filter(g -> !g.isEmpty()).distinct().toList();
    }

    public static String tag(String raw) {
        return raw.strip().replaceFirst("^#+", "").replaceAll("\\s+", "-").toLowerCase(Locale.ROOT);
    }

    /** Field by field, this layer's set values win: edited over extracted, a patch over edited. */
    public AlbumMeta over(AlbumMeta base) {
        return new AlbumMeta(or(name, base.name), or(model, base.model), or(studio, base.studio),
                or(photographer, base.photographer), or(description, base.description), or(date, base.date),
                or(tags, base.tags), or(genres, base.genres));
    }

    /** Composite extraction: this extractor's non-blank values win, tags and genres are unioned. */
    public AlbumMeta orElse(AlbumMeta later) {
        return new AlbumMeta(text(name, later.name), text(model, later.model), text(studio, later.studio),
                text(photographer, later.photographer), text(description, later.description), or(date, later.date),
                union(tags, later.tags), union(genres, later.genres));
    }

    public AlbumMeta withDate(LocalDate date) {
        return new AlbumMeta(name, model, studio, photographer, description, date, tags, genres);
    }

    /** What the columns and the wire carry: explicit empties become absent. */
    public AlbumMeta effective() {
        return new AlbumMeta(blankToNull(name), blankToNull(model), blankToNull(studio), blankToNull(photographer),
                blankToNull(description), date, tags == null ? List.of() : tags, genres == null ? List.of() : genres);
    }

    private static <T> T or(T value, T fallback) { return value != null ? value : fallback; }

    private static List<String> union(List<String> a, List<String> b) {
        return a == null || b == null ? or(a, b) : Stream.concat(a.stream(), b.stream()).toList();
    }

    private static String text(String value, String fallback) { return blankToNull(value) != null ? value : fallback; }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
