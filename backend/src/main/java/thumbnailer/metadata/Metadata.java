package thumbnailer.metadata;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import thumbnailer.album.AlbumMeta;

/** The extractor chain (beans and class-path providers, by order); a failing extractor is skipped, never fatal for the album. */
@Component
public class Metadata {

    private static final Logger log = LoggerFactory.getLogger(Metadata.class);

    private final List<MetadataExtractor> extractors;

    public Metadata(List<MetadataExtractor> extractors) {
        this.extractors = Plugins.load(MetadataExtractor.class, extractors, MetadataExtractor::order);
    }

    public AlbumMeta extract(Extraction ctx) {
        var meta = AlbumMeta.EMPTY;
        for (var extractor : extractors) {
            try {
                meta = meta.orElse(extractor.extract(ctx));
            } catch (IOException | RuntimeException e) {
                log.warn("{} failed on {}: {}", extractor.getClass().getSimpleName(), ctx.name(), e.toString());
            }
        }
        return meta.orElse(new AlbumMeta(ctx.name(), null, null, null, null, null, null, null));
    }

    List<MetadataExtractor> extractors() { return extractors; }
}
