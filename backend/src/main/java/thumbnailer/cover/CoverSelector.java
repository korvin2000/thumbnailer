package thumbnailer.cover;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import thumbnailer.album.Entry;

/** Orders cover candidates: a pinned cover that still exists, then each strategy's pick, then every other entry. */
@Component
public class CoverSelector {

    private final List<CoverStrategy> chain = List.of(CoverStrategy.NAME_HINT, CoverStrategy.SMALLEST);

    /** Lazy, so callers that can decode the first candidate never evaluate the rest. */
    public Stream<Entry> candidates(List<Entry> entries, String pinned) {
        return Stream.of(entries.stream().filter(e -> e.name().equals(pinned)),
                        chain.stream().flatMap(s -> s.pick(entries).stream()), entries.stream())
                .flatMap(Function.identity()).distinct();
    }
}
