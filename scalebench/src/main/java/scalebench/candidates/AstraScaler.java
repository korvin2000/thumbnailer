package scalebench.candidates;

import java.util.Arrays;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;
import scalebench.Scaler;

/**
 * Single-threaded separable resampling, with a single intermediate row. Pixel centres and
 * normalized, truncated edge kernels follow Pillow. A 4x area pre-shrink plus Lanczos3
 * handles large, evenly divisible reductions; other ratios use an antialiased Keys cubic.
 * Every table and scratch buffer belongs to the call, so instances are re-entrant.
 */
public final class AstraScaler implements Scaler {
    private static final int BITS = 7, ONE = 1 << BITS, HALF = ONE / 2;
    private static final boolean VECTOR = ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();

    @Override public String name() { return "Astra"; }
    @Override public boolean supports(int sw, int sh, int dw, int dh) { return true; }

    @Override public int[] resize(int[] src, int sw, int sh, int dw, int dh) {
        if (sw == dw && sh == dh) return src.clone();
        // Exact blocks preserve the sampling geometry. Odd sizes take the direct filter.
        boolean shrink = sw / dw >= 8 && sh / dh >= 8 && (sw & 3) == 0 && (sh & 3) == 0;
        if (shrink) {
            src = VECTOR ? Simd.box4(src, sw, sh) : box4(src, sw, sh);
            sw /= 4;
            sh /= 4;
        }
        Axis ax = new Axis(sw, dw, shrink, VECTOR), ay = new Axis(sh, dh, shrink, false);
        int[] out = new int[Math.multiplyExact(dw, dh)];
        var plan = VECTOR && sw <= 3L * dw ? new Simd.Horizontal(ax, (int) ((sw + (long) dw - 1) / dw)) : null;
        // Padded loads have zero coefficients outside the image; no edge pixels are repeated.
        int[] row = new int[sw + (plan == null ? 0 : ax.stride + 3 * Simd.S.length())];
        int[] redBlue = VECTOR ? null : new int[sw], green = VECTOR ? null : new int[sw];
        for (int y = 0; y < dh; y++) {
            if (VECTOR) {
                Simd.vertical(src, sw, ay, y, row);
                Simd.horizontal(row, ax, plan, out, y * dw);
            } else {
                verticalRows(src, sw, ay, y, row, redBlue, green);
                horizontal(row, ax, out, y * dw, 0);
            }
        }
        return out;
    }

    private static int clip(int v, int bits) { return Math.clamp(v >> bits, 0, 255); }

    private static int[] box4(int[] src, int sw, int sh) {
        int[] out = new int[(sw / 4) * (sh / 4)];
        for (int y = 0, o = 0; y < sh; y += 4) {
            for (int x = 0; x < sw; x += 4) {
                int rb = 0x80008, g = 0x800;
                for (int yy = 0; yy < 4; yy++) {
                    for (int xx = 0; xx < 4; xx++) {
                        int p = src[(y + yy) * sw + x + xx];
                        rb += p & 0xff00ff;
                        g += p & 0xff00;
                    }
                }
                out[o++] = (rb >>> 4 & 0xff00ff) | (g >>> 4 & 0xff00);
            }
        }
        return out;
    }

    private static void vertical(int[] src, int sw, Axis a, int y, int[] row, int start) {
        int first = a.first[y] * sw, count = a.count[y], base = y * a.stride;
        for (int x = start; x < sw; x++) {
            int r = 1 << (a.bits - 1), g = r, b = r;
            for (int k = 0; k < count; k++) {
                int p = src[first + k * sw + x], c = a.weights[base + k];
                r += (p >>> 16 & 255) * c;
                g += (p >>> 8 & 255) * c;
                b += (p & 255) * c;
            }
            row[x] = clip(r, a.bits) << 16 | clip(g, a.bits) << 8 | clip(b, a.bits);
        }
    }

    /** Contiguous axpy loops give C2 an ordinary auto-vectorization path without the module. */
    private static void verticalRows(int[] src, int sw, Axis a, int y, int[] row, int[] rb, int[] green) {
        if (a.bits != BITS) {
            vertical(src, sw, a, y, row, 0);
            return;
        }
        Arrays.fill(rb, (16384 + HALF) * 65537);
        Arrays.fill(green, HALF);
        for (int k = 0, first = a.first[y] * sw, base = y * a.stride; k < a.count[y]; k++) {
            int c = a.weights[base + k], offset = first + k * sw;
            for (int x = 0; x < sw; x++) {
                int p = src[offset + x];
                rb[x] += (p & 0xff00ff) * c;
                green[x] += (p >>> 8 & 255) * c;
            }
        }
        for (int x = 0; x < sw; x++) row[x] = packRB(rb[x], green[x]);
    }

    private static int packRB(int rb, int g) {
        return clip((rb >>> 16) - 16384, BITS) << 16 | clip(g, BITS) << 8
                | clip((rb & 65535) - 16384, BITS);
    }

    private static void horizontal(int[] src, Axis a, int[] out, int offset, int start) {
        if (a.bits == BITS) {
            for (int x = start; x < a.size; x++) {
                int rb = (16384 + HALF) * 65537, g = HALF;
                int first = a.first[x], base = x * a.stride;
                for (int k = 0; k < a.count[x]; k++) {
                    int p = src[first + k], c = a.weights[base + k];
                    rb += (p & 0xff00ff) * c;
                    g += (p >>> 8 & 255) * c;
                }
                out[offset + x] = packRB(rb, g);
            }
            return;
        }
        for (int x = start; x < a.size; x++) {
            int r = 1 << (a.bits - 1), g = r, b = r, first = a.first[x], base = x * a.stride;
            for (int k = 0; k < a.count[x]; k++) {
                int p = src[first + k], c = a.weights[base + k];
                r += (p >>> 16 & 255) * c;
                g += (p >>> 8 & 255) * c;
                b += (p & 255) * c;
            }
            out[offset + x] = clip(r, a.bits) << 16 | clip(g, a.bits) << 8 | clip(b, a.bits);
        }
    }

    private static final class Axis {
        final int size, stride, bits;
        final int[] first, count, weights, indices, lanes;

        Axis(int in, int out, boolean lanczos, boolean horizontal) {
            size = out;
            double scale = (double) in / out, filter = Math.max(1, scale), support = (lanczos ? 3 : 2) * filter;
            stride = Math.min(in, (int) Math.ceil(2 * support));
            // Long kernels need more precision to keep small coefficients and their stopband.
            bits = stride <= 24 ? BITS : 22;
            int unit = 1 << bits;
            first = new int[out];
            count = new int[out];
            weights = new int[Math.multiplyExact(out, stride)];
            indices = horizontal && in > 3L * out ? new int[weights.length] : null;
            lanes = horizontal ? new int[weights.length] : null;
            double[] work = new double[stride];
            for (int x = 0; x < out; x++) {
                double center = (x + 0.5) * scale, sum = 0;
                int lo = Math.max(0, (int) (center - support + 0.5));
                // A centre infinitesimally below a half-integer can otherwise include
                // both zero-valued endpoints of a four-tap interpolation kernel.
                int n = Math.min(stride, Math.min(in, (int) (center + support + 0.5)) - lo);
                first[x] = lo;
                count[x] = n;
                int base = x * stride, total = 0, peak = 0;
                for (int k = 0; k < n; k++) {
                    double t = (lo + k + 0.5 - center) / filter;
                    sum += work[k] = lanczos ? lanczos(t) : cubic(t);
                }
                for (int k = 0; k < n; k++) {
                    int c = (int) Math.round(work[k] * unit / sum);
                    weights[base + k] = c;
                    total += c;
                    if (c > weights[base + peak]) peak = k;
                }
                weights[base + peak] += unit - total;
                if (horizontal) {
                    for (int k = 0; k < stride; k++) {
                        if (indices != null) indices[k * out + x] = Math.min(in - 1, lo + k);
                        lanes[k * out + x] = weights[base + k];
                    }
                }
            }
        }

        private static double cubic(double x) {
            // Keys a=-0.55 retains a little more passband detail than Catmull-Rom.
            x = Math.abs(x);
            return x < 1 ? (1.45 * x - 2.45) * x * x + 1
                    : x < 2 ? ((-0.55 * x + 2.75) * x - 4.4) * x + 2.2 : 0;
        }

        private static double lanczos(double x) {
            x = Math.abs(x);
            if (x == 0) return 1;
            if (x >= 3) return 0;
            double p = Math.PI * x;
            return Math.sin(p) * Math.sin(p / 3) / (p * p / 3);
        }
    }

    /** This class is resolved only when the incubator module is available. */
    private static final class Simd {
        private static final VectorSpecies<Integer> S = IntVector.SPECIES_PREFERRED;

        private static final class Horizontal {
            final VectorShuffle<Integer>[] shuffle;
            final VectorMask<Integer>[] second, third;
            final int groups;

            @SuppressWarnings("unchecked")
            Horizontal(Axis a, int groups) {
                // At ratios <=3, at most three contiguous vectors cover a tap's outputs.
                // Precomputed permutations and blends are substantially cheaper than gathers.
                this.groups = groups;
                shuffle = (VectorShuffle<Integer>[]) new VectorShuffle<?>[a.size / S.length()];
                second = (VectorMask<Integer>[]) new VectorMask<?>[shuffle.length];
                third = (VectorMask<Integer>[]) new VectorMask<?>[shuffle.length];
                for (int b = 0; b < shuffle.length; b++) {
                    int x = b * S.length(), first = a.first[x];
                    shuffle[b] = VectorShuffle.fromOp(S, i -> (a.first[x + i] - first) % S.length());
                    long m1 = 0, m2 = 0;
                    for (int i = 0; i < S.length(); i++) {
                        int d = a.first[x + i] - first;
                        if (d >= S.length()) m1 |= 1L << i;
                        if (d >= 2 * S.length()) m2 |= 1L << i;
                    }
                    second[b] = VectorMask.fromLong(S, m1);
                    third[b] = VectorMask.fromLong(S, m2);
                }
            }
        }

        static int[] box4(int[] src, int sw, int sh) {
            if (S.length() < 4 || sw % S.length() != 0) return AstraScaler.box4(src, sw, sh);
            var adjacent = VectorShuffle.fromOp(S, i -> i ^ 1);
            var pairs = VectorShuffle.fromOp(S, i -> i ^ 2);
            int[] out = new int[(sw / 4) * (sh / 4)];
            for (int y = 0, o = 0; y < sh; y += 4) {
                for (int x = 0; x < sw; x += S.length()) {
                    IntVector rb = IntVector.zero(S), g = rb;
                    for (int k = 0; k < 4; k++) {
                        IntVector p = IntVector.fromArray(S, src, (y + k) * sw + x);
                        rb = rb.add(p.and(0xff00ff));
                        g = g.add(p.and(0xff00));
                    }
                    rb = rb.add(rb.rearrange(adjacent));
                    rb = rb.add(rb.rearrange(pairs)).add(0x80008).lanewise(VectorOperators.LSHR, 4).and(0xff00ff);
                    g = g.add(g.rearrange(adjacent));
                    g = g.add(g.rearrange(pairs)).add(0x800).lanewise(VectorOperators.LSHR, 4).and(0xff00);
                    IntVector p = rb.or(g);
                    for (int k = 0; k < S.length(); k += 4) out[o++] = p.lane(k);
                }
            }
            return out;
        }

        private static IntVector pack(IntVector r, IntVector g, IntVector b) {
            r = r.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
            g = g.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
            b = b.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
            return r.lanewise(VectorOperators.LSHL, 16).or(g.lanewise(VectorOperators.LSHL, 8)).or(b);
        }

        private static IntVector packRB(IntVector rb, IntVector g) {
            // Q7 lets R and B share one multiply in independent 16-bit fields. The
            // 16384 bias accommodates negative lobes without borrowing across fields;
            // these short kernels have <64 units of negative coefficient mass.
            IntVector r = rb.lanewise(VectorOperators.LSHR, 16).sub(16384);
            IntVector b = rb.and(65535).sub(16384);
            return pack(r, g, b);
        }

        static void vertical(int[] src, int sw, Axis a, int y, int[] row) {
            if (a.bits != BITS) {
                verticalWide(src, sw, a, y, row);
                return;
            }
            int first = a.first[y] * sw, count = a.count[y], base = y * a.stride;
            int end = S.loopBound(sw), x = 0;
            for (; x < end; x += S.length()) {
                IntVector rb = IntVector.broadcast(S, (16384 + HALF) * 65537), g = IntVector.broadcast(S, HALF);
                for (int k = 0; k < count; k++) {
                    IntVector p = IntVector.fromArray(S, src, first + k * sw + x);
                    int c = a.weights[base + k];
                    rb = rb.add(p.and(0xff00ff).mul(c));
                    g = g.add(p.lanewise(VectorOperators.LSHR, 8).and(255).mul(c));
                }
                packRB(rb, g).intoArray(row, x);
            }
            AstraScaler.vertical(src, sw, a, y, row, x);
        }

        static void horizontal(int[] src, Axis a, Horizontal plan, int[] out, int offset) {
            if (a.bits != BITS) {
                horizontalWide(src, a, out, offset);
                return;
            }
            int end = S.loopBound(a.size), x = 0;
            for (; x < end; x += S.length()) {
                IntVector rb = IntVector.broadcast(S, (16384 + HALF) * 65537), g = IntVector.broadcast(S, HALF);
                for (int k = 0; k < a.stride; k++) {
                    int o = k * a.size + x;
                    IntVector p;
                    if (plan == null) {
                        p = IntVector.fromArray(S, src, 0, a.indices, o);
                    } else {
                        p = IntVector.fromArray(S, src, a.first[x] + k);
                        int block = x / S.length();
                        var shuffle = plan.shuffle[block];
                        p = p.rearrange(shuffle);
                        if (plan.groups >= 2) p = p.blend(IntVector.fromArray(S, src,
                                a.first[x] + k + S.length()).rearrange(shuffle), plan.second[block]);
                        if (plan.groups >= 3) p = p.blend(IntVector.fromArray(S, src,
                                a.first[x] + k + 2 * S.length()).rearrange(shuffle), plan.third[block]);
                    }
                    IntVector c = IntVector.fromArray(S, a.lanes, o);
                    rb = rb.add(p.and(0xff00ff).mul(c));
                    g = g.add(p.lanewise(VectorOperators.LSHR, 8).and(255).mul(c));
                }
                packRB(rb, g).intoArray(out, offset + x);
            }
            AstraScaler.horizontal(src, a, out, offset, x);
        }

        private static IntVector packWide(IntVector r, IntVector g, IntVector b) {
            r = r.lanewise(VectorOperators.ASHR, 22).max(0).min(255);
            g = g.lanewise(VectorOperators.ASHR, 22).max(0).min(255);
            b = b.lanewise(VectorOperators.ASHR, 22).max(0).min(255);
            return r.lanewise(VectorOperators.LSHL, 16).or(g.lanewise(VectorOperators.LSHL, 8)).or(b);
        }

        private static void verticalWide(int[] src, int sw, Axis a, int y, int[] row) {
            int first = a.first[y] * sw, count = a.count[y], base = y * a.stride;
            int end = S.loopBound(sw), x = 0;
            for (; x < end; x += S.length()) {
                IntVector r = IntVector.broadcast(S, 1 << 21), g = r, b = r;
                for (int k = 0; k < count; k++) {
                    IntVector p = IntVector.fromArray(S, src, first + k * sw + x);
                    int c = a.weights[base + k];
                    r = r.add(p.lanewise(VectorOperators.LSHR, 16).and(255).mul(c));
                    g = g.add(p.lanewise(VectorOperators.LSHR, 8).and(255).mul(c));
                    b = b.add(p.and(255).mul(c));
                }
                packWide(r, g, b).intoArray(row, x);
            }
            AstraScaler.vertical(src, sw, a, y, row, x);
        }

        private static void horizontalWide(int[] src, Axis a, int[] out, int offset) {
            int end = S.loopBound(a.size), x = 0;
            for (; x < end; x += S.length()) {
                IntVector r = IntVector.broadcast(S, 1 << 21), g = r, b = r;
                for (int k = 0; k < a.stride; k++) {
                    int o = k * a.size + x;
                    IntVector p = IntVector.fromArray(S, src, 0, a.indices, o);
                    IntVector c = IntVector.fromArray(S, a.lanes, o);
                    r = r.add(p.lanewise(VectorOperators.LSHR, 16).and(255).mul(c));
                    g = g.add(p.lanewise(VectorOperators.LSHR, 8).and(255).mul(c));
                    b = b.add(p.and(255).mul(c));
                }
                packWide(r, g, b).intoArray(out, offset + x);
            }
            AstraScaler.horizontal(src, a, out, offset, x);
        }
    }
}
