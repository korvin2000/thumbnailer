package thumbnailer.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dynatrace.hash4j.hashing.Hashing;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import thumbnailer.spi.AlbumContainer;

class ThumbnailerTest {

    private static final Render JPEG_256 = new Render(Render.Format.JPEG, 85, 256, 0);

    private final Thumbnailer thumbnailer = new Thumbnailer();

    @Test
    void longerSideBecomes512KeepingProportions() throws IOException {
        var thumb = render(jpeg(3000, 2000));
        assertThat(thumb.portrait()).isFalse();
        assertThat(size(thumb.bytes())).isEqualTo("512x341");
        assertThat(size(render(jpeg(8000, 600)).bytes())).as("panorama").isEqualTo("512x38");
        var portrait = render(jpeg(2000, 3000));
        assertThat(portrait.portrait()).isTrue();
        assertThat(size(portrait.bytes())).isEqualTo("341x512");
        assertThat(size(render(jpeg(300, 200)).bytes())).as("never upscaled").isEqualTo("300x200");
        var small = render(jpeg(3000, 2000), new Render(Render.Format.WEBP, 60, 256, 1));
        assertThat(size(small.bytes())).as("the spec's size").isEqualTo("256x171");
        assertThat(small.epoch()).isNotEqualTo(thumb.epoch()).isEqualTo(new Render(Render.Format.WEBP, 60, 256, 8).epoch());
    }

    @Test
    void jpegSpecEncodesAWholeJpeg() throws IOException {
        var thumb = render(jpeg(2000, 3000), JPEG_256);
        assertThat(thumb.format()).isEqualTo(Render.Format.JPEG);
        assertThat(thumb.epoch()).isEqualTo(JPEG_256.epoch());
        assertThat(thumb.portrait()).isTrue();
        var b = thumb.bytes();
        assertThat(new byte[] {b[0], b[1], b[b.length - 2], b[b.length - 1]}).as("SOI ... EOI")
                .containsExactly(0xFF, 0xD8, 0xFF, 0xD9);
        var img = ImageIO.read(new ByteArrayInputStream(b));
        assertThat(img.getWidth() + "x" + img.getHeight()).isEqualTo("171x256");
        assertThat(new Color(img.getRGB(40, 128)).getRed()).as("left half stays red").isGreaterThan(200);
    }

    @Test
    void renderSpecIsValidatedAndKeepsTheLegacyEpoch() {
        long legacy = Hashing.wyhashFinal4().hashIntIntToLong(512, Float.floatToIntBits(75f)); // pre-settings Thumbnailer.epoch()
        assertThat(Render.LEGACY_EPOCH).isEqualTo(legacy);
        assertThat(Render.COVERS.epoch()).as("default covers keep stored values valid").isEqualTo(legacy);
        assertThat(new Render(Render.Format.JPEG, 75, 512, 4).epoch()).as("format counts").isNotEqualTo(legacy);
        assertThat(Render.THUMBS.permits()).isEqualTo(Runtime.getRuntime().availableProcessors());
        for (int[] bad : new int[][] {{0, 512, 0}, {101, 512, 0}, {75, 63, 0}, {75, 4097, 0}, {75, 512, -1}, {75, 512, 65}})
            assertThatThrownBy(() -> new Render(Render.Format.WEBP, bad[0], bad[1], bad[2])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exifOrientation6RotatesClockwise() throws IOException {
        // Stored landscape, left half red: displayed rotated 90 degrees clockwise, so portrait with red on top.
        var thumb = render(withOrientation(jpeg(3000, 2000), 6));
        assertThat(thumb.portrait()).isTrue();
        var img = WebP.decode(thumb.bytes());
        assertThat(img.width() + "x" + img.height()).isEqualTo("341x512");
        assertThat(new Color(rgb(img, 170, 40)).getRed()).isGreaterThan(200);
        assertThat(new Color(rgb(img, 170, 470)).getBlue()).isGreaterThan(200);
    }

    @Test
    void largePngIsReopenedAndTransparencyLandsOnWhite() throws IOException {
        var img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        var rnd = new java.util.Random(1);
        for (int y = 0; y < 400; y++) for (int x = 300; x < 600; x++) img.setRGB(x, y, 0xFF000000 | rnd.nextInt());
        var png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        assertThat(png.size()).as("past the kept head, so the entry is reopened").isGreaterThan(128 << 10);
        var thumb = render(png.toByteArray());
        assertThat(size(thumb.bytes())).isEqualTo("512x341");
        assertThat(new Color(rgb(WebP.decode(thumb.bytes()), 20, 170)).getRed()).isGreaterThan(240);
    }

    private Thumbnailer.Thumb render(byte[] image) throws IOException { return render(image, Render.COVERS); }

    private Thumbnailer.Thumb render(byte[] image, Render spec) throws IOException {
        return thumbnailer.render(new AlbumContainer() {
            @Override public String name() { return "test"; }
            @Override public List<Item> items() { return List.of(); }
            @Override public InputStream open(String name) { return new ByteArrayInputStream(image); }
            @Override public void close() {}
        }, "image.jpg", spec);
    }

    private static String size(byte[] webp) {
        var f = WebP.features(webp);
        return f.width + "x" + f.height;
    }

    private static int rgb(Pixels p, int x, int y) { return p.argb()[y * p.width() + x]; }

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
