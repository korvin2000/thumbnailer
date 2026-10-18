package thumbnailer.ops.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import thumbnailer.spi.AlbumContainer;

/** Extracts archives for unpack (catalog_plan.md §4.5, track B3). */
public interface Unpacker {

    /**
     * Writes every file of {@code raw} below {@code dir} (created here: one that exists fails), renamed by {@code nameMap} (old → new,
     * e.g. a flattened top directory); returns, by written name ('/'-relative), the hash of the bytes read from the
     * archive. The caller verifies by reading the tree back once it is on the disk: source bytes against written bytes.
     */
    Map<String, Long> unpack(AlbumContainer raw, Path dir, Map<String, String> nameMap) throws IOException;
}
