package thumbnailer.cover;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import thumbnailer.album.Entry;

/** SPI: proposes a cover among an album's entries; {@link CoverSelector} runs the chain in order. */
@FunctionalInterface
public interface CoverStrategy {

    Optional<Entry> pick(List<Entry> entries);
    //board, folder, *_ch, 0.jpg, logo, icon, covet
    Pattern HINT = Pattern.compile("(?i)cover|poster|logo|_cv\\.[^.]+$");

    /** A file name announcing a cover: cover, poster, logo, or a {@code _cv} suffix. */
    CoverStrategy NAME_HINT = entries -> entries.stream()
            .filter(e -> HINT.matcher(e.name().substring(e.name().lastIndexOf('/') + 1)).find()).findFirst();

    CoverStrategy SMALLEST = entries -> entries.stream().min(Comparator.comparingLong(Entry::bytes));
}
