package thumbnailer.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import thumbnailer.source.AlbumContainer;

class CatalogApiTest {

    @Test
    void aNameListedTwiceInAContainerNeverComparesIdentical() throws Exception {
        var twice = container(List.of("a.jpg", "b.jpg", "a.jpg"));
        assertThat(CatalogApi.differing(twice, twice)).as("extraction by name reads one of the two").containsExactly("a.jpg");
        assertThat(CatalogApi.differing(container(List.of("a.jpg", "b.jpg")), twice)).containsExactly("a.jpg");
        assertThat(CatalogApi.differing(container(List.of("a.jpg", "b.jpg")), container(List.of("b.jpg", "a.jpg")))).isEmpty();
    }

    /** Every listed file 4 bytes long, its bytes those of its name. */
    private static AlbumContainer container(List<String> names) {
        return new AlbumContainer() {
            @Override public String name() { return "x"; }
            @Override public List<Item> items() { return names.stream().map(n -> new Item(n, 4, 0)).toList(); }
            @Override public InputStream open(String name) { return new ByteArrayInputStream(name.substring(0, 4).getBytes()); }
            @Override public void close() {}
        };
    }
}
