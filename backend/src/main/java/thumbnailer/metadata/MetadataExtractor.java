package thumbnailer.metadata;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.Entry;
import thumbnailer.source.AlbumContainer;

/**
 * SPI: a partial {@link AlbumMeta} read from an album. Implementations are Spring beans ordered with {@code @Order};
 * {@link Metadata} merges them so that an earlier extractor's non-blank field wins and tags are unioned.
 */
public interface MetadataExtractor {

    Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    AlbumMeta extract(AlbumContainer container, List<Entry> entries) throws IOException;

    /** The first valid {@code yyyy-MM-dd} in {@code text}, if any. */
    static LocalDate date(String text) {
        if (text == null) return null;
        for (var m = ISO_DATE.matcher(text); m.find(); ) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (DateTimeException e) {
                // not a calendar date, keep looking
            }
        }
        return null;
    }
}
