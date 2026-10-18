package thumbnailer.metadata;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import thumbnailer.album.Entry;
import thumbnailer.source.AlbumContainer.Item;

class ExtractionTest {

    @Test
    void decodesByBomThenStrictUtf8ThenCp1251() {
        var text = "Модель: Анна №1";
        assertThat(Extraction.decode(bytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, text.getBytes(UTF_8)), false)).isEqualTo(text);
        assertThat(Extraction.decode(bytes(new byte[] {(byte) 0xFF, (byte) 0xFE}, text.getBytes(UTF_16LE)), false)).isEqualTo(text);
        assertThat(Extraction.decode(text.getBytes(UTF_8), false)).isEqualTo(text);
        assertThat(Extraction.decode(text.getBytes(Charset.forName("windows-1251")), false)).as("not UTF-8").isEqualTo(text);
        var utf8 = text.getBytes(UTF_8);
        assertThat(Extraction.decode(Arrays.copyOf(utf8, 5), true)).as("cut inside the third letter: dropped").isEqualTo("Мо");
        assertThat(Extraction.decode(Arrays.copyOf(utf8, 4), true)).isEqualTo("Мо");
        assertThat(Extraction.decode(Arrays.copyOf(utf8, 5), false)).as("not a prefix: invalid UTF-8").isNotEqualTo("Мо");
    }

    @Test
    void textBudgetTakesAlbumTxtFirstThenByNameWithinTheLimits() {
        var items = IntStream.range(0, 12).mapToObj(i -> new Item("t" + (char) ('a' + i) + ".txt", 40 << 10, 0)).toList();
        var withSidecar = new java.util.ArrayList<>(items);
        withSidecar.add(new Item("x/Album.TXT", 100 << 10, 0));
        withSidecar.add(new Item("a.jpg", 1 << 20, 0));
        var budget = Extraction.budget(withSidecar);
        assertThat(budget.keySet()).first().isEqualTo("x/Album.TXT");
        assertThat(budget.get("x/Album.TXT")).as("64 KiB each").isEqualTo(Extraction.TEXT_BYTES);
        assertThat(budget).hasSizeLessThanOrEqualTo(Extraction.TEXT_FILES).doesNotContainKey("a.jpg");
        assertThat(budget.values().stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(Extraction.TEXT_TOTAL);
        assertThat(budget.keySet()).containsExactly("x/Album.TXT", "ta.txt", "tb.txt", "tc.txt", "td.txt", "te.txt");
        assertThat(budget.get("te.txt")).as("the total cuts the last one").isEqualTo(Extraction.TEXT_TOTAL - Extraction.TEXT_BYTES - 4 * (40 << 10));
        var small = IntStream.range(0, 10).mapToObj(i -> new Item("s" + i + ".txt", 1000, 0)).toList();
        assertThat(Extraction.budget(small)).as("eight files at most").hasSize(Extraction.TEXT_FILES).containsEntry("s0.txt", 1000)
                .doesNotContainKeys("s8.txt", "s9.txt");
    }

    @Test
    void longestDirAndSampleImage() {
        var items = List.of(new Item("CLS-2019-01-14 - EL-Studio - Ellena/a.jpg", 1, 0), new Item("short/deeper-name/b.jpg", 1, 0),
                new Item("top.jpg", 1, 0));
        assertThat(Extraction.longestDir(items)).isEqualTo("CLS-2019-01-14 - EL-Studio - Ellena");
        assertThat(Extraction.longestDir(List.of(new Item("top.jpg", 1, 0)))).isNull();
        var entries = IntStream.range(0, 5).mapToObj(i -> new Entry(i, i + ".jpg", 1, 0, 1, 1, null, 0)).toList();
        assertThat(Extraction.sample(entries)).isEqualTo("2.jpg");
        assertThat(Extraction.of(entries.subList(0, 4))).extracting(Extraction::sampleImage, Extraction::container, Extraction::texts)
                .containsExactly("2.jpg", null, java.util.Map.of());
        assertThat(Extraction.sample(List.of())).isNull();
    }

    private static byte[] bytes(byte[] a, byte[] b) {
        var r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
