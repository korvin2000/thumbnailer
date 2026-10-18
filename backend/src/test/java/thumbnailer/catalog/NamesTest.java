package thumbnailer.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void parsesSeparatorsQuotesAndBlanks() {
        assertThat(Names.parse(" Ellena ,Aurora; ;Mira ")).containsExactly("Ellena", "Aurora", "Mira");
        assertThat(Names.parse("\"Smith, John\"; Art & Soul")).containsExactly("Smith, John", "Art & Soul");
        assertThat(Names.parse("\"Ann \"\"Bee\"\" Cee\", x")).containsExactly("Ann \"Bee\" Cee", "x");
        assertThat(Names.parse(null)).isEmpty();
        assertThat(Names.parse(" , ;")).isEmpty();
    }

    @Test
    void joinQuotesOnlyWhatParseWouldSplit() {
        var names = List.of("Ellena", "Smith, John", "a;b", "Ann \"Bee\" Cee", "Black and White");
        assertThat(Names.join(names)).isEqualTo("Ellena, \"Smith, John\", \"a;b\", \"Ann \"\"Bee\"\" Cee\", Black and White");
        assertThat(Names.parse(Names.join(names))).isEqualTo(names);
        assertThat(Names.join(List.of())).isEmpty();
    }

    @Test
    void normalizesPerKind() {
        assertThat(Catalog.norm(EntityKind.MODEL, "  Ariell   van\tHoover ")).isEqualTo("ariell van hoover");
        assertThat(Catalog.norm(EntityKind.TAG, "#Best Of")).isEqualTo("best-of");
        assertThat(Catalog.norm(EntityKind.STUDIO, "x".repeat(300))).hasSize(256);
    }
}
