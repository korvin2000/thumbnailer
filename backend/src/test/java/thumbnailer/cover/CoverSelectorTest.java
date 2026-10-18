package thumbnailer.cover;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import thumbnailer.album.Entry;

class CoverSelectorTest {

    private final CoverSelector selector = new CoverSelector();

    @Test
    void nameHintsWinOverSmallest() {
        for (var hint : List.of("Cover.jpg", "studio_poster.png", "logo.webp", "x_cv.jpg")) {
            var entries = List.of(entry(0, "001.jpg", 100), entry(1, hint, 900), entry(2, "zzz.jpg", 50));
            assertThat(first(entries, null)).as(hint).isEqualTo(hint);
        }
    }

    @Test
    void smallestByBytesOtherwise() {
        var entries = List.of(entry(0, "001.jpg", 300), entry(1, "002.jpg", 100), entry(2, "cv.jpg", 200));
        assertThat(first(entries, null)).isEqualTo("002.jpg");
    }

    @Test
    void pinnedCoverWinsWhileItExists() {
        var entries = List.of(entry(0, "001.jpg", 300), entry(1, "002.jpg", 100));
        assertThat(first(entries, "001.jpg")).isEqualTo("001.jpg");
        assertThat(first(entries, "gone.jpg")).isEqualTo("002.jpg");
        assertThat(selector.candidates(entries, "001.jpg").map(Entry::name)).containsExactly("001.jpg", "002.jpg");
    }

    private String first(List<Entry> entries, String pinned) {
        return selector.candidates(entries, pinned).findFirst().orElseThrow().name();
    }

    private static Entry entry(int index, String name, long bytes) {
        return new Entry(index, name, bytes, 0, 100, 100, null, 0);
    }
}
