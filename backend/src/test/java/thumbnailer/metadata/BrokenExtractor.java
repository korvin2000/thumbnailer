package thumbnailer.metadata;

import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Extraction;
import thumbnailer.spi.MetadataExtractor;

/** A provider that cannot be constructed: skipped with a warning. */
public class BrokenExtractor implements MetadataExtractor {

    public BrokenExtractor() { throw new IllegalStateException("broken on purpose"); }

    @Override public AlbumMeta extract(Extraction ctx) { return AlbumMeta.EMPTY; }
}
