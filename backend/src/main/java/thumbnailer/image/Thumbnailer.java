package thumbnailer.image;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import thumbnailer.source.AlbumContainer;

/** Renders 16:9 (landscape) or 9:16 (portrait) JPEG thumbnails whose longer side is {@code thumbnailer.thumb-width}. */
@Component
public class Thumbnailer {

    private final int size;

    public Thumbnailer(@Value("${thumbnailer.thumb-width:640}") int size) { this.size = size; }

    public record Thumb(byte[] jpeg, boolean portrait) {}

    /**
     * Streams the entry and decodes only the centred crop, subsampled so its longer side is at most twice the target:
     * memory stays bounded by the thumbnail size, not by the original's.
     */
    public Thumb render(AlbumContainer container, String name) throws IOException {
        ImageProbe.Exif exif;
        try (var in = container.open(name)) {
            exif = ImageProbe.exif(in);
        }
        boolean swap = exif.swapsAxes();
        int tw, th;
        BufferedImage decoded;
        try (var raw = container.open(name); var in = ImageIO.createImageInputStream(raw)) {
            var reader = ImageProbe.reader(in, name);
            try {
                int w = reader.getWidth(0), h = reader.getHeight(0);
                double ow = swap ? h : w, oh = swap ? w : h; // as displayed
                tw = oh > ow ? size * 9 / 16 : size;
                th = oh > ow ? size : size * 9 / 16;
                double cw = Math.min(ow, oh * tw / th), ch = Math.min(oh, ow * th / tw);
                // A centred crop stays centred under every EXIF orientation, so it can be taken in stored coordinates.
                int rw = (int) Math.round(swap ? ch : cw), rh = (int) Math.round(swap ? cw : ch);
                int step = Math.max(1, (int) Math.ceil(Math.max(cw, ch) / (2.0 * size)));
                var param = reader.getDefaultReadParam();
                param.setSourceRegion(new Rectangle((w - rw) / 2, (h - rh) / 2, Math.max(1, rw), Math.max(1, rh)));
                param.setSourceSubsampling(step, step, 0, 0);
                decoded = reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
        int dw = decoded.getWidth(), dh = decoded.getHeight();
        var t = AffineTransform.getScaleInstance((double) tw / (swap ? dh : dw), (double) th / (swap ? dw : dh));
        t.concatenate(orientation(exif.orientation(), dw, dh));
        return new Thumb(jpeg(draw(decoded, t, tw, th)), th > tw);
    }

    /** Maps stored pixels to displayed ones for EXIF orientations 2..8, mirrored variants included. */
    static AffineTransform orientation(int o, int w, int h) {
        return switch (o) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
    }

    /** One bilinear draw: the decoded crop is at most twice the target, where bilinear still averages every pixel. */
    private static BufferedImage draw(BufferedImage src, AffineTransform t, int w, int h) {
        var dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE); // background for transparent sources
            g.fillRect(0, 0, w, h);
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(.85f);
        var out = new ByteArrayOutputStream(64 << 10);
        try (var ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
