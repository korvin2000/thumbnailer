package thumbnailer.image;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Packed {@code 0x??RRGGBB} pixels, row-major, no padding, top byte ignored: the one layout from decode to encode.
 * TurboJPEG decodes into it ({@code TJPF_BGRX}), the resampler maps it, webp4j and TurboJPEG encode from it, so the
 * chain never converts or copies between stages.
 */
record Pixels(int[] argb, int width, int height) {

    /** An ImageIO fallback result: transparency composited on white, any layout drawn once into packed RGB. */
    static Pixels of(BufferedImage img) {
        if (img.getType() != BufferedImage.TYPE_INT_RGB || !(img.getRaster().getDataBuffer() instanceof DataBufferInt)) {
            var rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            g.drawImage(img, 0, 0, Color.WHITE, null);
            g.dispose();
            img = rgb;
        }
        return new Pixels(((DataBufferInt) img.getRaster().getDataBuffer()).getData(), img.getWidth(), img.getHeight());
    }

    /** Straight-alpha ARGB (WebP) composited on white in place. */
    Pixels onWhite() {
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i], a = p >>> 24;
            if (a == 255) continue;
            int na = 255 - a;
            argb[i] = ((p >>> 16 & 255) * a + 255 * na) / 255 << 16 | ((p >>> 8 & 255) * a + 255 * na) / 255 << 8 | ((p & 255) * a + 255 * na) / 255;
        }
        return this;
    }

    /** Stored pixels as displayed under EXIF orientation 2..8 (mirrors and quarter turns), by index remapping. */
    Pixels oriented(int orientation) {
        if (orientation < 2 || orientation > 8) return this;
        int w = width, h = height, x1 = w - 1, y1 = h - 1;
        boolean swap = orientation >= 5;
        int dw = swap ? h : w, dh = swap ? w : h;
        var out = new int[argb.length];
        for (int y = 0, o = 0; y < dh; y++)
            for (int x = 0; x < dw; x++, o++)
                out[o] = argb[switch (orientation) {
                    case 2 -> y * w + x1 - x;
                    case 3 -> (y1 - y) * w + x1 - x;
                    case 4 -> (y1 - y) * w + x;
                    case 5 -> x * w + y;
                    case 6 -> (y1 - x) * w + y;
                    case 7 -> (y1 - x) * w + x1 - y;
                    default -> x * w + x1 - y; // 8
                }];
        return new Pixels(out, dw, dh);
    }
}
