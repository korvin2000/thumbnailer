package thumbnailer.ops.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import thumbnailer.ops.Op;
import thumbnailer.spi.AlbumContainer;

/** Writes archives for pack and repack (catalog_plan.md §4.5, track B3). */
public interface Packer {

    /**
     * Packs the named files of {@code raw} into {@code target} in one pass, each entry named exactly as in {@code names};
     * returns, by that name, the {@link thumbnailer.ops.Hashes hash} of the bytes read from {@code raw}.
     */
    Map<String, Long> pack(AlbumContainer raw, Collection<String> names, Path target, Op.Format format) throws IOException;

    /** Every entry's hash, read back from a written archive (the verification). */
    Map<String, Long> hashes(Path archive) throws IOException;
}
