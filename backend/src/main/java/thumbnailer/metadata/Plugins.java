package thumbnailer.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** An SPI chain: the Spring beans plus the {@link ServiceLoader} providers of the class path not already among them, by order. */
public final class Plugins {

    private static final Logger log = LoggerFactory.getLogger(Plugins.class);

    private Plugins() {}

    /**
     * A provider that cannot be loaded, constructed or ordered is skipped with one warning; {@code order} is asked once
     * per element; ties keep beans first, then discovery order.
     */
    public static <T> List<T> load(Class<T> spi, Collection<? extends T> beans, ToIntFunction<T> order) {
        var chain = new ArrayList<T>(beans);
        var known = new HashSet<Class<?>>();
        beans.forEach(b -> known.add(b.getClass()));
        var providers = ServiceLoader.load(spi, spi.getClassLoader()).iterator();
        for (int failures = 0; failures < 64; ) {
            try {
                if (!providers.hasNext()) break;
                var p = providers.next();
                if (known.add(p.getClass())) chain.add(p);
            } catch (ServiceConfigurationError e) {
                failures++;
                log.warn("Skipping a {} provider: {}", spi.getSimpleName(), e.getMessage());
            }
        }
        var ordered = new ArrayList<Map.Entry<T, Integer>>();
        for (var e : chain)
            try {
                ordered.add(Map.entry(e, order.applyAsInt(e)));
            } catch (RuntimeException x) {
                log.warn("Skipping a {} provider: {}.order() failed: {}", spi.getSimpleName(), e.getClass().getName(), x.toString());
            }
        return ordered.stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList(); // a stable sort
    }
}
