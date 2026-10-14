package thumbnailer.web;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ErrorPage;
import io.undertow.servlet.api.MimeMapping;
import io.undertow.servlet.api.ServletContainerInitializerInfo;
import io.undertow.servlet.util.ImmediateInstanceFactory;
import io.undertow.websockets.jsr.WebSocketDeploymentInfo;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;
import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import org.springframework.boot.web.server.autoconfigure.servlet.ServletWebServerConfiguration;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.servlet.ServletContextInitializers;
import org.springframework.boot.web.server.servlet.ServletWebServerSettings;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

/**
 * The embedded web server: Undertow, which Spring Boot 4 no longer ships. Undertow EE (Servlet 6.1, Jakarta WebSocket
 * 2.2, which Spring's standard upgrade strategy drives) on undertow-core. Boot's own customizer applies
 * {@code server.*}: address, port, context path, MIME mappings, error pages, init parameters, graceful shutdown; SSL,
 * HTTP/2 and compression are not wired (a local plain-HTTP app).
 */
@Component
@Import(ServletWebServerConfiguration.class) // what Boot's Tomcat/Jetty auto-configurations import: server.* binding
class UndertowServer extends AbstractConfigurableWebServerFactory implements ConfigurableServletWebServerFactory {

    private static final Logger log = LoggerFactory.getLogger(UndertowServer.class);
    private final ServletWebServerSettings settings = new ServletWebServerSettings();

    @Override
    public ServletWebServerSettings getSettings() { return settings; }

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        var undertow = new AtomicReference<Undertow>();
        // Undertow's default pool frees each buffer beyond the per-thread cache (allocateDirect churn, and the free is
        // Unsafe.invokeCleaner, which JDK 27 warns about); this one recycles up to 1024 x 16 KiB instead.
        var buffers = new DefaultByteBufferPool(true, 16 << 10, 1024, 12);
        ServletContainerInitializer spring = (types, context) -> {
            for (var initializer : ServletContextInitializers.from(settings, initializers)) initializer.onStartup(context);
        };
        var info = Servlets.deployment().setDeploymentName("thumbnailer").setClassLoader(getClass().getClassLoader())
                .setContextPath(settings.getContextPath().toString())
                .addServletContainerInitializer(new ServletContainerInitializerInfo(spring.getClass(),
                        new ImmediateInstanceFactory<>(spring), Set.of()))
                // Handlers query the stores (blocking): messages run on worker threads, never on I/O threads. The
                // worker serves WebSocket clients only; the server's own one exists once it starts.
                .addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME, new WebSocketDeploymentInfo()
                        .setDispatchToWorkerThread(true).setBuffers(buffers)
                        .setWorker(() -> undertow.get().getWorker()));
        settings.getMimeMappings().forEach(m -> info.addMimeMapping(new MimeMapping(m.getExtension(), m.getMimeType())));
        getErrorPages().forEach(page -> info.addErrorPage(page.isGlobal() ? new ErrorPage(page.getPath())
                : page.getException() != null ? new ErrorPage(page.getPath(), page.getException())
                : new ErrorPage(page.getPath(), page.getStatusCode())));
        var deployment = Servlets.newContainer().addDeployment(info);
        deployment.deploy(); // runs Spring's initializers: DispatcherServlet, filters, the WebSocket endpoint
        HttpHandler handler;
        try {
            handler = deployment.start();
        } catch (ServletException e) {
            throw new WebServerException("Unable to start the Undertow deployment", e);
        }
        if (!info.getContextPath().isEmpty()) handler = Handlers.path().addPrefixPath(info.getContextPath(), handler);
        var gate = getShutdown() == Shutdown.GRACEFUL ? Handlers.gracefulShutdown(handler) : null;
        undertow.set(Undertow.builder()
                .addHttpListener(getPort(), getAddress() != null ? getAddress().getHostAddress() : "0.0.0.0")
                .setByteBufferPool(buffers)
                .setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 0) // stop() would wait for every open WebSocket
                .setHandler(gate != null ? gate : handler).build());
        return new Server(undertow.get(), deployment, gate, getPort());
    }

    /** One Undertow serving one deployment: Boot's lifecycle starts and stops it, context close destroys it. */
    private static final class Server implements WebServer {

        private final Undertow undertow;
        private final DeploymentManager deployment;
        private final GracefulShutdownHandler gate; // null: immediate shutdown
        private final int port;
        private boolean started;

        Server(Undertow undertow, DeploymentManager deployment, GracefulShutdownHandler gate, int port) {
            this.undertow = undertow;
            this.deployment = deployment;
            this.gate = gate;
            this.port = port;
        }

        @Override
        public synchronized void start() {
            if (started) return;
            if (gate != null) gate.start(); // reopens after an earlier graceful shutdown
            try {
                undertow.start();
            } catch (RuntimeException e) {
                PortInUseException.ifPortBindingException(e, bind -> { throw new PortInUseException(port, e); });
                throw new WebServerException("Unable to start Undertow", e);
            }
            started = true;
            log.info("Undertow started on port {}", getPort());
        }

        @Override
        public synchronized void stop() {
            if (!started) return;
            started = false;
            var worker = undertow.getWorker();
            undertow.stop(); // closes the listeners and interrupts the workers, without waiting for them
            try { // like Tomcat's executor: a handler in flight ends before the context closes the stores under it
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) log.warn("Undertow workers still busy after 5 s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public synchronized int getPort() {
            return started && undertow.getListenerInfo().getFirst().getAddress() instanceof InetSocketAddress a
                    ? a.getPort() : -1;
        }

        /** New requests get 503; the callback fires once the requests in flight are done (upgraded sockets are not). */
        @Override
        public void shutDownGracefully(GracefulShutdownCallback callback) {
            if (gate == null) {
                callback.shutdownComplete(GracefulShutdownResult.IMMEDIATE);
                return;
            }
            gate.shutdown();
            gate.addShutdownListener(idle -> callback.shutdownComplete(idle ? GracefulShutdownResult.IDLE
                    : GracefulShutdownResult.REQUESTS_ACTIVE)); // false: start() reopened the gate first
        }

        @Override
        public synchronized void destroy() {
            stop();
            if (deployment.getState() == DeploymentManager.State.UNDEPLOYED) return;
            try {
                deployment.stop();
            } catch (ServletException e) {
                throw new WebServerException("Unable to stop the Undertow deployment", e);
            }
            deployment.undeploy();
        }
    }
}
