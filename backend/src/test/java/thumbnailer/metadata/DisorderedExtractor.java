package thumbnailer.metadata;

import thumbnailer.album.AlbumMeta;

/** A provider whose {@code order()} fails: skipped with a warning, the chain still builds. */
public class DisorderedExtractor implements MetadataExtractor {

    @Override public int order() { throw new IllegalStateException("no order on purpose"); }

    @Override public AlbumMeta extract(Extraction ctx) { return AlbumMeta.EMPTY; }
}
