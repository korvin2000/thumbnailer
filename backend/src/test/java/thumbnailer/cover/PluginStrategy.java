package thumbnailer.cover;

import java.util.Optional;
import thumbnailer.album.Entry;
import thumbnailer.metadata.Extraction;

/** A class-path provider (test resources' {@code META-INF/services}) after the built-ins: the sample image. */
public class PluginStrategy implements CoverStrategy {

    @Override public int order() { return 50; }

    @Override
    public Optional<Entry> pick(Extraction ctx) { return ctx.images().stream().filter(e -> e.name().equals(ctx.sampleImage())).findFirst(); }
}
