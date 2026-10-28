package thumbnailer.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import thumbnailer.source.Containers;
import tools.jackson.databind.json.JsonMapper;

class LibrariesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path home;

    @Test
    void switchesAndForgetsLibraries() throws IOException {
        var libraries = new Libraries(home, home, false, JSON);
        var h = Containers.normalize(home);
        var other = home.resolve("other/lib");
        var o = Containers.normalize(other);
        assertThat(libraries.known()).containsExactly(h);
        assertThat(libraries.active()).isEqualTo(h);
        assertThat(libraries.pinned()).isFalse();

        libraries.switchTo(other.toString());
        assertThat(other).isDirectory();
        assertThat(libraries.active()).isEqualTo(o);
        assertThat(libraries.known()).containsExactly(h, o);
        assertThatThrownBy(() -> libraries.forget(o)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> libraries.switchTo("relative/dir")).isInstanceOf(IllegalArgumentException.class);

        var broken = home.resolve("broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("settings.json"), "{ \"roots\": [ { \"path\": \"\" } ] }");
        assertThatThrownBy(() -> libraries.switchTo(broken.toString())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Cannot read " + Containers.normalize(broken) + "/settings.json: ");
        assertThat(libraries.active()).as("unchanged").isEqualTo(o);

        libraries.switchTo(h);
        libraries.forget(o);
        assertThat(libraries.known()).containsExactly(h);

        var pinned = new Libraries(home, other, true, JSON);
        assertThat(pinned.pinned()).isTrue();
        assertThatThrownBy(() -> pinned.switchTo(o)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Library is fixed by --thumbnailer.library-dir");
        assertThatThrownBy(() -> pinned.forget(o)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void locatorSetsTheActiveLibraryOnlyWhenNoneIsGiven() throws IOException {
        var other = Containers.normalize(home.resolve("other"));
        new Libraries(home, home, false, JSON).switchTo(other);
        assertThat(locate(Map.of("thumbnailer.data-dir", home.toString()))).containsExactly(other, "false");
        assertThat(locate(Map.of("thumbnailer.data-dir", home.toString(), "thumbnailer.library-dir", "/pinned")))
                .containsExactly("/pinned", "true");

        Files.writeString(home.resolve(Libraries.FILE), "{ not json");
        assertThat(locate(Map.of("thumbnailer.data-dir", home.toString()))).as("unreadable: ignored").containsExactly(null, "false");
        assertThat(home.resolve(Libraries.FILE + ".unreadable")).as("the unreadable file is kept aside before a save can replace it").hasContent("{ not json");
    }

    /** {@code library-dir}, {@code library-pinned} after the locator ran. */
    private static List<String> locate(Map<String, Object> properties) {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new LibraryLocator().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(null, new SpringApplication(), new String[0], env));
        return Arrays.asList(env.getProperty("thumbnailer.library-dir"), env.getProperty("thumbnailer.library-pinned"));
    }
}
