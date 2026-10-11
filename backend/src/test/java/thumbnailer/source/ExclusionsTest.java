package thumbnailer.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExclusionsTest {

    private final Exclusions exclusions = Exclusions.of(List.of("*/raw/*", "*_tmp*", "*/.*"));

    @Test
    void matchesWholeNormalizedPaths() {
        assertThat(exclusions.excludes("C:/albums/shoot/raw/")).as("the raw folder itself").isTrue();
        assertThat(exclusions.excludes("C:/albums/shoot/RAW/001.jpg")).as("case-insensitive").isTrue();
        assertThat(exclusions.excludes("D:/x/set_tmp/")).isTrue();
        assertThat(exclusions.excludes("D:/x/set_tmp.zip")).isTrue();
        assertThat(exclusions.excludes("/home/me/.cache/")).isTrue();
        assertThat(exclusions.excludes("/home/me/pics/.hidden.jpg")).isTrue();

        assertThat(exclusions.excludes("C:/albums/rawhide/")).isFalse();
        assertThat(exclusions.excludes("C:/albums/v1.2/001.jpg")).isFalse();
        assertThat(exclusions.excludes("C:/albums/temp/")).isFalse();
    }

    @Test
    void literalCharactersAreNotRegex() {
        var e = Exclusions.of(List.of("C:/a (1)/*", "  "));
        assertThat(e.excludes("C:/a (1)/x.jpg")).isTrue();
        assertThat(e.excludes("C:/a 1/x.jpg")).isFalse();
    }
}
