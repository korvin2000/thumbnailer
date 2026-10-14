package scalebench.candidates.fable;

/**
 * Separable Catmull-Rom resampler with Pillow geometry (centres at i + 0.5, support widened by the scale when shrinking).
 * Large shrinks first average power-of-two boxes (libvips "shrink then reduce") and sharpen the cubic's taps to cancel
 * the box droop ({@link Axis}). Passes stream row by row: the shrinking vertical pass fills one float row that the
 * horizontal pass consumes at once; enlargements keep a small ring of horizontally resampled rows. Pixels travel as four
 * float lanes [B,G,R,X] so packed XRGB loads and stores need no shuffles. {@link Simd} is used when the Vector API module
 * is present, else {@link Scalar}.
 */
public final class Fable {

    static final int KMAX = 8;
    static final double RMIN = env("FABLE_RMIN", 2.0), LAMBDA = env("FABLE_LAMBDA", 1.0 / 24);
    static final boolean SIMD = !"0".equals(System.getenv("FABLE_SIMD"))
            && ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent() && Simd.usable();

    private Fable() {}

    public static int[] resize(int[] src, int sw, int sh, int dw, int dh) {
        if (sw == dw && sh == dh) return src.clone();
        int kx = dh <= sh ? box(sw, dw) : 1, ky = box(sh, dh); // growing rows read packed rows directly, no box
        var ax = Axis.of(sw, kx, (sw + kx - 1) / kx, dw, LAMBDA);
        var ay = Axis.of(sh, ky, (sh + ky - 1) / ky, dh, LAMBDA);
        int[] out = new int[dw * dh];
        if (SIMD) Simd.run(src, sw, sh, dw, dh, kx, ky, ax, ay, out);
        else Scalar.run(src, sw, sh, dw, dh, kx, ky, ax, ay, out);
        return out;
    }

    /** Largest power-of-two box (<= KMAX) that leaves a remaining ratio of at least RMIN for the cubic. */
    static int box(int in, int out) {
        double scale = (double) in / out;
        int k = 1;
        while (k * 2 <= KMAX && scale / (k * 2) >= RMIN) k *= 2;
        return k;
    }

    /** Catmull-Rom (Keys a = -0.5) of |x|. */
    static double kernel(double x) {
        return x < 1 ? (1.5 * x - 2.5) * x * x + 1 : x < 2 ? ((-0.5 * x + 2.5) * x - 4) * x + 2 : 0;
    }

    /** Mean of the kx-by-ky cell at (bx, by), clipped at the image edge, as floats [B,G,R,0] at {@code dst[o..o+3]}. */
    static void boxCell(int[] src, int sw, int sh, int kx, int ky, int bx, int by, float[] dst, int o) {
        int x0 = bx * kx, y0 = by * ky, wc = Math.min(kx, sw - x0), hc = Math.min(ky, sh - y0), r = 0, g = 0, b = 0;
        for (int y = y0; y < y0 + hc; y++)
            for (int i = y * sw + x0, e = i + wc; i < e; i++) {
                int p = src[i];
                r += p >>> 16 & 255;
                g += p >>> 8 & 255;
                b += p & 255;
            }
        float inv = 1f / (wc * hc);
        dst[o] = b * inv;
        dst[o + 1] = g * inv;
        dst[o + 2] = r * inv;
        dst[o + 3] = 0;
    }

    private static double env(String name, double def) {
        String v = System.getenv(name);
        return v == null ? def : Double.parseDouble(v);
    }
}
