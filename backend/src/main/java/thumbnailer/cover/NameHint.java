package thumbnailer.cover;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import thumbnailer.album.Entry;
import thumbnailer.metadata.Extraction;

/** A file name announcing a cover: cover, poster, logo, or a {@code _cv} suffix. */
@Component
public class NameHint implements CoverStrategy {

    //board, folder, *_ch, 0.jpg, logo, icon, covet
    static final Pattern HINT = Pattern.compile("(?i)cover|poster|logo|_cv\\.[^.]+$");

    @Override public int order() { return 1; }

    @Override
    public Optional<Entry> pick(Extraction ctx) {
        return ctx.images().stream().filter(e -> HINT.matcher(e.name().substring(e.name().lastIndexOf('/') + 1)).find()).findFirst();
    }
}
