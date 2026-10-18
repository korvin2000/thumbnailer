package thumbnailer.cover;

import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Component;
import thumbnailer.album.Entry;
import thumbnailer.metadata.Extraction;

/** The smallest file: usually a prepared cover or a preview, and the cheapest decode. */
@Component
public class Smallest implements CoverStrategy {

    @Override public int order() { return 2; }

    @Override
    public Optional<Entry> pick(Extraction ctx) { return ctx.images().stream().min(Comparator.comparingLong(Entry::bytes)); }
}
