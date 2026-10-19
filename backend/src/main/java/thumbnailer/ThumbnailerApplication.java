package thumbnailer;

import java.io.IOException;
import javax.imageio.ImageIO;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import thumbnailer.settings.Libraries;
import thumbnailer.settings.LibraryLocator;

@SpringBootApplication
@EnableWebSocket
public class ThumbnailerApplication {

    private static String[] args = {};
    private static volatile ConfigurableApplicationContext context;
    private static String library; // the library the running context uses: where a failed restart falls back to

    public static void main(String[] args) {
        // TuplDB page access through FFM instead of the deprecated sun.misc.Unsafe; read before any TuplDB class loads.
        System.getProperties().putIfAbsent("org.cojen.tupl.core.DirectPageOpsSelector", "ffm");
        ImageIO.scanForPlugins(); // registers TwelveMonkeys readers from the fat jar's nested class loader
        ImageIO.setUseCache(false); // header probes stay in memory instead of temp files
        synchronized (ThumbnailerApplication.class) { // a restart asked for during start-up waits until the runners are done
            ThumbnailerApplication.args = args;
            context = app().run(args);
            library = context.getBean(Libraries.class).dir();
        }
    }

    /** The application with the listener that picks the active library, for every (re)start. */
    static SpringApplication app() {
        var app = new SpringApplication(ThumbnailerApplication.class);
        app.addListeners(new LibraryLocator());
        return app;
    }

    /**
     * Closes the context (stores close, scans stop) and runs the application again with the same arguments, so a
     * switched library or an optimize at close takes effect; clients reconnect. Only for an application started by
     * {@link #main}. If the new context fails to start, the previous library becomes active again and starts (once).
     */
    public static void restart() { Thread.ofPlatform().name("restart").daemon(false).start(ThumbnailerApplication::rerun); }

    /**
     * Closes the context (scans stop, stores flush and close) and ends the process with its exit code. Only for an
     * application started by {@link #main}; waits for a restart in progress.
     */
    public static void exit() { Thread.ofPlatform().name("exit").daemon(false).start(ThumbnailerApplication::shutdown); }

    private static synchronized void shutdown() {
        if (context == null) {
            LoggerFactory.getLogger(ThumbnailerApplication.class).warn("Not started by main: no exit");
            return;
        }
        System.exit(SpringApplication.exit(context));
    }

    private static synchronized void rerun() {
        var log = LoggerFactory.getLogger(ThumbnailerApplication.class);
        if (context == null) {
            log.warn("Not started by main: no restart");
            return;
        }
        // The fat jar's classes are visible only to the application class loader, not to a pool thread's default one.
        Thread.currentThread().setContextClassLoader(ThumbnailerApplication.class.getClassLoader());
        var libraries = context.getBean(Libraries.class);
        try {
            context.close();
            context = app().run(args);
        } catch (RuntimeException e) {
            log.error("Restart failed, starting library {} again", library, e);
            try {
                if (!libraries.pinned()) libraries.switchTo(library);
                context = app().run(args);
            } catch (IOException | RuntimeException again) {
                log.error("Restart on {} failed too, giving up", library, again);
                return;
            }
        }
        library = context.getBean(Libraries.class).dir();
    }
}
