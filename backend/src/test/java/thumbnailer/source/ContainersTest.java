package thumbnailer.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainersTest {

    @TempDir Path dir;

    @Test
    void zipListsByNameButReadsHeadsInFileOrder() throws IOException {
        var file = dir.resolve("a.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var name : List.of("b.jpg", "a.jpg", "c.jpg")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.getBytes());
            }
        }
        try (var c = new ZipContainer(file, Exclusions.NONE)) {
            assertThat(c.items()).extracting(AlbumContainer.Item::name).containsExactly("a.jpg", "b.jpg", "c.jpg");
            var order = new ArrayList<String>();
            c.heads(List.of("c.jpg", "a.jpg", "b.jpg"), 10, (name, head) -> order.add(name));
            assertThat(order).containsExactly("b.jpg", "a.jpg", "c.jpg");
            assertThat(c.sequential()).isFalse();
        }
    }

    @Test
    void directoryListsItsOwnFilesWithSizesAndSkipsExcludedOnes() throws IOException {
        Files.write(dir.resolve("B.jpg"), new byte[3]);
        Files.write(dir.resolve("a.jpg"), new byte[5]);
        Files.write(dir.resolve("skip.tmp"), new byte[1]);
        Files.createDirectories(dir.resolve("sub")).resolve("c.jpg").toFile().createNewFile();
        var c = new DirectoryContainer(dir, Exclusions.of(List.of("*.tmp")));
        assertThat(c.items()).extracting(i -> i.name() + ":" + i.bytes()).containsExactly("a.jpg:5", "B.jpg:3");
        assertThat(c.items().getFirst().mtime()).isEqualTo(Files.getLastModifiedTime(dir.resolve("a.jpg")).toMillis());
    }
}
