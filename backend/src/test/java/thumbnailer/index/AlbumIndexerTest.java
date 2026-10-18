package thumbnailer.index;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.sf.sevenzipjbinding.ArchiveFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.image.ImageProbe;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Archives;
import thumbnailer.source.Containers;
import thumbnailer.source.Disks;
import thumbnailer.source.Exclusions;
import thumbnailer.source.Kind;

/** The reads indexing makes: a solid archive's one pass also holds what the built-in extractors read. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlbumIndexerTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var id = UUID.randomUUID();
        registry.add("thumbnailer.data-dir", () -> Path.of("build/test-data", id.toString()).toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + id + ";DB_CLOSE_DELAY=-1");
    }

    @Autowired AlbumIndexer indexer;
    @Autowired Reindexer scans;
    @Autowired AlbumRepository repo;
    @Autowired AlbumService albums;
    @Autowired SettingsStore settings;
    @Autowired Containers containers;
    @TempDir Path root;

    @Test
    void reindexingASolidArchiveReadsAlbumJsonAndTheFirstImageHeadInItsOnePass() throws IOException {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("a.jpg", jpeg(83, 43));
        files.put("album.json", "{\"model\":\"Json\"}".getBytes(UTF_8));
        files.put("b.jpg", jpeg(84, 44));
        Archives.sevenZip(root.resolve("S.7z"), ArchiveFormat.SEVEN_ZIP, files, true);
        settings.set(new Settings(List.of(new Settings.Root(root.toString(), List.of())), List.of(), false, null, null, null));
        scans.scan(null, () -> false);
        var stored = repo.byRoot(Containers.normalize(root)).getFirst();

        try (var in = containers.open(Kind.ARCHIVE, root.resolve("S.7z"), Exclusions.NONE)) {
            var reads = new Reads(in);
            var x = indexer.index(reads, stored.root(), stored.path(), Kind.ARCHIVE, stored, Disks.Hold.NONE,
                    albums.lane(settings.get(), stored.path()), Map.of()).orElseThrow();
            assertThat(x.album().model()).isEqualTo("Json");
            assertThat(reads.passes).as("unchanged probes: the pass holds only the extractors' reads")
                    .containsExactly(List.of("a.jpg", "album.json"));
            assertThat(reads.heads).as("served from what the pass kept").isNotEmpty().allMatch(List.of("a.jpg", "album.json")::contains);
            assertThat(reads.opens).as("no whole read").isZero();
        }
    }

    /** Records the reads that reach a container. */
    private static final class Reads implements AlbumContainer {
        final AlbumContainer in;
        final List<List<String>> passes = new ArrayList<>();
        final List<String> heads = new ArrayList<>();
        int opens;

        Reads(AlbumContainer in) { this.in = in; }

        @Override public String name() { return in.name(); }
        @Override public List<Item> items() { return in.items(); }
        @Override public List<Item> images() { return in.images(); }
        @Override public boolean sequential() { return in.sequential(); }
        @Override public boolean cpuBound() { return in.cpuBound(); }
        @Override public String keyTag() { return in.keyTag(); }
        @Override public String signature() { return in.signature(); }
        @Override public String contentId() { return in.contentId(); }
        @Override public MemorySegment read(String name, Arena arena) throws IOException { return in.read(name, arena); }
        @Override public void close() throws IOException { in.close(); }

        @Override
        public InputStream open(String name) throws IOException {
            opens++;
            return in.open(name);
        }

        @Override
        public byte[] head(String name, int limit) throws IOException {
            synchronized (heads) { heads.add(name); }
            return in.head(name, limit);
        }

        @Override
        public void heads(Collection<String> names, int limit, HeadSink sink) throws IOException {
            if (limit == ImageProbe.HEAD) passes.add(List.copyOf(names));
            in.heads(names, limit, sink);
        }
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }
}
