package thumbnailer.cover;

import java.util.Optional;
import thumbnailer.album.Entry;
import thumbnailer.metadata.Extraction;

/**
 * SPI: proposes a cover among an album's images. Implementations are Spring beans or {@code META-INF/services}
 * providers (public, no-argument constructor); {@link CoverSelector} runs them by {@link #order}. Outside indexing
 * the context holds the entries only ({@link Extraction#of}): a strategy that needs the container checks for null.
 */
public interface CoverStrategy {

    Optional<Entry> pick(Extraction ctx);

    default int order() { return 100; }
}
