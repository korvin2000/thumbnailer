package thumbnailer.spi;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * SPI: a partial {@link AlbumMeta} read from an album's {@link Extraction}. Implementations are Spring beans or
 * {@code META-INF/services} providers (public, no-argument constructor); {@code Metadata} runs them by {@link #order}
 * and merges them so that an earlier extractor's non-blank field wins and tags and genres are unioned.
 */
public interface MetadataExtractor {

    Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    AlbumMeta extract(Extraction ctx) throws IOException;

    default int order() { return 100; }

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
