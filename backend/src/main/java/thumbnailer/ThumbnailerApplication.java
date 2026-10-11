package thumbnailer;

import javax.imageio.ImageIO;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.socket.config.annotation.EnableWebSocket;

@SpringBootApplication
@EnableWebSocket
public class ThumbnailerApplication {

    public static void main(String[] args) {
        ImageIO.scanForPlugins(); // registers TwelveMonkeys readers from the fat jar's nested class loader
        ImageIO.setUseCache(false); // header probes stay in memory instead of temp files
        SpringApplication.run(ThumbnailerApplication.class, args);
    }
}
