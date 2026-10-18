package thumbnailer.metadata;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.Entry;
import thumbnailer.source.AlbumContainer;

/** The ordered extractor chain; a failing extractor is skipped, never fatal for the album. */
@Component
public class Metadata {

    private static final Logger log = LoggerFactory.getLogger(Metadata.class);

    private final List<MetadataExtractor> extractors;

    public Metadata(List<MetadataExtractor> extractors) { this.extractors = List.copyOf(extractors); }

    public AlbumMeta extract(AlbumContainer container, List<Entry> entries) {
        var meta = AlbumMeta.EMPTY;
        for (var extractor : extractors) {
            try {
                meta = meta.orElse(extractor.extract(container, entries));
            } catch (IOException | RuntimeException e) {
                log.warn("{} failed on {}: {}", extractor.getClass().getSimpleName(), container.name(), e.toString());
            }
        }
        return meta.orElse(new AlbumMeta(container.name(), null, null, null, null, null, null, null));
    }
}
