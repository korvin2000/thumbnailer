package thumbnailer.source;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import net.sf.sevenzipjbinding.ArchiveFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SevenZipContainerTest {

    @TempDir Path dir;

    /** Incompressible "images" larger than a head, a nested one, a long tar name, a small sidecar. */
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    {
        var rnd = new SplittableRandom(3);
        for (var n : List.of("b.jpg", "a.jpg", "raw/c.jpg", "x".repeat(120) + "/long.jpg")) {
            var b = new byte[300_000 + rnd.nextInt(1000)];
            rnd.nextBytes(b);
            files.put(n, b);
        }
        files.put("album.txt", "Model: Anna".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everyFormatListsHeadsAndReads() throws IOException {
        byte[] tar = Archives.tar(files);
        Files.write(dir.resolve("t.tar"), tar);
        Files.write(dir.resolve("t.tar.gz"), Archives.gzip(tar));
        Archives.sevenZip(dir.resolve("t.tar.xz"), ArchiveFormat.XZ, Map.of("t.tar", tar), false);
        Archives.sevenZip(dir.resolve("t.tar.bz2"), ArchiveFormat.BZIP2, Map.of("t.tar", tar), false);
        Archives.sevenZip(dir.resolve("solid.7z"), ArchiveFormat.SEVEN_ZIP, files, true);
        Archives.sevenZip(dir.resolve("plain.7z"), ArchiveFormat.SEVEN_ZIP, files, false);
        for (var name : List.of("t.tar", "t.tar.gz", "t.tar.xz", "t.tar.bz2", "solid.7z", "plain.7z")) {
            try (var c = new SevenZipContainer(dir.resolve(name), Exclusions.NONE); var arena = Arena.ofConfined()) {
                assertThat(c.items()).as(name).extracting(AlbumContainer.Item::name)
                        .containsExactly("a.jpg", "album.txt", "b.jpg", "raw/c.jpg", "x".repeat(120) + "/long.jpg");
                assertThat(c.items()).as(name).allMatch(i -> i.bytes() == files.get(i.name()).length);
                assertThat(c.head("b.jpg", 1000)).as(name).isEqualTo(slice(files.get("b.jpg"), 1000));
                assertThat(c.read("raw/c.jpg", arena).toArray(JAVA_BYTE)).as(name).isEqualTo(files.get("raw/c.jpg"));
                assertThat(c.open("album.txt").readAllBytes()).as(name).isEqualTo(files.get("album.txt"));
                var heads = new LinkedHashMap<String, byte[]>();
                c.heads(List.of("a.jpg", "b.jpg", "raw/c.jpg", "album.txt"), 4096, heads::put);
                assertThat(heads).as(name).hasSize(4);
                heads.forEach((n, h) -> assertThat(h).as(name + " " + n).isEqualTo(slice(files.get(n), 4096)));
                assertThatThrownBy(() -> c.head("missing.jpg", 10)).isInstanceOf(FileNotFoundException.class);
            }
        }
    }

    @Test
    void exclusionsHideEntriesAndTheSignatureIsTheArchiveStat() throws IOException {
        var file = dir.resolve("e.7z");
        Archives.sevenZip(file, ArchiveFormat.SEVEN_ZIP, files, true);
        var exclusions = Exclusions.of(List.of("*/raw/*"));
        try (var c = new SevenZipContainer(file, exclusions)) {
            String before = c.signature();
            assertThat(c.items()).extracting(AlbumContainer.Item::name).doesNotContain("raw/c.jpg");
            assertThat(new SevenZipContainer(file, Exclusions.NONE).signature()).as("globs count").isNotEqualTo(before);
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
            assertThat(new SevenZipContainer(file, exclusions).signature()).as("touched").isNotEqualTo(before);
        }
        assertThat(new SevenZipContainer.Factory().accepts(Path.of("x.tar.gz"))).isTrue();
        assertThat(new SevenZipContainer.Factory().accepts(Path.of("x.zip"))).isFalse();
        assertThat(new SevenZipContainer(Path.of("Shoot 01.tar.bz2"), Exclusions.NONE).name()).isEqualTo("Shoot 01");
    }

    private static byte[] slice(byte[] b, int n) { return java.util.Arrays.copyOf(b, Math.min(n, b.length)); }
}
