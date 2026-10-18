package thumbnailer.ops.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import thumbnailer.ops.Hashes;
import thumbnailer.ops.Op;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Containers;
import thumbnailer.source.Kind;

/** The {@link Packer}: {@code AUTO} and {@code ZIP} through {@link ZipPacker}, {@code SEVEN_ZIP} through {@link SevenZipPacker}. */
@Component
public class Packers implements Packer {

    private static final byte[] ZIP = {'P', 'K', 3, 4}, EMPTY_ZIP = {'P', 'K', 5, 6};

    private final Containers containers;

    public Packers(Containers containers) { this.containers = containers; }

    /**
     * {@code format} null = {@code AUTO}. {@code target} is created: one already there fails ({@code FileAlreadyExistsException},
     * left as it is); any other failure leaves a partial {@code target} for the caller to delete.
     */
    @Override
    public Map<String, Long> pack(AlbumContainer raw, Collection<String> names, Path target, Op.Format format) throws IOException {
        return format == Op.Format.SEVEN_ZIP ? SevenZipPacker.pack(raw, names, target) : ZipPacker.pack(raw, names, target);
    }

    /** Reopens {@code archive} raw, whatever its name ({@code X.zip.part}): a zip by its magic, else as 7-Zip reads it. */
    @Override
    public Map<String, Long> hashes(Path archive) throws IOException {
        byte[] magic;
        try (var in = Files.newInputStream(archive)) {
            magic = in.readNBytes(4);
        }
        var kind = Arrays.equals(magic, ZIP) || Arrays.equals(magic, EMPTY_ZIP) ? Kind.ZIP : Kind.ARCHIVE;
        try (var c = containers.openRaw(kind, archive)) {
            return hashes(c);
        }
    }

    /** Every file's hash in one {@link AlbumContainer#extract} pass. */
    static Map<String, Long> hashes(AlbumContainer c) throws IOException {
        var hashes = new ConcurrentHashMap<String, Long>();
        c.extract(c.items().stream().map(AlbumContainer.Item::name).toList(), (item, in) -> hashes.put(item.name(), Hashes.of(in)));
        return hashes;
    }
}
