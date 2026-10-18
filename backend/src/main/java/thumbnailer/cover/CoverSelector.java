package thumbnailer.cover;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import thumbnailer.metadata.Plugins;
import thumbnailer.spi.CoverStrategy;
import thumbnailer.spi.Entry;
import thumbnailer.spi.Extraction;

/**
 * Orders cover candidates: a pinned cover that still exists, then each strategy's pick (beans and class-path providers,
 * by order; a failing one is skipped), then every other entry.
 */
@Component
public class CoverSelector {

    private static final Logger log = LoggerFactory.getLogger(CoverSelector.class);

    private final List<CoverStrategy> chain;

    public CoverSelector(List<CoverStrategy> strategies) { chain = Plugins.load(CoverStrategy.class, strategies, CoverStrategy::order); }

    /** From the entries alone ({@link Extraction#of}): saves and patches outside indexing. */
    public Stream<Entry> candidates(List<Entry> entries, String pinned) { return candidates(Extraction.of(entries), pinned); }

    /** Lazy, so callers that can decode the first candidate never evaluate the rest. */
    public Stream<Entry> candidates(Extraction ctx, String pinned) {
        return Stream.of(ctx.images().stream().filter(e -> e.name().equals(pinned)),
                        chain.stream().flatMap(s -> pick(s, ctx).stream()), ctx.images().stream())
                .flatMap(Function.identity()).distinct();
    }

    private static Optional<Entry> pick(CoverStrategy strategy, Extraction ctx) {
        try {
            return strategy.pick(ctx);
        } catch (RuntimeException e) {
            log.warn("{} failed on {}: {}", strategy.getClass().getSimpleName(), ctx.name(), e.toString());
            return Optional.empty();
        }
    }

    List<CoverStrategy> chain() { return chain; }
}
