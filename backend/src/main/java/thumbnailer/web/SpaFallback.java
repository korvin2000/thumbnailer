package thumbnailer.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/** Serves the packaged UI; client-side routes (no dot in the last path segment) get {@code index.html}. */
@Configuration
class SpaFallback implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations("classpath:/static/").resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        var resource = super.getResource(path, location);
                        return resource != null || path.substring(path.lastIndexOf('/') + 1).contains(".") ? resource
                                : super.getResource("index.html", location);
                    }
                });
    }
}
