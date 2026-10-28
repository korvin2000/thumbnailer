package thumbnailer.image;

import dev.matrixlab.webp4j.internal.NativeWebP;
import dev.matrixlab.webp4j.model.WebPBitstreamFeatures;
import java.io.IOException;

/** libwebp 1.6 (AVX2 build of github.com/korvin2000/libwebp) through webp4j's JNI library, zero copy both ways. */
final class WebP {

    private WebP() {}

    static boolean isWebP(byte[] d) {
        return d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P';
    }

    /** Width, height and alpha from the bitstream header (a prefix of the file suffices), or null. */
    static WebPBitstreamFeatures features(byte[] data) {
        var f = new WebPBitstreamFeatures();
        return NativeWebP.getFeatures(data, data.length, f) == 0 ? f : null;
    }

    /**
     * Still images only: an animation throws, and its first frame comes through ImageIO. Decodes at full size, so the
     * header's size beyond {@link Limits#MAX_PIXELS} fails (IllegalArgumentException) before any pixel is allocated.
     */
    static Pixels decode(byte[] data) throws IOException {
        var f = features(data);
        if (f == null || f.hasAnimation) throw new IOException("Not a still WebP");
        var px = new int[Limits.pixels("WebP", f.width, f.height)];
        if (!NativeWebP.decodeInto(data, px, f.width * 4)) throw new IOException("WebP decode failed");
        var p = new Pixels(px, f.width, f.height);
        return f.hasAlpha ? p.onWhite() : p;
    }

    /** Lossy, single-threaded (renders already run in parallel), the pixels imported as BGRX without a copy. */
    static byte[] encode(Pixels p, int quality) throws IOException {
        var b = NativeWebP.encode(p.argb(), p.width(), p.height(), quality, false, false, false);
        if (b == null) throw new IOException("WebP encode failed");
        return b;
    }
}
