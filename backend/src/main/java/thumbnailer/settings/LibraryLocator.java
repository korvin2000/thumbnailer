package thumbnailer.settings;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Before any bean exists, points {@code thumbnailer.library-dir} at the active library of {@code libraries.json}
 * ({@link Libraries}), unless the command line or the environment already set it; {@code thumbnailer.library-pinned}
 * records which. Registered by {@code ThumbnailerApplication.app()}, so each (re)start reads the file again.
 */
public class LibraryLocator implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final String PROPERTY = "thumbnailer.library-dir", PINNED = "thumbnailer.library-pinned";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        var env = event.getEnvironment();
        var home = env.getProperty("thumbnailer.data-dir");
        boolean pinned = env.containsProperty(PROPERTY);
        Map<String, Object> props = new HashMap<>(Map.of(PINNED, pinned));
        var active = pinned || home == null ? null : Libraries.read(Path.of(home, Libraries.FILE), JsonMapper.builder().build()).active();
        if (active != null) props.put(PROPERTY, active);
        env.getPropertySources().addFirst(new MapPropertySource("library", props));
    }
}
