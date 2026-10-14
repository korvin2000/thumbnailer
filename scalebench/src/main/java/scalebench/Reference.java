package scalebench;

import java.util.function.DoubleUnaryOperator;
import java.util.stream.IntStream;

/**
 * Exact float64 separable resampling (Pillow's {@code ImagingResample} geometry: pixel centres at {@code i + 0.5},
 * kernel support widened by the scale factor when shrinking, weights normalized per output pixel, no rounding until
 * the end). Lanczos3 serves as the "ideal" downscale and as the degradation step of the upscale test; Catmull-Rom is
 * the classic bicubic for comparison. Slow on purpose: quality reference only, never timed.
 */
public final class Reference implements Scaler {

    public static final Reference LANCZOS3 = new Reference("RefLanczos3", 3, x -> x == 0 ? 1 : x >= 3 ? 0 : sinc(x) * sinc(x / 3));
    public static final Reference CATMULL_ROM = new Reference("RefCatmullRom", 2,
            x -> x < 1 ? (1.5 * x - 2.5) * x * x + 1 : x < 2 ? ((-0.5 * x + 2.5) * x - 4) * x + 2 : 0);

    private final String name;
    private final double support;
    private final DoubleUnaryOperator kernel; // of |x|

    private Reference(String name, double support, DoubleUnaryOperator kernel) {
        this.name = name;
        this.support = support;
        this.kernel = kernel;
    }

    @Override public String name() { return name; }

    @Override
    public int[] resize(int[] src, int sw, int sh, int dw, int dh) {
        double[][] planes = planes(src, sw, sh, dw, dh);
        int[] out = new int[dw * dh];
        for (int i = 0; i < out.length; i++)
            out[i] = 0xFF000000 | round(planes[0][i]) << 16 | round(planes[1][i]) << 8 | round(planes[2][i]);
        return out;
    }

    /** Float planes for the metrics (R, G, B), unrounded. */
    public double[][] planes(int[] src, int sw, int sh, int dw, int dh) {
        double[][] planes = new double[3][];
        for (int c = 0; c < 3; c++) {
            int shift = 16 - 8 * c;
            double[] p = new double[sw * sh];
            for (int i = 0; i < p.length; i++) p[i] = src[i] >>> shift & 255;
            planes[c] = resize(resize(p, sw, sh, dw, true), dw, sh, dh, false);
        }
        return planes;
    }

    /** One axis: {@code horizontal} maps (w x h) to (out x h), else (w x h) to (w x out). */
    private double[] resize(double[] in, int w, int h, int out, boolean horizontal) {
        int n = horizontal ? w : h;
        double scale = (double) n / out, filterScale = Math.max(1, scale), sup = support * filterScale;
        int[] first = new int[out], count = new int[out];
        double[][] weights = new double[out][];
        for (int i = 0; i < out; i++) {
            double center = (i + 0.5) * scale;
            int lo = Math.max(0, (int) Math.floor(center - sup)), hi = Math.min(n, (int) Math.ceil(center + sup));
            double[] k = new double[hi - lo];
            double sum = 0;
            for (int j = lo; j < hi; j++) sum += k[j - lo] = kernel.applyAsDouble(Math.abs((j + 0.5 - center) / filterScale));
            for (int j = 0; j < k.length; j++) k[j] /= sum;
            first[i] = lo;
            count[i] = k.length;
            weights[i] = k;
        }
        double[] res = new double[horizontal ? out * h : w * out];
        if (horizontal) {
            IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0; x < out; x++) {
                    double s = 0;
                    for (int k = 0; k < count[x]; k++) s += weights[x][k] * in[y * w + first[x] + k];
                    res[y * out + x] = s;
                }
            });
        } else {
            IntStream.range(0, out).parallel().forEach(y -> {
                for (int x = 0; x < w; x++) {
                    double s = 0;
                    for (int k = 0; k < count[y]; k++) s += weights[y][k] * in[(first[y] + k) * w + x];
                    res[y * w + x] = s;
                }
            });
        }
        return res;
    }

    private static int round(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    private static double sinc(double x) { return Math.sin(Math.PI * x) / (Math.PI * x); }
}
