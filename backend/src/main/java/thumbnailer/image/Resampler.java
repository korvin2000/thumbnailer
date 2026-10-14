package thumbnailer.image;

import java.util.Arrays;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Resizes {@link Pixels} in either direction: separable, single-threaded, one intermediate row. Pillow's geometry
 * (pixel centres at {@code i + 0.5}, support widened by the ratio when shrinking, edge kernels truncated and
 * renormalized). A large reduction first averages power-of-two boxes (2, 4 or 8; exact area means, the image cropped
 * to whole boxes) while at least 1.9x remains for a Lanczos3, which also finishes a source that arrives area-averaged;
 * other ratios use an antialiased Keys cubic (a = -0.55).
 * Short kernels run in Q7 with red and blue sharing one multiply in 16-bit fields; long ones in Q22 per channel.
 * Two paths, same bits: SIMD through the Vector API when {@code jdk.incubator.vector} is present (launch with
 * {@code --add-modules jdk.incubator.vector}) and vectors hold 8+ ints, else scalar loops that C2 auto-vectorizes. Chosen in a 4-way competition, see
 * scalebench/README.md: the gpt-6-astra candidate with the power-of-two box of the Fable and Opus ones.
 */
final class Resampler {

    private static final int BITS = 7, HALF = 1 << (BITS - 1), RB = (16384 + HALF) * 65537;
    /** The faster path where it exists: the Vector API module is present and vectors hold at least 8 ints. */
    static final boolean SIMD = ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent() && Simd.S.length() >= 8;

    private Resampler() {}

    /**
     * {@code boxed}: the source is already area-averaged (a DCT-scaled JPEG decode), so a shrink takes the Lanczos3
     * as after our own box: a 24 MP JPEG decoded at 1/4 then shrunk to 512 px scored 50.8 dB against 42.9 dB with
     * the cubic (decoding at 1/2 instead: 50.6 dB for 11 ms more).
     */
    static Pixels resize(Pixels src, int w, int h, boolean boxed) { return resize(src, w, h, boxed, SIMD); }

    /**
     * Runs every SIMD kernel shape until C2 has compiled it. Interpreted, the Vector API boxes each vector: the first
     * resize took 0.5 s instead of 4 ms, and the first album opened right after start rendered in 1 s instead of 0.25.
     * About a second of one core, on a background thread at start.
     */
    static void warmUp() {
        if (!SIMD) return;
        int[][] shapes = { // source, target, boxed: cubic 2 groups, DCT-scaled Lanczos 3 and 4 groups, 4x box
                {900, 600, 512, 341, 0}, {1500, 1000, 512, 341, 1}, {2000, 1500, 512, 384, 1}, {3072, 2048, 384, 256, 0}};
        for (int[] s : shapes) {
            var p = new Pixels(new int[s[0] * s[1]], s[0], s[1]);
            for (int i = 0; i < 12; i++) resize(p, s[2], s[3], s[4] == 1, true);
        }
    }

    /** {@code simd} picks the path (tests compare both); the two compute the same bits. */
    static Pixels resize(Pixels src, int w, int h, boolean boxed, boolean simd) {
        int sw = src.width(), sh = src.height();
        if (w < 1 || h < 1) throw new IllegalArgumentException("target must be at least 1x1, got %dx%d".formatted(w, h));
        if (w == sw && h == sh) return src;
        int[] in = src.argb();
        int f = box(sw, sh, w, h);
        if (f > 1) {
            in = simd ? Simd.box(in, sw, sh, f) : box(in, sw, sh, f, 0);
            sw /= f;
            sh /= f;
        }
        boolean lanczos = (f > 1 || boxed) && w < sw && h < sh;
        var ax = new Axis(sw, w, lanczos, simd);
        var ay = new Axis(sh, h, lanczos, false);
        int[] out = new int[Math.multiplyExact(w, h)];
        int groups = (int) ((sw + (long) w - 1) / w);
        var plan = simd && groups <= 4 ? new Simd.Horizontal(ax, groups) : null;
        // Padded loads meet zero coefficients beyond the image: no edge pixel is repeated.
        int[] row = new int[sw + (plan == null ? 0 : ax.stride + groups * Simd.S.length())];
        int[] rb = simd ? null : new int[sw], g = simd ? null : new int[sw];
        for (int y = 0; y < h; y++) {
            if (simd) {
                Simd.vertical(in, sw, ay, y, row);
                Simd.horizontal(row, ax, plan, out, y * w);
            } else {
                verticalRows(in, sw, ay, y, row, rb, g);
                horizontal(row, ax, out, y * w, 0);
            }
        }
        return new Pixels(out, w, h);
    }

    /**
     * The largest power-of-two box, at most 8, that leaves both axes at least 1.9x for the Lanczos3 and crops at most
     * 1/64 of either axis to whole boxes (a 16000x23 strip keeps all its rows). At 2x a 12 MP image (ratio 7.8) took
     * a 2x box and 24-tap kernels: 53.9 dB in 18 ms; at 1.9x a 4x box and 12 taps: 48.1 dB (the old resampler: 45.5)
     * in 6.8 ms.
     */
    static int box(int sw, int sh, int w, int h) {
        int f = 1;
        for (int g = 2; g <= 8 && sw / (double) g >= 1.9 * w && sh / (double) g >= 1.9 * h
                && sw % g * 64 <= sw && sh % g * 64 <= sh; g *= 2) f = g;
        return f;
    }

    /** Means of f x f blocks (columns {@code from} on), rounded; red and blue share one int in 16-bit fields. */
    private static int[] box(int[] src, int sw, int sh, int f, int from) {
        int w = sw / f, h = sh / f, shift = 2 * Integer.numberOfTrailingZeros(f), half = 1 << (shift - 1);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = from; x < w; x++) {
                int rb = half * 0x10001, g = half << 8;
                for (int yy = 0, at = y * f * sw + x * f; yy < f; yy++, at += sw)
                    for (int xx = 0; xx < f; xx++) {
                        int p = src[at + xx];
                        rb += p & 0xff00ff;
                        g += p & 0xff00;
                    }
                out[y * w + x] = rb >>> shift & 0xff00ff | g >>> shift & 0xff00;
            }
        return out;
    }

    private static int clip(int v, int bits) { return Math.clamp(v >> bits, 0, 255); }

    private static int packRB(int rb, int g) {
        return clip((rb >>> 16) - 16384, BITS) << 16 | clip(g, BITS) << 8 | clip((rb & 65535) - 16384, BITS);
    }

    private static void vertical(int[] src, int sw, Axis a, int y, int[] row, int from) {
        int first = a.first[y] * sw, count = a.count[y], base = y * a.stride;
        for (int x = from; x < sw; x++) {
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

    /** Contiguous axpy loops: C2's ordinary auto-vectorization path when the Vector API is absent. */
    private static void verticalRows(int[] src, int sw, Axis a, int y, int[] row, int[] rb, int[] green) {
        if (a.bits != BITS) {
            vertical(src, sw, a, y, row, 0);
            return;
        }
        Arrays.fill(rb, RB);
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

    private static void horizontal(int[] src, Axis a, int[] out, int offset, int from) {
        for (int x = from; x < a.size; x++) {
            int first = a.first[x], base = x * a.stride;
            if (a.bits == BITS) {
                int rb = RB, g = HALF;
                for (int k = 0; k < a.count[x]; k++) {
                    int p = src[first + k], c = a.weights[base + k];
                    rb += (p & 0xff00ff) * c;
                    g += (p >>> 8 & 255) * c;
                }
                out[offset + x] = packRB(rb, g);
            } else {
                int r = 1 << (a.bits - 1), g = r, b = r;
                for (int k = 0; k < a.count[x]; k++) {
                    int p = src[first + k], c = a.weights[base + k];
                    r += (p >>> 16 & 255) * c;
                    g += (p >>> 8 & 255) * c;
                    b += (p & 255) * c;
                }
                out[offset + x] = clip(r, a.bits) << 16 | clip(g, a.bits) << 8 | clip(b, a.bits);
            }
        }
    }

    /** Per output coordinate: first source index, tap count and {@code stride} fixed-point weights summing to one. */
    private static final class Axis {
        final int size, stride, bits;
        final int[] first, count, weights, indices, lanes;

        Axis(int in, int out, boolean lanczos, boolean horizontal) {
            size = out;
            double scale = (double) in / out, filter = Math.max(1, scale), support = (lanczos ? 3 : 2) * filter;
            stride = Math.min(in, (int) Math.ceil(2 * support));
            // Q7 leaves 16-bit fields enough headroom for short kernels; long ones need Q22 for their small taps.
            bits = stride <= 24 ? BITS : 22;
            int unit = 1 << bits;
            first = new int[out];
            count = new int[out];
            weights = new int[Math.multiplyExact(out, stride)];
            indices = horizontal && in > 4L * out ? new int[weights.length] : null;
            lanes = horizontal ? new int[weights.length] : null;
            double[] work = new double[stride];
            for (int x = 0; x < out; x++) {
                double center = (x + 0.5) * scale, sum = 0;
                int lo = Math.max(0, (int) (center - support + 0.5));
                // A centre just below a half-integer could otherwise take both zero ends of a 4-tap kernel.
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
                weights[base + peak] += unit - total; // rounding residue to the largest tap: constants stay exact
                if (horizontal)
                    for (int k = 0; k < stride; k++) {
                        if (indices != null) indices[k * out + x] = Math.min(in - 1, lo + k);
                        lanes[k * out + x] = weights[base + k];
                    }
            }
        }

        /** Keys cubic, a = -0.55: a little more passband than Catmull-Rom. */
        private static double cubic(double x) {
            x = Math.abs(x);
            return x < 1 ? (1.45 * x - 2.45) * x * x + 1 : x < 2 ? ((-0.55 * x + 2.75) * x - 4.4) * x + 2.2 : 0;
        }

        private static double lanczos(double x) {
            x = Math.abs(x);
            if (x == 0) return 1;
            if (x >= 3) return 0;
            double p = Math.PI * x;
            return Math.sin(p) * Math.sin(p / 3) / (p * p / 3);
        }
    }

    /**
     * Resolved only when the incubator module is present. C2 keeps these kernels in registers only in this shape:
     * constant shifts, separate Q7 and Q22 methods, unrolled blends; a loop over vectors or a variable shift made the
     * same arithmetic up to 2x slower.
     */
    private static final class Simd {
        static final VectorSpecies<Integer> S = IntVector.SPECIES_PREFERRED;

        /** Up to a ratio of 4, at most four contiguous vectors hold a tap's sources: shuffles and blends, no gathers. */
        static final class Horizontal {
            final VectorShuffle<Integer>[] shuffle;
            final VectorMask<Integer>[] second, third, fourth; // lanes whose source lies in vector 2, 3, 4
            final int groups;

            @SuppressWarnings("unchecked")
            Horizontal(Axis a, int groups) {
                this.groups = groups;
                int blocks = a.size / S.length();
                shuffle = (VectorShuffle<Integer>[]) new VectorShuffle<?>[blocks];
                second = (VectorMask<Integer>[]) new VectorMask<?>[blocks];
                third = (VectorMask<Integer>[]) new VectorMask<?>[blocks];
                fourth = (VectorMask<Integer>[]) new VectorMask<?>[blocks];
                for (int b = 0; b < blocks; b++) {
                    int x = b * S.length(), first = a.first[x];
                    shuffle[b] = VectorShuffle.fromOp(S, i -> (a.first[x + i] - first) % S.length());
                    long m1 = 0, m2 = 0, m3 = 0;
                    for (int i = 0; i < S.length(); i++) {
                        int d = a.first[x + i] - first;
                        if (d >= S.length()) m1 |= 1L << i;
                        if (d >= 2 * S.length()) m2 |= 1L << i;
                        if (d >= 3 * S.length()) m3 |= 1L << i;
                    }
                    second[b] = VectorMask.fromLong(S, m1);
                    third[b] = VectorMask.fromLong(S, m2);
                    fourth[b] = VectorMask.fromLong(S, m3);
                }
            }
        }

        /**
         * f rows summed per vector, lane pairs folded log2(f) times (lane k*f then holds a block's sum), the block
         * sums gathered to the front and stored under a mask; the scalar box does the columns past whole vectors.
         */
        @SuppressWarnings("unchecked")
        static int[] box(int[] src, int sw, int sh, int f) {
            int n = S.length(), w = sw / f, h = sh / f, shift = 2 * Integer.numberOfTrailingZeros(f), half = 1 << (shift - 1);
            if (n < f) return Resampler.box(src, sw, sh, f, 0);
            var folds = (VectorShuffle<Integer>[]) new VectorShuffle<?>[Integer.numberOfTrailingZeros(f)];
            for (int s = 0; s < folds.length; s++) {
                int bit = 1 << s;
                folds[s] = VectorShuffle.fromOp(S, i -> i ^ bit);
            }
            var front = VectorShuffle.fromOp(S, i -> i * f % n);
            var outputs = VectorMask.fromLong(S, (1L << n / f) - 1);
            int covered = w * f / n * n, step = n / f;
            int[] out = Resampler.box(src, sw, sh, f, covered / f);
            for (int y = 0; y < h; y++)
                for (int x = 0, o = y * w; x < covered; x += n, o += step) {
                    IntVector rb = IntVector.zero(S), g = rb;
                    for (int k = 0, at = y * f * sw + x; k < f; k++, at += sw) {
                        IntVector p = IntVector.fromArray(S, src, at);
                        rb = rb.add(p.and(0xff00ff));
                        g = g.add(p.and(0xff00));
                    }
                    for (var fold : folds) {
                        rb = rb.add(rb.rearrange(fold));
                        g = g.add(g.rearrange(fold));
                    }
                    rb.add(half * 0x10001).lanewise(VectorOperators.LSHR, shift).and(0xff00ff)
                            .or(g.add(half << 8).lanewise(VectorOperators.LSHR, shift).and(0xff00))
                            .rearrange(front).intoArray(out, o, outputs);
                }
            return out;
        }

        private static IntVector pack(IntVector r, IntVector g, IntVector b) {
            r = r.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
            g = g.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
            b = b.lanewise(VectorOperators.ASHR, BITS).max(0).min(255);
            return r.lanewise(VectorOperators.LSHL, 16).or(g.lanewise(VectorOperators.LSHL, 8)).or(b);
        }

        /** Q7 R and B in 16-bit fields; the 16384 bias absorbs the negative lobes without a borrow across fields. */
        private static IntVector packRB(IntVector rb, IntVector g) {
            return pack(rb.lanewise(VectorOperators.LSHR, 16).sub(16384), g, rb.and(65535).sub(16384));
        }

        private static IntVector packWide(IntVector r, IntVector g, IntVector b) {
            r = r.lanewise(VectorOperators.ASHR, 22).max(0).min(255);
            g = g.lanewise(VectorOperators.ASHR, 22).max(0).min(255);
            b = b.lanewise(VectorOperators.ASHR, 22).max(0).min(255);
            return r.lanewise(VectorOperators.LSHL, 16).or(g.lanewise(VectorOperators.LSHL, 8)).or(b);
        }

        static void vertical(int[] src, int sw, Axis a, int y, int[] row) {
            if (a.bits != BITS) {
                verticalWide(src, sw, a, y, row);
                return;
            }
            int first = a.first[y] * sw, count = a.count[y], base = y * a.stride, end = S.loopBound(sw), x = 0;
            for (; x < end; x += S.length()) {
                IntVector rb = IntVector.broadcast(S, RB), g = IntVector.broadcast(S, HALF);
                for (int k = 0; k < count; k++) {
                    IntVector p = IntVector.fromArray(S, src, first + k * sw + x);
                    int c = a.weights[base + k];
                    rb = rb.add(p.and(0xff00ff).mul(c));
                    g = g.add(p.lanewise(VectorOperators.LSHR, 8).and(255).mul(c));
                }
                packRB(rb, g).intoArray(row, x);
            }
            Resampler.vertical(src, sw, a, y, row, x);
        }

        private static void verticalWide(int[] src, int sw, Axis a, int y, int[] row) {
            int first = a.first[y] * sw, count = a.count[y], base = y * a.stride, end = S.loopBound(sw), x = 0;
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
            Resampler.vertical(src, sw, a, y, row, x);
        }

        static void horizontal(int[] src, Axis a, Horizontal plan, int[] out, int offset) {
            if (a.bits != BITS) {
                horizontalWide(src, a, out, offset);
                return;
            }
            int end = S.loopBound(a.size), x = 0;
            for (; x < end; x += S.length()) {
                IntVector rb = IntVector.broadcast(S, RB), g = IntVector.broadcast(S, HALF);
                for (int k = 0; k < a.stride; k++) {
                    int o = k * a.size + x;
                    IntVector p;
                    if (plan == null) {
                        p = IntVector.fromArray(S, src, 0, a.indices, o);
                    } else {
                        int at = a.first[x] + k, block = x / S.length();
                        var shuffle = plan.shuffle[block];
                        p = IntVector.fromArray(S, src, at).rearrange(shuffle);
                        if (plan.groups >= 2) p = p.blend(IntVector.fromArray(S, src, at + S.length()).rearrange(shuffle), plan.second[block]);
                        if (plan.groups >= 3) p = p.blend(IntVector.fromArray(S, src, at + 2 * S.length()).rearrange(shuffle), plan.third[block]);
                        if (plan.groups >= 4) p = p.blend(IntVector.fromArray(S, src, at + 3 * S.length()).rearrange(shuffle), plan.fourth[block]);
                    }
                    IntVector c = IntVector.fromArray(S, a.lanes, o);
                    rb = rb.add(p.and(0xff00ff).mul(c));
                    g = g.add(p.lanewise(VectorOperators.LSHR, 8).and(255).mul(c));
                }
                packRB(rb, g).intoArray(out, offset + x);
            }
            Resampler.horizontal(src, a, out, offset, x);
        }

        /** Q22 kernels are long (ratio above 4, so {@code indices} exists): gathered loads. */
        private static void horizontalWide(int[] src, Axis a, int[] out, int offset) {
            int end = S.loopBound(a.size), x = 0;
            for (; x < end; x += S.length()) {
                IntVector r = IntVector.broadcast(S, 1 << 21), g = r, b = r;
                for (int k = 0; k < a.stride; k++) {
                    int o = k * a.size + x;
                    IntVector p = IntVector.fromArray(S, src, 0, a.indices, o), c = IntVector.fromArray(S, a.lanes, o);
                    r = r.add(p.lanewise(VectorOperators.LSHR, 16).and(255).mul(c));
                    g = g.add(p.lanewise(VectorOperators.LSHR, 8).and(255).mul(c));
                    b = b.add(p.and(255).mul(c));
                }
                packWide(r, g, b).intoArray(out, offset + x);
            }
            Resampler.horizontal(src, a, out, offset, x);
        }
    }
}
