package thumbnailer.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import thumbnailer.source.AlbumContainer;

class ThumbnailerTest {

    private final Thumbnailer thumbnailer = new Thumbnailer(640);

    @Test
    void landscapeBecomes16by9() throws IOException {
        var thumb = render(jpeg(3000, 2000));
        assertThat(thumb.portrait()).isFalse();
        assertThat(size(thumb.jpeg())).isEqualTo("640x360");
        assertThat(size(render(jpeg(8000, 600)).jpeg())).as("panorama: only the centre crop is decoded").isEqualTo("640x360");
    }

    @Test
    void portraitBecomes9by16() throws IOException {
        var thumb = render(jpeg(2000, 3000));
        assertThat(thumb.portrait()).isTrue();
        assertThat(size(thumb.jpeg())).isEqualTo("360x640");
    }

    @Test
    void exifOrientation6RotatesClockwise() throws IOException {
        // Stored landscape, left half red: displayed rotated 90 degrees clockwise, so portrait with red on top.
        var thumb = render(withOrientation(jpeg(3000, 2000), 6));
        assertThat(thumb.portrait()).isTrue();
        var img = ImageIO.read(new ByteArrayInputStream(thumb.jpeg()));
        assertThat(img.getWidth() + "x" + img.getHeight()).isEqualTo("360x640");
        assertThat(new Color(img.getRGB(180, 40)).getRed()).isGreaterThan(200);
        assertThat(new Color(img.getRGB(180, 600)).getBlue()).isGreaterThan(200);
    }

    private Thumbnailer.Thumb render(byte[] image) throws IOException {
        return thumbnailer.render(new AlbumContainer() {
            @Override public String name() { return "test"; }
            @Override public List<Item> items() { return List.of(); }
            @Override public InputStream open(String name) { return new ByteArrayInputStream(image); }
            @Override public void close() {}
        }, "image.jpg");
    }

    private static String size(byte[] jpeg) throws IOException {
        var img = ImageIO.read(new ByteArrayInputStream(jpeg));
        return img.getWidth() + "x" + img.getHeight();
    }

    /** Left half red, right half blue. */
    private static byte[] jpeg(int w, int h) throws IOException {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** Inserts a minimal big-endian EXIF APP1 segment holding only the orientation tag. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {(byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8,
                0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0};
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.writeBytes(app1);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
