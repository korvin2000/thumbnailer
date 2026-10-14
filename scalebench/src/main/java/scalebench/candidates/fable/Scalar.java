package scalebench.candidates.fable;

import java.util.Arrays;

/** Plain-Java fallback for JVMs without {@code jdk.incubator.vector}: the same geometry and float math, whole planes. */
final class Scalar {

    private Scalar() {}

    static void run(int[] src, int sw, int sh, int dw, int dh, int kx, int ky, Axis ax, Axis ay, int[] out) {
        int bw = (sw + kx - 1) / kx, bh = (sh + ky - 1) / ky;
        float[] in = null; // box means as floats, or null to read the packed source directly
        if (kx > 1 || ky > 1) {
            in = new float[bw * bh * 4];
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) Fable.boxCell(src, sw, sh, kx, ky, bx, by, in, (by * bw + bx) * 4);
        }
        if (dh <= sh) {
            var mid = new float[bw * dh * 4];
            vertical(in, src, bw, ay, mid, null);
            horizontal(mid, null, bw, dh, ax, null, out, dw);
        } else {
            var mid = new float[dw * bh * 4];
            horizontal(in, src, bw, bh, ax, mid, null, dw);
            vertical(mid, null, dw, ay, null, out);
        }
    }

    /** Resamples rows; reads floats ({@code in}) or packed ints ({@code src}), writes floats or packed pixels. */
    static void vertical(float[] in, int[] src, int w, Axis ay, float[] mid, int[] out) {
        var acc = new float[w * 4];
        for (int y = 0; y < ay.first().length; y++) {
            Arrays.fill(acc, 0);
            for (int t = 0; t < ay.count()[y]; t++) {
                float wt = ay.weight()[y * ay.taps() + t];
                int ro = (ay.first()[y] + t) * w;
                if (in != null) for (int i = 0, si = ro * 4; i < acc.length; i++) acc[i] += wt * in[si + i];
                else for (int x = 0, i = 0; x < w; x++, i += 4) {
                    int p = src[ro + x];
                    acc[i] += wt * (p & 255);
                    acc[i + 1] += wt * (p >>> 8 & 255);
                    acc[i + 2] += wt * (p >>> 16 & 255);
                }
            }
            if (mid != null) System.arraycopy(acc, 0, mid, y * w * 4, acc.length);
            else for (int x = 0, o = y * w; x < w; x++) out[o + x] = pack(acc[4 * x + 2], acc[4 * x + 1], acc[4 * x]);
        }
    }

    /** Resamples columns of {@code h} rows that are {@code w} wide into {@code dw} wide rows. */
    static void horizontal(float[] in, int[] src, int w, int h, Axis ax, float[] mid, int[] out, int dw) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < dw; x++) {
                float r = 0, g = 0, b = 0;
                for (int t = 0, i = y * w + ax.first()[x], wo = x * ax.taps(); t < ax.count()[x]; t++, i++, wo++) {
                    float wt = ax.weight()[wo];
                    if (in != null) {
                        b += wt * in[i * 4];
                        g += wt * in[i * 4 + 1];
                        r += wt * in[i * 4 + 2];
                    } else {
                        int p = src[i];
                        b += wt * (p & 255);
                        g += wt * (p >>> 8 & 255);
                        r += wt * (p >>> 16 & 255);
                    }
                }
                if (mid != null) {
                    int o = (y * dw + x) * 4;
                    mid[o] = b;
                    mid[o + 1] = g;
                    mid[o + 2] = r;
                } else out[y * dw + x] = pack(r, g, b);
            }
    }

    static int pack(float r, float g, float b) { return clip(r) << 16 | clip(g) << 8 | clip(b); }

    /** Round half up, truncating like the vector F2I conversion, then clamp. */
    static int clip(float v) { return Math.clamp((int) (v + 0.5f), 0, 255); }
}
