package thumbnailer.album;

import static java.util.stream.Collectors.toSet;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import thumbnailer.spi.AlbumMeta;

/**
 * A user edit: null = unchanged, {@code ""}/{@code []} = explicit empty, {@code cover: ""} unpins the cover. {@code date}:
 * {@code YYYY-MM-DD}, {@code YYYY-MM} or {@code YYYY} (stored as the first day), {@code ""} removes the override.
 * {@code addTags}/{@code removeTags} apply to the effective tags (to {@code tags} when that is given too).
 */
public record AlbumPatch(String name, String model, String studio, String photographer, String description,
                         List<String> tags, Integer rating, String cover, String date, List<String> genres,
                         Boolean hidden, List<String> addTags, List<String> removeTags) {

    /** The edited layer after this patch; {@code effectiveTags}: the album's tags now. */
    AlbumMeta edit(AlbumMeta edited, List<String> effectiveTags) {
        var t = tags;
        if (addTags != null || removeTags != null) {
            var drop = Stream.ofNullable(removeTags).flatMap(List::stream).filter(Objects::nonNull).map(AlbumMeta::tag).collect(toSet());
            t = Stream.concat((tags != null ? tags : effectiveTags).stream(), Stream.ofNullable(addTags).flatMap(List::stream))
                    .filter(x -> x != null && !drop.contains(AlbumMeta.tag(x))).toList();
        }
        var m = new AlbumMeta(name, model, studio, photographer, description, null, t, genres).over(edited);
        return date == null ? m : m.withDate(date.isBlank() ? null : day(date.strip()));
    }

    private static LocalDate day(String s) {
        try {
            return switch (s.length()) {
                case 4 -> Year.parse(s).atDay(1);
                case 7 -> YearMonth.parse(s).atDay(1);
                default -> LocalDate.parse(s);
            };
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date must be YYYY-MM-DD, YYYY-MM or YYYY: " + s);
        }
    }
}
