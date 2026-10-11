package thumbnailer.metadata;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.Entry;
import thumbnailer.image.ImageProbe;
import thumbnailer.source.AlbumContainer;

/** Earliest {@code DateTimeOriginal} (already probed per entry) and the first image's {@code Artist}. */
@Component
@Order(3)
class ExifExtractor implements MetadataExtractor {

    @Override
    public AlbumMeta extract(AlbumContainer container, List<Entry> entries) throws IOException {
        var date = entries.stream().map(Entry::shotAt).filter(Objects::nonNull).min(Comparator.naturalOrder())
                .map(LocalDateTime::toLocalDate).orElse(null);
        String artist = null;
        if (!entries.isEmpty()) {
            try (var in = container.open(entries.getFirst().name())) {
                artist = ImageProbe.exif(in).artist();
            }
        }
        return new AlbumMeta(null, null, null, artist, null, date, null);
    }
}
