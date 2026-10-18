package thumbnailer.metadata;

import thumbnailer.album.AlbumMeta;

/** A provider that cannot be constructed: skipped with a warning. */
public class BrokenExtractor implements MetadataExtractor {

    public BrokenExtractor() { throw new IllegalStateException("broken on purpose"); }

    @Override public AlbumMeta extract(Extraction ctx) { return AlbumMeta.EMPTY; }
}
