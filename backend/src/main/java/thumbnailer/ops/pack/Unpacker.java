package thumbnailer.ops.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import thumbnailer.source.AlbumContainer;

/** Extracts archives for unpack (catalog_plan.md §4.5, track B3). */
public interface Unpacker {

    /**
     * Writes every file of {@code raw} below {@code dir}, renamed by {@code nameMap} (old → new, e.g. a flattened top
     * directory); returns the hashes of the written files, read back.
     */
    Map<String, Long> unpack(AlbumContainer raw, Path dir, Map<String, String> nameMap) throws IOException;
}
