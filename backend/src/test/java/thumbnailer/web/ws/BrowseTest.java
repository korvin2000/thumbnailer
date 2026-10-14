package thumbnailer.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.source.Containers;

class BrowseTest {

    @TempDir Path dir;

    @Test
    void listsSubfoldersAndFallsBackToTheNearestFolder() throws IOException {
        for (var d : new String[] {"b", "A", "c/d"}) Files.createDirectories(dir.resolve(d));
        Files.writeString(dir.resolve("f.txt"), "x");
        var at = Containers.normalize(dir);

        var listing = Browse.list(dir.toString());
        assertThat(listing.path()).isEqualTo(at);
        assertThat(listing.parent()).isEqualTo(Containers.normalize(dir.getParent()));
        assertThat(listing.folders()).containsExactly(new Event.Listing.Entry("A", at + "/A"),
                new Event.Listing.Entry("b", at + "/b"), new Event.Listing.Entry("c", at + "/c"));
        assertThat(listing.drives()).isNotEmpty().allMatch(d -> d.endsWith("/"));
        assertThat(listing.error()).isNull();

        assertThat(Browse.list(dir.resolve("f.txt").toString()).path()).as("a file: its folder").isEqualTo(at);
        var missing = Browse.list(dir.resolve("gone/deeper").toString());
        assertThat(missing.path()).isEqualTo(at);
        assertThat(missing.error()).startsWith("Cannot open ");
        assertThat(Browse.list(null).path()).isEqualTo(Containers.normalize(Path.of(System.getProperty("user.home"))));
    }
}
