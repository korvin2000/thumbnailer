package scalebench.candidates.opus;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vector API kernels: 8 float lanes = 2 interleaved pixels. Only loaded when jdk.incubator.vector is present. Methods
 * take and return arrays only (a vector crossing a call C2 declines to inline gets boxed), and stay small enough for
 * C2's inlining node budget.
 */
final class VecPass extends Pass {

    private static final VectorSpecies<Float> F = FloatVector.SPECIES_256;
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_256, I2 = IntVector.SPECIES_64, I4 = IntVector.SPECIES_128;
    private static final VectorSpecies<Byte> B8 = ByteVector.SPECIES_64;
    private static final VectorSpecies<Short> S = ShortVector.SPECIES_256;
    private static final VectorSpecies<Long> L = LongVector.SPECIES_256;
    private static final VectorMask<Float> HI = VectorMask.fromLong(F, 0xF0);
    private static final VectorShuffle<Float> SWAP = VectorShuffle.fromValues(F, 4, 5, 6, 7, 0, 1, 2, 3);
    private static final VectorShuffle<Long> P1032 = VectorShuffle.fromValues(L, 1, 0, 3, 2), P2301 = VectorShuffle.fromValues(L, 2, 3, 0, 1),
            P0213 = VectorShuffle.fromValues(L, 0, 2, 1, 3);
    private static final VectorMask<Long> M0101 = VectorMask.fromLong(L, 0b1010);
    private static final float MAGIC = 0x1.8p23f; // v + MAGIC holds round(v) in its low mantissa bits
    private static final int CHUNK = 128; // output pixels per horizontal chunk (4 rows x 2 KB of floats)

    @Override
    void unpack(int[] src, int off, float[] dst, int i0, int i1) {
        int i = i0, o = off - i0;
        for (; i <= i1 - 2; i += 2)
            ((FloatVector) IntVector.fromArray(I2, src, o + i).reinterpretAsBytes()
                    .convertShape(VectorOperators.ZERO_EXTEND_B2I, I, 0).convert(VectorOperators.I2F, 0)).intoArray(dst, 4 * i);
        for (; i < i1; i++) {
            int p = src[o + i];
            dst[4 * i] = p & 255;
            dst[4 * i + 1] = p >> 8 & 255;
            dst[4 * i + 2] = p >> 16 & 255;
        }
    }

    /**
     * Power-of-two box widths: rows are summed in 16-bit lanes (4 interleaved pixels per vector, sums fit while
     * bx * rows <= 257), then each box's pixels are folded with 64-bit lane shuffles (one pixel = 4 shorts).
     */
    @Override
    void box(int[] src, int sw, int y, int rows, int bx, float[] d, int i0, int i1, short[] v) {
        int iv = i0 + (Math.min(i1, sw / bx) - i0 & -2); // box columns [i0, iv) done in pairs by the vector stages
        if (bx < 2 || bx > 16 || (bx & bx - 1) != 0 || bx * rows > 257 || iv < i0) iv = i0;
        int x = bx * i0, n = bx * iv;
        for (; x <= n - 16; x += 16) {
            var a0 = ShortVector.zero(S);
            var a1 = a0; var a2 = a0; var a3 = a0;
            for (int j = 0, o = y * sw + x; j < rows; j++, o += sw) {
                a0 = a0.add(ext(src, o));
                a1 = a1.add(ext(src, o + 4));
                a2 = a2.add(ext(src, o + 8));
                a3 = a3.add(ext(src, o + 12));
            }
            a0.intoArray(v, 4 * x);
            a1.intoArray(v, 4 * x + 16);
            a2.intoArray(v, 4 * x + 32);
            a3.intoArray(v, 4 * x + 48);
        }
        for (; x < n; x += 4) {
            var a = ShortVector.zero(S);
            for (int j = 0, o = y * sw + x; j < rows; j++, o += sw) a = a.add(ext(src, o));
            a.intoArray(v, 4 * x);
        }
        var s = FloatVector.broadcast(F, 1f / (bx * rows));
        if (bx == 2) {
            for (int i = i0; i < iv; i += 2) {
                var l = ShortVector.fromArray(S, v, 8 * i).reinterpretAsLongs();
                widen(l.add(l.rearrange(P1032)).rearrange(P0213), s, d, 4 * i);
            }
        } else {
            for (int i = i0, m = 4 * bx; i < iv; i += 2) {
                var a = ShortVector.fromArray(S, v, m * i);
                var b = ShortVector.fromArray(S, v, m * i + m);
                for (int q = 16; q < m; q += 16) {
                    a = a.add(ShortVector.fromArray(S, v, m * i + q));
                    b = b.add(ShortVector.fromArray(S, v, m * i + m + q));
                }
                var la = a.reinterpretAsLongs();
                var lb = b.reinterpretAsLongs();
                var w = la.add(la.rearrange(P1032)).blend(lb.add(lb.rearrange(P1032)), M0101); // [a01, b01, a23, b23]
                widen(w.add(w.rearrange(P2301)), s, d, 4 * i);
            }
        }
        box(src, sw, y, rows, bx, d, iv, i1);
    }

    /** Four pixels (16 bytes) zero-extended to 16-bit lanes. */
    private static ShortVector ext(int[] src, int o) {
        return (ShortVector) IntVector.fromArray(I4, src, o).reinterpretAsBytes().convertShape(VectorOperators.ZERO_EXTEND_B2S, S, 0);
    }

    /** Lanes 0 and 1 (two pixels of 4 shorts) as 8 floats, scaled, into d[o ..]. */
    private static void widen(LongVector l, FloatVector s, float[] d, int o) {
        ((FloatVector) l.reinterpretAsShorts().convertShape(VectorOperators.ZERO_EXTEND_S2I, I, 0).convert(VectorOperators.I2F, 0)).mul(s).intoArray(d, o);
    }

    /**
     * Axpy sweeps of up to 4 taps over [from, to): the inner index is linear, so C2 hoists the range checks and unrolls,
     * which beats keeping the sum in registers across all taps.
     */
    @Override
    void blend(float[][] rows, float[] w, int wo, int n, float[] d, int from, int to) {
        int end = from + (to - from & -8);
        for (int k = 0; k < n; k += 4) {
            int m = Math.min(4, n - k);
            if (m == 4) sweep4(rows[k], rows[k + 1], rows[k + 2], rows[k + 3], w[wo + k], w[wo + k + 1], w[wo + k + 2], w[wo + k + 3], d, from, end, k > 0);
            else if (m == 3) sweep3(rows[k], rows[k + 1], rows[k + 2], w[wo + k], w[wo + k + 1], w[wo + k + 2], d, from, end, k > 0);
            else if (m == 2) sweep2(rows[k], rows[k + 1], w[wo + k], w[wo + k + 1], d, from, end, k > 0);
            else sweep1(rows[k], w[wo + k], d, from, end, k > 0);
        }
        for (int i = end; i < to; i++) {
            float a = 0;
            for (int j = 0; j < n; j++) a += w[wo + j] * rows[j][i];
            d[i] = a;
        }
    }

    private static void sweep4(float[] r0, float[] r1, float[] r2, float[] r3, float w0, float w1, float w2, float w3, float[] d, int from, int to, boolean add) {
        var c0 = FloatVector.broadcast(F, w0);
        var c1 = FloatVector.broadcast(F, w1);
        var c2 = FloatVector.broadcast(F, w2);
        var c3 = FloatVector.broadcast(F, w3);
        if (add)
            for (int i = from; i < to; i += 8)
                FloatVector.fromArray(F, r3, i).fma(c3, FloatVector.fromArray(F, r2, i).fma(c2,
                        FloatVector.fromArray(F, r1, i).fma(c1, FloatVector.fromArray(F, r0, i).fma(c0, FloatVector.fromArray(F, d, i))))).intoArray(d, i);
        else
            for (int i = from; i < to; i += 8)
                FloatVector.fromArray(F, r3, i).fma(c3, FloatVector.fromArray(F, r2, i).fma(c2,
                        FloatVector.fromArray(F, r1, i).fma(c1, FloatVector.fromArray(F, r0, i).mul(c0)))).intoArray(d, i);
    }

    private static void sweep3(float[] r0, float[] r1, float[] r2, float w0, float w1, float w2, float[] d, int from, int to, boolean add) {
        var c0 = FloatVector.broadcast(F, w0);
        var c1 = FloatVector.broadcast(F, w1);
        var c2 = FloatVector.broadcast(F, w2);
        if (add)
            for (int i = from; i < to; i += 8)
                FloatVector.fromArray(F, r2, i).fma(c2, FloatVector.fromArray(F, r1, i).fma(c1, FloatVector.fromArray(F, r0, i).fma(c0, FloatVector.fromArray(F, d, i)))).intoArray(d, i);
        else
            for (int i = from; i < to; i += 8)
                FloatVector.fromArray(F, r2, i).fma(c2, FloatVector.fromArray(F, r1, i).fma(c1, FloatVector.fromArray(F, r0, i).mul(c0))).intoArray(d, i);
    }

    private static void sweep2(float[] r0, float[] r1, float w0, float w1, float[] d, int from, int to, boolean add) {
        var c0 = FloatVector.broadcast(F, w0);
        var c1 = FloatVector.broadcast(F, w1);
        if (add)
            for (int i = from; i < to; i += 8)
                FloatVector.fromArray(F, r1, i).fma(c1, FloatVector.fromArray(F, r0, i).fma(c0, FloatVector.fromArray(F, d, i))).intoArray(d, i);
        else
            for (int i = from; i < to; i += 8) FloatVector.fromArray(F, r1, i).fma(c1, FloatVector.fromArray(F, r0, i).mul(c0)).intoArray(d, i);
    }

    private static void sweep1(float[] r0, float w0, float[] d, int from, int to, boolean add) {
        var c0 = FloatVector.broadcast(F, w0);
        if (add)
            for (int i = from; i < to; i += 8) FloatVector.fromArray(F, r0, i).fma(c0, FloatVector.fromArray(F, d, i)).intoArray(d, i);
        else
            for (int i = from; i < to; i += 8) FloatVector.fromArray(F, r0, i).mul(c0).intoArray(d, i);
    }

    @Override
    void blendPack(float[][] rows, float[] w, int wo, int n, int[] out, int off, int from, int to, float[] tmp) {
        int end = from;
        if (n <= 4) { // enlarging: sum up to 4 taps and pack in one sweep, missing taps weigh 0
            end = from + (to - from & -8);
            packSweep(rows[0], rows[Math.min(1, n - 1)], rows[Math.min(2, n - 1)], rows[Math.min(3, n - 1)], w[wo], n > 1 ? w[wo + 1] : 0,
                    n > 2 ? w[wo + 2] : 0, n > 3 ? w[wo + 3] : 0, out, off, from / 4, end / 4);
        }
        if (end < to) {
            blend(rows, w, wo, n, tmp, end, to);
            pack(tmp, end, to, out, off + end / 4);
        }
    }

    private static void packSweep(float[] r0, float[] r1, float[] r2, float[] r3, float w0, float w1, float w2, float w3, int[] out, int off, int x0, int x1) {
        var c0 = FloatVector.broadcast(F, w0);
        var c1 = FloatVector.broadcast(F, w1);
        var c2 = FloatVector.broadcast(F, w2);
        var c3 = FloatVector.broadcast(F, w3);
        for (int x = x0; x < x1; x += 2)
            FloatVector.fromArray(F, r3, 4 * x).fma(c3, FloatVector.fromArray(F, r2, 4 * x).fma(c2,
                            FloatVector.fromArray(F, r1, 4 * x).fma(c1, FloatVector.fromArray(F, r0, 4 * x).mul(c0))))
                    .max(0f).min(255f).add(MAGIC).reinterpretAsInts().convertShape(VectorOperators.I2B, B8, 0).reinterpretAsInts().intoArray(out, off + x);
    }

    /** Rounds, clamps and packs the pixels in floats d[from, to) into out[o ..]. */
    private static void pack(float[] d, int from, int to, int[] out, int o) {
        int i = from;
        for (; i <= to - 8; i += 8, o += 2)
            FloatVector.fromArray(F, d, i).max(0f).min(255f).add(MAGIC).reinterpretAsInts()
                    .convertShape(VectorOperators.I2B, B8, 0).reinterpretAsInts().intoArray(out, o);
        if (i < to) out[o] = clamp(d[i + 2]) << 16 | clamp(d[i + 1]) << 8 | clamp(d[i]);
    }

    @Override
    void filter(float[][] src, int g, Axis ax, float[] wx, Axis.Dual dual, float[][] dst) {
        int r1 = Math.min(1, g - 1), r2 = Math.min(2, g - 1), r3 = Math.min(3, g - 1);
        if (dual != null) {
            dual4(src[0], src[r1], src[r2], src[r3], dual, dst[0], dst[r1], dst[r2], dst[r3]);
            return;
        }
        int n = ax.first().length, even = n & -2;
        h4(src[0], src[r1], src[r2], src[r3], ax, wx, 4, 0, even, dst[0], dst[r1], dst[r2], dst[r3], 0);
        if (even < n)
            for (int j = 0; j < g; j++) one(src[j], 4, ax, even, dst[j], 4 * even);
    }

    @Override
    void filterPack(float[][] src, int g, Axis ax, float[] wx, int[] out, int off, int stride, int x0, int x1, float[][] tmp) {
        int even = x0 + (x1 - x0 & -2);
        for (int r = 0; r < g; r += 4) {
            int r1 = Math.min(r + 1, g - 1), r2 = Math.min(r + 2, g - 1), r3 = Math.min(r + 3, g - 1);
            for (int x = x0; x < even; x += CHUNK) {
                int e = Math.min(even, x + CHUNK), len = 4 * (e - x);
                h4(src[r], src[r1], src[r2], src[r3], ax, wx, 0, x, e, tmp[0], tmp[1], tmp[2], tmp[3], 4 * x);
                for (int j = r; j < Math.min(r + 4, g); j++) pack(tmp[j - r], 0, len, out, off + j * stride + x);
            }
        }
        if (even < x1)
            for (int j = 0; j < g; j++) {
                float[] t = tmp[0];
                one(src[j], 0, ax, even, t, 0);
                out[off + j * stride + even] = clamp(t[2]) << 16 | clamp(t[1]) << 8 | clamp(t[0]);
            }
    }

    /**
     * Output pixels [x0, x1) (an even count) of four rows whose pixel s sits at 4 s + m; each weight load feeds four
     * rows, two outputs per step keep eight FMA chains in flight. Writes pixel x at d[4 x - base].
     */
    static void h4(float[] s0, float[] s1, float[] s2, float[] s3, Axis ax, float[] wx, int m, int x0, int x1,
                   float[] d0, float[] d1, float[] d2, float[] d3, int base) {
        int[] first = ax.first();
        int ws = ax.pairs() * 8;
        for (int x = x0; x < x1; x += 2) {
            int p0 = 4 * first[x] + m, p1 = 4 * first[x + 1] + m, w0 = x * ws, w1 = w0 + ws;
            var a0 = FloatVector.zero(F); // all loads inside the pair loop: its range checks are hoisted
            var a1 = a0; var b0 = a0; var b1 = a0; var c0 = a0; var c1 = a0; var e0 = a0; var e1 = a0;
            for (int p = 0; p < ws; p += 8) {
                var u = FloatVector.fromArray(F, wx, w0 + p);
                var v = FloatVector.fromArray(F, wx, w1 + p);
                a0 = FloatVector.fromArray(F, s0, p0 + p).fma(u, a0);
                a1 = FloatVector.fromArray(F, s0, p1 + p).fma(v, a1);
                b0 = FloatVector.fromArray(F, s1, p0 + p).fma(u, b0);
                b1 = FloatVector.fromArray(F, s1, p1 + p).fma(v, b1);
                c0 = FloatVector.fromArray(F, s2, p0 + p).fma(u, c0);
                c1 = FloatVector.fromArray(F, s2, p1 + p).fma(v, c1);
                e0 = FloatVector.fromArray(F, s3, p0 + p).fma(u, e0);
                e1 = FloatVector.fromArray(F, s3, p1 + p).fma(v, e1);
            }
            // fold: [x.lo + x.hi, (x+1).lo + (x+1).hi] = the two pixel sums
            int o = 4 * x - base;
            a0.blend(a1, HI).add(a1.blend(a0, HI).rearrange(SWAP)).intoArray(d0, o);
            b0.blend(b1, HI).add(b1.blend(b0, HI).rearrange(SWAP)).intoArray(d1, o);
            c0.blend(c1, HI).add(c1.blend(c0, HI).rearrange(SWAP)).intoArray(d2, o);
            e0.blend(e1, HI).add(e1.blend(e0, HI).rearrange(SWAP)).intoArray(d3, o);
        }
    }

    /** {@link Axis.Dual} pairs of outputs for four rows, two pairs per step (eight FMA chains); pair q lands at d[8 q ..]. */
    private static void dual4(float[] s0, float[] s1, float[] s2, float[] s3, Axis.Dual dl, float[] d0, float[] d1, float[] d2, float[] d3) {
        int[] base = dl.base();
        float[] w = dl.w();
        int k = dl.k(), n = base.length, q = 0;
        for (; q <= n - 2; q += 2) {
            int p0 = base[q], p1 = base[q + 1], w0 = 8 * k * q, w1 = w0 + 8 * k;
            var a0 = FloatVector.zero(F);
            var a1 = a0; var b0 = a0; var b1 = a0; var c0 = a0; var c1 = a0; var e0 = a0; var e1 = a0;
            for (int s = 0; s < k; s++) {
                var u = FloatVector.fromArray(F, w, w0 + 8 * s);
                var v = FloatVector.fromArray(F, w, w1 + 8 * s);
                int i0 = p0 + 4 * s, i1 = p1 + 4 * s;
                a0 = FloatVector.fromArray(F, s0, i0).fma(u, a0);
                a1 = FloatVector.fromArray(F, s0, i1).fma(v, a1);
                b0 = FloatVector.fromArray(F, s1, i0).fma(u, b0);
                b1 = FloatVector.fromArray(F, s1, i1).fma(v, b1);
                c0 = FloatVector.fromArray(F, s2, i0).fma(u, c0);
                c1 = FloatVector.fromArray(F, s2, i1).fma(v, c1);
                e0 = FloatVector.fromArray(F, s3, i0).fma(u, e0);
                e1 = FloatVector.fromArray(F, s3, i1).fma(v, e1);
            }
            int o = 8 * q;
            a0.intoArray(d0, o);
            a1.intoArray(d0, o + 8);
            b0.intoArray(d1, o);
            b1.intoArray(d1, o + 8);
            c0.intoArray(d2, o);
            c1.intoArray(d2, o + 8);
            e0.intoArray(d3, o);
            e1.intoArray(d3, o + 8);
        }
        if (q < n) {
            int p0 = base[q], w0 = 8 * k * q;
            var a0 = FloatVector.zero(F);
            var b0 = a0; var c0 = a0; var e0 = a0;
            for (int s = 0; s < k; s++) {
                var u = FloatVector.fromArray(F, w, w0 + 8 * s);
                int i0 = p0 + 4 * s;
                a0 = FloatVector.fromArray(F, s0, i0).fma(u, a0);
                b0 = FloatVector.fromArray(F, s1, i0).fma(u, b0);
                c0 = FloatVector.fromArray(F, s2, i0).fma(u, c0);
                e0 = FloatVector.fromArray(F, s3, i0).fma(u, e0);
            }
            a0.intoArray(d0, 8 * q);
            b0.intoArray(d1, 8 * q);
            c0.intoArray(d2, 8 * q);
            e0.intoArray(d3, 8 * q);
        }
    }

    @Override float[] prepare(Axis ax) { return ax.expanded(); }

    @Override Axis.Dual prepareDual(Axis ax) { return ax.dual(); }
}
