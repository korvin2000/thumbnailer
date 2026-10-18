package thumbnailer.metadata;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;
import org.springframework.stereotype.Component;
import thumbnailer.image.ImageProbe;
import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Entry;
import thumbnailer.spi.Extraction;
import thumbnailer.spi.MetadataExtractor;

/** Earliest {@code DateTimeOriginal} (already probed per entry) and the first image's {@code Artist} (its head, during indexing). */
@Component
public class ExifExtractor implements MetadataExtractor {

    @Override public int order() { return 3; }

    @Override
    public AlbumMeta extract(Extraction ctx) throws IOException {
        var date = ctx.images().stream().map(Entry::shotAt).filter(Objects::nonNull).min(Comparator.naturalOrder())
                .map(LocalDateTime::toLocalDate).orElse(null);
        String artist = null;
        if (!ctx.images().isEmpty() && ctx.container() != null)
            artist = ImageProbe.exif(ctx.container().head(ctx.images().getFirst().name(), ImageProbe.HEAD)).artist();
        return new AlbumMeta(null, null, null, artist, null, date, null, null);
    }
}
