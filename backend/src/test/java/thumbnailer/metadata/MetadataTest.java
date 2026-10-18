package thumbnailer.metadata;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thumbnailer.spi.AlbumContainer;
import thumbnailer.spi.AlbumContainer.Item;
import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Entry;
import thumbnailer.spi.Extraction;
import thumbnailer.spi.Kind;

class MetadataTest {

    @Test
    void chainHoldsBeansAndClassPathProvidersByOrderOnceEach() {
        var bean = new SidecarExtractor();
        var metadata = new Metadata(List.of(bean, new GenreExtractor(new String[] {"Glamour"})));
        assertThat(metadata.extractors()).extracting(e -> e.getClass().getName())
                .as("broken, disordered and missing providers skipped, built-ins and the names plugin jar from META-INF/services, the bean not doubled, beans first on a tie")
                .containsExactly("thumbnailer.metadata.PluginExtractor", "thumbnailer.metadata.SidecarExtractor", "thumbnailer.metadata.GenreExtractor",
                        "thumbnailer.plugins.names.FileNameExtractor", "thumbnailer.metadata.ExifExtractor");
        assertThat(metadata.extractors()).contains(bean);
    }

    @Test
    void extractorsReadTheContextAndAFailingOneIsSkipped() {
        var metadata = new Metadata(List.of());
        var entries = List.of(new Entry(0, "a.jpg", 1, 0, 1, 1, null, 0));
        var items = List.of(new Item("a.jpg", 1, 0), new Item("notes.txt", 30, 0));
        var meta = metadata.extract(ctx("CLS-2019-01-14 - EL-Studio - Ellena", entries, items, Map.of("notes.txt", "model: Anna\n#Set")));
        assertThat(meta).extracting(AlbumMeta::model, AlbumMeta::studio, AlbumMeta::tags).as("sidecar first, then the name")
                .containsExactly("Anna", "EL-Studio", List.of("set", "cls"));
        assertThat(metadata.extract(ctx("plugin-x", entries, items, Map.of())).tags()).containsExactly("plugin");
        assertThat(metadata.extract(ctx("plugin-fails", entries, items, Map.of())).name()).isEqualTo("plugin-fails");
        assertThat(metadata.extract(Extraction.of(entries))).as("no container, no name").isEqualTo(AlbumMeta.EMPTY);
    }

    @Test
    void genresOnlyFromConfiguredWholeWords() {
        var names = new GenreExtractor(new String[] {"Glamour", " art "});
        var entries = List.of(new Entry(0, "a.jpg", 1, 0, 1, 1, null, 0));
        assertThat(names.extract(ctx("CLS-2019-01-14 - EL-Studio - Ellena - glamour", entries, List.of(), Map.of())).genres())
                .containsExactly("Glamour");
        assertThat(names.extract(ctx("CLS-2019-01-14 - Artemis - Party", entries, List.of(), Map.of())).genres()).isNull();
        assertThat(new GenreExtractor(new String[0]).extract(ctx("Glamour set", entries, List.of(), Map.of())).genres()).as("none configured").isNull();
    }

    @Test
    void sidecarFieldsStayOnTheirLineAndAnEmptyOneIsUnset() {
        var meta = SidecarExtractor.parse("description:\nmodel: Anna\r\nname:\n\nstudio: X\nname: Late");
        assertThat(meta).extracting(AlbumMeta::description, AlbumMeta::model, AlbumMeta::studio, AlbumMeta::name)
                .containsExactly(null, "Anna", "X", "Late");
    }

    @Test
    void albumJsonIsReadWithinTheTextLimitElseTheTextSidecarWins() throws Exception {
        var json = "{\"model\":\"Json\"}".getBytes(UTF_8);
        var asked = new ArrayList<Integer>();
        var entries = List.of(new Entry(0, "a.jpg", 1, 0, 1, 1, null, 0));
        for (long declared : new long[] {json.length, Extraction.TEXT_BYTES + 1L}) {
            asked.clear();
            var items = List.of(new Item("a.jpg", 1, 0), new Item("album.json", declared, 0), new Item("album.txt", 11, 0));
            var container = new AlbumContainer() { // whole reads are not bounded: only head may be used
                @Override public String name() { return "A"; }
                @Override public List<Item> items() { return items; }
                @Override public InputStream open(String name) { throw new UnsupportedOperationException("unbounded read of " + name); }
                @Override public byte[] head(String name, int limit) {
                    asked.add(limit);
                    return Arrays.copyOf(json, Math.min(limit, json.length));
                }
                @Override public void close() {}
            };
            var ctx = new Extraction("A", "/r/A", Kind.DIRECTORY, entries, items, null, "a.jpg", Map.of("album.txt", "model: Anna"), container);
            var model = new SidecarExtractor().extract(ctx).model();
            if (declared == json.length) assertThat(model).isEqualTo("Json");
            else assertThat(model).as("an oversized album.json is skipped").isEqualTo("Anna");
            assertThat(asked).allMatch(limit -> limit <= Extraction.TEXT_BYTES + 1);
        }
    }

    private static Extraction ctx(String name, List<Entry> entries, List<Item> items, Map<String, String> texts) {
        return new Extraction(name, "/r/" + name, Kind.DIRECTORY, entries, items, null, Extraction.sample(entries), texts, null);
    }
}
