package thumbnailer.plugins.names;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FileNameExtractorTest {

    @Test
    void parsesStudioModelAndVolume() {
        var m = FileNameExtractor.parse("CLS-2019-01-14 - EL-Studio - Ellena - New Model - (vol-1) 1800px (x133) 52.3Mb");
        assertThat(m.date()).isEqualTo(LocalDate.of(2019, 1, 14));
        assertThat(m.studio()).isEqualTo("EL-Studio");
        assertThat(m.model()).isEqualTo("Ellena");
        assertThat(m.tags()).containsExactly("cls");
        assertThat(m.description()).contains("New Model", "vol-1");
        assertThat(m.name()).isEqualTo("CLS-2019-01-14 - EL-Studio - Ellena - New Model - (vol-1)");
    }

    @Test
    void modelWithoutStudio() {
        var m = FileNameExtractor.parse("CLS-2019-01-14 - Aurora - (vol-5) 1800px (x148) 66.7Mb");
        assertThat(m.model()).isEqualTo("Aurora");
        assertThat(m.studio()).isNull();
        assertThat(m.description()).isEqualTo("vol-5");
    }

    @Test
    void bareVolumeMarker() {
        var m = FileNameExtractor.parse("CLS-2019-09-30 - EL-Studio - Jeny - VOL-27 1800px (x142) 26.3Mb");
        assertThat(m.model()).isEqualTo("Jeny");
        assertThat(m.studio()).isEqualTo("EL-Studio");
        assertThat(m.description()).isEqualTo("vol-27");
    }

    @Test
    void plainNameYieldsOnlyAName() {
        var m = FileNameExtractor.parse("unpacked");
        assertThat(m.name()).isEqualTo("unpacked");
        assertThat(m.model()).isNull();
        assertThat(m.date()).isNull();
    }
}
