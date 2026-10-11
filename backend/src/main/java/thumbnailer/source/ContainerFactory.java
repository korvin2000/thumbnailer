package thumbnailer.source;

import java.io.IOException;
import java.nio.file.Path;

/** SPI: one container format. Implementations are Spring beans; a new archive format (7z, rar) is one more bean. */
public interface ContainerFactory {

    Kind kind();

    /** Whether {@code file}, a regular file met during discovery, is an archive of this format. */
    boolean accepts(Path file);

    /** Opens the container; {@code exclusions} hide matching files from {@link AlbumContainer#items()}. */
    AlbumContainer open(Path path, Exclusions exclusions) throws IOException;
}
