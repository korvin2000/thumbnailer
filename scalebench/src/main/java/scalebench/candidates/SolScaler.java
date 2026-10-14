package scalebench.candidates;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import scalebench.Scaler;

/** Single-threaded, separable antialiased cubic resampling of packed XRGB. */
public final class SolScaler implements Scaler {
    private static final int BITS = 14, ONE = 1 << BITS, HALF = ONE / 2;
    private static final boolean VECTOR = ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
    @Override public String name() { return "Sol"; }
    @Override public boolean supports(int sw, int sh, int dw, int dh) { return true; }

    @Override
    public int[] resize(int[] src, int sw, int sh, int dw, int dh) {
        if (sw == dw && sh == dh) return src.clone();
        Axis ax = Axis.of(sw, dw), ay = Axis.of(sh, dh);
        int[] out = new int[dw * dh];
        if (sw == dw) {
            filter(src, sw, dh, ay, out);
            return out;
        }
        if (dh <= sh) {
            if (VECTOR) {
                int[] mid = sh == dh ? src : new int[sw * dh];
                if (sh != dh) filter(src, sw, dh, ay, mid);
                Simd.horizontal(mid, sw, dw, dh, ax, out);
                return out;
            }
            int[] mid = new int[sw * dh], columns = new int[sw * dh], result = new int[dw * dh];
            if (sh == dh) mid = src;
            else filter(src, sw, dh, ay, mid);
            transpose(mid, sw, dh, columns);
            filter(columns, dh, dw, ax, result);
            transpose(result, dh, dw, out);
        } else {
            int[] columns = new int[sw * sh], mid = new int[dw * sh], rows = new int[dw * sh];
            transpose(src, sw, sh, columns);
            filter(columns, sh, dw, ax, mid);
            transpose(mid, sh, dw, rows);
            filter(rows, dw, dh, ay, out);
        }
        return out;
    }

    private static void filter(int[] src, int w, int h, Axis a, int[] out) {
        if (VECTOR) Simd.vertical(src, w, h, a, out);
        else vertical(src, w, h, a, out);
    }

    private static void transpose(int[] src, int w, int h, int[] out) {
        for (int by = 0; by < h; by += 32) {
            for (int bx = 0; bx < w; bx += 32) {
                int ey = Math.min(by + 32, h), ex = Math.min(bx + 32, w);
                for (int y = by; y < ey; y++)
                    for (int x = bx; x < ex; x++) out[x * h + y] = src[y * w + x];
            }
        }
    }

    private static int clip(int v) { return Math.clamp(v >> BITS, 0, 255); }

    private static void horizontal(int[] src, int sw, int dw, int h, Axis a, int[] out) {
        for (int y = 0; y < h; y++) {
            int row = y * sw, dest = y * dw;
            for (int x = 0; x < dw; x++) {
                int first = row + a.first[x], n = a.count[x], ko = x * a.stride;
                int r = HALF, g = HALF, b = HALF;
                for (int k = 0; k < n; k++) {
                    int p = src[first + k], c = a.weight[ko + k];
                    r += (p >>> 16 & 255) * c;
                    g += (p >>> 8 & 255) * c;
                    b += (p & 255) * c;
                }
                out[dest + x] = clip(r) << 16 | clip(g) << 8 | clip(b);
            }
        }
    }

    private static void vertical(int[] src, int w, int h, Axis a, int[] out) {
        for (int y = 0; y < h; y++) {
            int first = a.first[y] * w, n = a.count[y], ko = y * a.stride;
            for (int x = 0; x < w; x++) {
                int r = HALF, g = HALF, b = HALF;
                for (int k = 0; k < n; k++) {
                    int p = src[first + k * w + x], c = a.weight[ko + k];
                    r += (p >>> 16 & 255) * c;
                    g += (p >>> 8 & 255) * c;
                    b += (p & 255) * c;
                }
                out[y * w + x] = clip(r) << 16 | clip(g) << 8 | clip(b);
            }
        }
    }

    private record Axis(int stride, int[] first, int[] count, int[] weight, int[] index, int[] lanes) {
        static Axis of(int in, int out) {
            double scale = (double) in / out, fs = Math.max(1, scale), support = 2 * fs;
            int stride = (int) Math.ceil(2 * support) + 1;
            int[] first = new int[out], count = new int[out], weight = new int[out * stride];
            double[] tmp = new double[stride];
            for (int i = 0; i < out; i++) {
                double center = (i + 0.5) * scale, sum = 0;
                int lo = Math.max(0, (int) (center - support + 0.5));
                int n = Math.min(in, (int) (center + support + 0.5)) - lo;
                for (int k = 0; k < n; k++) sum += tmp[k] = cubic((lo + k + 0.5 - center) / fs);
                int total = 0, peak = 0, base = i * stride;
                for (int k = 0; k < n; k++) {
                    total += weight[base + k] = (int) Math.round(tmp[k] / sum * ONE);
                    if (weight[base + k] > weight[base + peak]) peak = k;
                }
                weight[base + peak] += ONE - total;
                first[i] = lo;
                count[i] = n;
            }
            int[] index = new int[out * stride], lanes = new int[out * stride];
            for (int k = 0; k < stride; k++)
                for (int i = 0; i < out; i++) {
                    index[k * out + i] = Math.min(in - 1, first[i] + k);
                    lanes[k * out + i] = weight[i * stride + k];
                }
            return new Axis(stride, first, count, weight, index, lanes);
        }
        private static double cubic(double x) {
            x = Math.abs(x);
            return x < 1 ? (1.5 * x - 2.5) * x * x + 1 : x < 2 ? ((-0.5 * x + 2.5) * x - 4) * x + 2 : 0;
        }
    }

    /** Only resolved when the optional incubator module is in the boot layer. */
    private static final class Simd {
        private static final VectorSpecies<Integer> S = IntVector.SPECIES_PREFERRED;
        static void horizontal(int[] src, int sw, int dw, int h, Axis a, int[] out) {
            int bound = S.loopBound(dw);
            for (int y = 0; y < h; y++) {
                int row = y * sw, dest = y * dw, x = 0;
                for (; x < bound; x += S.length()) {
                    IntVector r = IntVector.broadcast(S, HALF), g = r, b = r;
                    for (int k = 0; k < a.stride; k++) {
                        int base = k * dw + x;
                        IntVector p = IntVector.fromArray(S, src, row, a.index, base);
                        IntVector c = IntVector.fromArray(S, a.lanes, base);
                        r = r.add(p.lanewise(VectorOperators.LSHR, 16).and(255).mul(c));
                        g = g.add(p.lanewise(VectorOperators.LSHR, 8).and(255).mul(c));
                        b = b.add(p.and(255).mul(c));
                    }
                    r = r.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
                    g = g.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
                    b = b.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
                    r.lanewise(VectorOperators.LSHL, 16).or(g.lanewise(VectorOperators.LSHL, 8)).or(b).intoArray(out, dest + x);
                }
                for (; x < dw; x++) {
                    int first = row + a.first[x], ko = x * a.stride, n = a.count[x];
                    int r = HALF, g = HALF, b = HALF;
                    for (int k = 0; k < n; k++) {
                        int p = src[first + k], c = a.weight[ko + k];
                        r += (p >>> 16 & 255) * c;
                        g += (p >>> 8 & 255) * c;
                        b += (p & 255) * c;
                    }
                    out[dest + x] = clip(r) << 16 | clip(g) << 8 | clip(b);
                }
            }
        }
        static void vertical(int[] src, int w, int h, Axis a, int[] out) {
            int bound = S.loopBound(w);
            for (int y = 0; y < h; y++) {
                int first = a.first[y] * w, n = a.count[y], ko = y * a.stride, dest = y * w;
                int x = 0;
                for (; x < bound; x += S.length()) {
                    IntVector r = IntVector.broadcast(S, HALF), g = r, b = r;
                    for (int k = 0; k < n; k++) {
                        IntVector p = IntVector.fromArray(S, src, first + k * w + x);
                        int c = a.weight[ko + k];
                        r = r.add(p.lanewise(VectorOperators.LSHR, 16).and(255).mul(c));
                        g = g.add(p.lanewise(VectorOperators.LSHR, 8).and(255).mul(c));
                        b = b.add(p.and(255).mul(c));
                    }
                    r = r.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
                    g = g.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
                    b = b.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
                    r.lanewise(VectorOperators.LSHL, 16).or(g.lanewise(VectorOperators.LSHL, 8)).or(b).intoArray(out, dest + x);
                }
                for (; x < w; x++) {
                    int r = HALF, g = HALF, b = HALF;
                    for (int k = 0; k < n; k++) {
                        int p = src[first + k * w + x], c = a.weight[ko + k];
                        r += (p >>> 16 & 255) * c;
                        g += (p >>> 8 & 255) * c;
                        b += (p & 255) * c;
                    }
                    out[dest + x] = clip(r) << 16 | clip(g) << 8 | clip(b);
                }
            }
        }
    }
}
