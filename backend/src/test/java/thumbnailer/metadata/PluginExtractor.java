package thumbnailer.metadata;

import java.util.List;
import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Extraction;
import thumbnailer.spi.MetadataExtractor;

/** A class-path provider (test resources' {@code META-INF/services}): tags albums named {@code plugin-*}, nothing else. */
public class PluginExtractor implements MetadataExtractor {

    @Override public int order() { return 0; }

    @Override
    public AlbumMeta extract(Extraction ctx) {
        if (ctx.name() == null || !ctx.name().startsWith("plugin-")) return AlbumMeta.EMPTY;
        if (ctx.name().equals("plugin-fails")) throw new IllegalStateException("plugin failure");
        return new AlbumMeta("from plugin", null, null, null, null, null, List.of("plugin"), null);
    }
}
