package scalebench.candidates.fable;

import static jdk.incubator.vector.VectorOperators.F2I;
import static jdk.incubator.vector.VectorOperators.I2B;
import static jdk.incubator.vector.VectorOperators.I2F;
import static jdk.incubator.vector.VectorOperators.S2I;
import static jdk.incubator.vector.VectorOperators.ZERO_EXTEND_B2I;
import static jdk.incubator.vector.VectorOperators.ZERO_EXTEND_B2S;

import java.util.Arrays;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * 256-bit Vector API pipeline. A pixel is four float lanes [B,G,R,X] (a packed XRGB int widens to it with one
 * vpmovzxbd). Output rows are made in pairs: the vertical pass walks the union of the two rows' tap windows once
 * (lanes along x, 8 pixels per iteration) and stores the pair interleaved per cell, [row y | row y+1]; the horizontal
 * pass then needs one load, one broadcast weight and one FMA per tap for both rows, and no fold. Box rows sum 16-bit
 * lanes and fold 64-bit (pixel) lanes.
 * <p>
 * Two rules keep C2 on the fast path. No helper takes or returns a vector: once hot it is compiled on its own and
 * never inlined again, which boxes every vector it touches. And every kernel uses the same shapes (256-bit lanes,
 * 64-bit only for packed pixel pairs): the JDK's shared templates profile the species they see, and a kernel using
 * 128-bit vectors makes the others compile with type checks and slow fallbacks. Loaded only when the module is present.
 */
final class Simd {

    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256, I64 = IntVector.SPECIES_64;
    private static final VectorSpecies<Float> F256 = FloatVector.SPECIES_256;
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;
    private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;
    private static final VectorSpecies<Long> L256 = LongVector.SPECIES_256;
    private static final VectorShuffle<Long> SWAP_PAIRS = VectorShuffle.fromValues(L256, 1, 0, 3, 2),
            SWAP_HALVES = VectorShuffle.fromValues(L256, 2, 3, 0, 1), EVENS = VectorShuffle.fromValues(L256, 0, 2, 0, 2);
    private static final VectorShuffle<Float> SWAP_HALVES_F = VectorShuffle.fromValues(F256, 4, 5, 6, 7, 0, 1, 2, 3);

    private Simd() {}

    static boolean usable() { return IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256; }

    static void run(int[] src, int sw, int sh, int dw, int dh, int kx, int ky, Axis ax, Axis ay, int[] out) {
        if (dh <= sh) shrinkRows(src, sw, sh, dw, dh, kx, ky, ax, ay, out);
        else growRows(src, sw, sh, dw, dh, ax, ay, out);
    }

    /** Power of two above {@code taps + taps / 4 + 1}, the longest union window of two rows: the ring holds it whole. */
    private static int ringRows(int taps) { return Integer.highestOneBit(taps + taps / 4 + 1) << 1; }

    /** Union window of output rows y and yB: rows {@code min(first)..} with the two rows' weights zero padded. */
    private static int union(Axis a, int y, int yB, float[] wA, float[] wB) {
        int fA = a.first()[y], nA = a.count()[y], fB = a.first()[yB], nB = a.count()[yB], taps = a.taps();
        int f = Math.min(fA, fB), r = Math.max(fA + nA, fB + nB) - f, dA = fA - f, dB = fB - f;
        float[] w = a.weight();
        for (int i = 0; i < r; i++) {
            wA[i] = i >= dA && i - dA < nA ? w[y * taps + i - dA] : 0;
            wB[i] = i >= dB && i - dB < nB ? w[yB * taps + i - dB] : 0;
        }
        return r;
    }

    /** Rows shrink (or stay): row pairs are resampled vertically into one interleaved float row, then horizontally. */
    private static void shrinkRows(int[] src, int sw, int sh, int dw, int dh, int kx, int ky, Axis ax, Axis ay, int[] out) {
        int bw = (sw + kx - 1) / kx, rowPx = bw + 7 & ~7, stride = (rowPx + 8) * 4, taps = ay.taps();
        boolean boxed = kx > 1 || ky > 1;
        var rowf2 = new float[(rowPx + ax.taps() + 8) * 8];
        var wA = new float[2 * taps + 4];
        var wB = new float[2 * taps + 4];
        int ringRows = boxed ? ringRows(taps) : 0, mask = ringRows - 1, produced = 0;
        float[] ring = boxed ? new float[ringRows * stride] : null;
        var rowOff = new int[2 * taps + 4];
        float inv = 1f / (kx * ky);
        for (int y = 0; y < dh; y += 2) {
            int yB = Math.min(y + 1, dh - 1), f = Math.min(ay.first()[y], ay.first()[yB]), r = union(ay, y, yB, wA, wB);
            if (boxed) {
                for (; produced < f + r; produced++) boxRow(src, sw, sh, kx, ky, bw, produced, ring, (produced & mask) * stride, inv);
                for (int t = 0; t < r; t++) rowOff[t] = (f + t & mask) * stride;
                vRowsRing(ring, rowOff, r, wA, wB, rowPx, rowf2, null, 0, 0);
            } else vRowsBytes(src, sw, f, r, wA, wB, rowf2);
            hRows(rowf2, ax, out, y * dw, yB * dw, dw);
        }
    }

    /** Rows grow: source row pairs are resampled horizontally into a ring, output row pairs vertically from the ring. */
    private static void growRows(int[] src, int sw, int sh, int dw, int dh, Axis ax, Axis ay, int[] out) {
        int rowPx = dw + 7 & ~7, stride = (rowPx + 8) * 4, taps = ay.taps(), ringRows = ringRows(taps), mask = ringRows - 1, produced = 0;
        var ring = new float[ringRows * stride];
        int[] last = Arrays.copyOfRange(src, (sh - 1) * sw, sh * sw + ax.taps()); // zero padded: the taps overrun the row
        var wA = new float[2 * taps + 4];
        var wB = new float[2 * taps + 4];
        var rowOff = new int[2 * taps + 4];
        float[] hw = expand(ax);
        for (int y = 0; y < dh; y += 2) {
            int yB = Math.min(y + 1, dh - 1), f = Math.min(ay.first()[y], ay.first()[yB]), r = union(ay, y, yB, wA, wB);
            for (; produced < f + r; produced += 2) {
                int p1 = produced + 1;
                int[] s0 = produced < sh - 1 ? src : last, s1 = p1 < sh - 1 ? src : last;
                int ro0 = produced < sh - 1 ? produced * sw : 0, ro1 = p1 < sh - 1 ? p1 * sw : 0;
                hRowsBytes(s0, ro0, p1 < sh ? s1 : s0, p1 < sh ? ro1 : ro0, ax, hw, ring, (produced & mask) * stride, p1 < sh ? (p1 & mask) * stride : -1, dw);
            }
            for (int t = 0; t < r; t++) rowOff[t] = (f + t & mask) * stride;
            vRowsRing(ring, rowOff, r, wA, wB, dw, null, out, y * dw, yB * dw);
        }
    }

    /** Weights four lanes wide, {@code hw[(x * taps + t) * 4 + c]}: 2 taps of one pixel per vector. */
    private static float[] expand(Axis a) {
        float[] w = a.weight(), e = new float[w.length * 4];
        for (int i = 0; i < w.length; i++) Arrays.fill(e, i * 4, i * 4 + 4, w[i]);
        return e;
    }

    // ---- box rows: kx-by-ky means of packed pixels as floats (a cell store writes 8 floats, the row has slack) ----

    static void boxRow(int[] src, int sw, int sh, int kx, int ky, int bw, int by, float[] ring, int o, float inv) {
        int y0 = by * ky, done = 0;
        if (sh - y0 >= ky) {
            int row = y0 * sw, cols = sw / kx;
            switch (kx) {
                case 1 -> {
                    for (int x = 0; x + 8 <= sw; x += 8) {
                        int i = row + x;
                        ByteVector p = IntVector.fromArray(I256, src, i).reinterpretAsBytes();
                        ShortVector s0 = (ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 0), s1 = (ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 1);
                        for (int r = 1; r < ky; r++) {
                            p = IntVector.fromArray(I256, src, i + r * sw).reinterpretAsBytes();
                            s0 = s0.add((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 0));
                            s1 = s1.add((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 1));
                        }
                        ((FloatVector) ((IntVector) s0.convertShape(S2I, I256, 0)).convertShape(I2F, F256, 0)).mul(inv).intoArray(ring, o + x * 4);
                        ((FloatVector) ((IntVector) s0.convertShape(S2I, I256, 1)).convertShape(I2F, F256, 0)).mul(inv).intoArray(ring, o + x * 4 + 8);
                        ((FloatVector) ((IntVector) s1.convertShape(S2I, I256, 0)).convertShape(I2F, F256, 0)).mul(inv).intoArray(ring, o + x * 4 + 16);
                        ((FloatVector) ((IntVector) s1.convertShape(S2I, I256, 1)).convertShape(I2F, F256, 0)).mul(inv).intoArray(ring, o + x * 4 + 24);
                    }
                    done = sw & ~7;
                }
                case 2 -> {
                    for (int c = 0; c + 4 <= cols; c += 4) {
                        int i = row + c * 2;
                        ByteVector p = IntVector.fromArray(I256, src, i).reinterpretAsBytes();
                        ShortVector s0 = (ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 0), s1 = (ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 1);
                        for (int r = 1; r < ky; r++) {
                            p = IntVector.fromArray(I256, src, i + r * sw).reinterpretAsBytes();
                            s0 = s0.add((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 0));
                            s1 = s1.add((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 1));
                        }
                        ShortVector t0 = s0.add(s0.reinterpretAsLongs().rearrange(SWAP_PAIRS).reinterpretAsShorts()), t1 = s1.add(s1.reinterpretAsLongs().rearrange(SWAP_PAIRS).reinterpretAsShorts());
                        t0 = t0.reinterpretAsLongs().rearrange(EVENS).reinterpretAsShorts();
                        t1 = t1.reinterpretAsLongs().rearrange(EVENS).reinterpretAsShorts();
                        ((FloatVector) ((IntVector) t0.convertShape(S2I, I256, 0)).convertShape(I2F, F256, 0)).mul(inv).intoArray(ring, o + c * 4);
                        ((FloatVector) ((IntVector) t1.convertShape(S2I, I256, 0)).convertShape(I2F, F256, 0)).mul(inv).intoArray(ring, o + c * 4 + 8);
                    }
                    done = cols & ~3;
                }
                case 4 -> {
                    for (int c = 0; c + 2 <= cols; c += 2) {
                        int i = row + c * 4;
                        ByteVector p = IntVector.fromArray(I256, src, i).reinterpretAsBytes();
                        ShortVector s0 = (ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 0), s1 = (ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 1);
                        for (int r = 1; r < ky; r++) {
                            p = IntVector.fromArray(I256, src, i + r * sw).reinterpretAsBytes();
                            s0 = s0.add((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 0));
                            s1 = s1.add((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 1));
                        }
                        ShortVector t0 = s0.add(s0.reinterpretAsLongs().rearrange(SWAP_PAIRS).reinterpretAsShorts()), t1 = s1.add(s1.reinterpretAsLongs().rearrange(SWAP_PAIRS).reinterpretAsShorts());
                        t0 = t0.add(t0.reinterpretAsLongs().rearrange(SWAP_HALVES).reinterpretAsShorts());
                        t1 = t1.add(t1.reinterpretAsLongs().rearrange(SWAP_HALVES).reinterpretAsShorts());
                        ((FloatVector) ((IntVector) t0.convertShape(S2I, I256, 0)).convertShape(I2F, F256, 0)).mul(inv).intoArray(ring, o + c * 4);
                        ((FloatVector) ((IntVector) t1.convertShape(S2I, I256, 0)).convertShape(I2F, F256, 0)).mul(inv).intoArray(ring, o + c * 4 + 4);
                    }
                    done = cols & ~1;
                }
                default -> {
                    for (int c = 0; c < cols; c++) {
                        int i = row + c * 8;
                        ByteVector p = IntVector.fromArray(I256, src, i).reinterpretAsBytes();
                        ShortVector s = ((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 0)).add((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 1));
                        for (int r = 1; r < ky; r++) {
                            p = IntVector.fromArray(I256, src, i + r * sw).reinterpretAsBytes();
                            s = s.add((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 0)).add((ShortVector) p.convertShape(ZERO_EXTEND_B2S, S256, 1));
                        }
                        ShortVector t = s.add(s.reinterpretAsLongs().rearrange(SWAP_PAIRS).reinterpretAsShorts());
                        t = t.add(t.reinterpretAsLongs().rearrange(SWAP_HALVES).reinterpretAsShorts());
                        ((FloatVector) ((IntVector) t.convertShape(S2I, I256, 0)).convertShape(I2F, F256, 0)).mul(inv).intoArray(ring, o + c * 4);
                    }
                    done = cols;
                }
            }
        }
        for (int bx = done; bx < bw; bx++) Fable.boxCell(src, sw, sh, kx, ky, bx, by, ring, o + bx * 4);
    }

    // ---- vertical pass: two output rows from the union window of their taps, lanes along x, 8 pixels per iteration ----

    /** Rows A and B from packed source rows {@code f..f+r-1} into the interleaved float row (padding gets garbage). */
    static void vRowsBytes(int[] src, int sw, int f, int r, float[] wA, float[] wB, float[] rowf2) {
        FloatVector zero = FloatVector.zero(F256);
        VectorMask<Float> hi = VectorMask.fromLong(F256, 0xF0);
        int groups = sw >> 3;
        for (int g = 0; g < groups; g++) {
            int x = g << 3;
            FloatVector a0 = zero, a1 = zero, a2 = zero, a3 = zero, b0 = zero, b1 = zero, b2 = zero, b3 = zero;
            for (int t = 0, si = f * sw + x; t < r; t++, si += sw) {
                FloatVector wa = FloatVector.broadcast(F256, wA[t]), wb = FloatVector.broadcast(F256, wB[t]);
                FloatVector d0 = (FloatVector) ((IntVector) IntVector.fromArray(I64, src, si).reinterpretAsBytes().convertShape(ZERO_EXTEND_B2I, I256, 0)).convertShape(I2F, F256, 0);
                FloatVector d1 = (FloatVector) ((IntVector) IntVector.fromArray(I64, src, si + 2).reinterpretAsBytes().convertShape(ZERO_EXTEND_B2I, I256, 0)).convertShape(I2F, F256, 0);
                FloatVector d2 = (FloatVector) ((IntVector) IntVector.fromArray(I64, src, si + 4).reinterpretAsBytes().convertShape(ZERO_EXTEND_B2I, I256, 0)).convertShape(I2F, F256, 0);
                FloatVector d3 = (FloatVector) ((IntVector) IntVector.fromArray(I64, src, si + 6).reinterpretAsBytes().convertShape(ZERO_EXTEND_B2I, I256, 0)).convertShape(I2F, F256, 0);
                a0 = d0.fma(wa, a0);
                a1 = d1.fma(wa, a1);
                a2 = d2.fma(wa, a2);
                a3 = d3.fma(wa, a3);
                b0 = d0.fma(wb, b0);
                b1 = d1.fma(wb, b1);
                b2 = d2.fma(wb, b2);
                b3 = d3.fma(wb, b3);
            }
            int o = x * 8; // [A px | B px] per cell
            a0.blend(b0.rearrange(SWAP_HALVES_F), hi).intoArray(rowf2, o);
            a0.rearrange(SWAP_HALVES_F).blend(b0, hi).intoArray(rowf2, o + 8);
            a1.blend(b1.rearrange(SWAP_HALVES_F), hi).intoArray(rowf2, o + 16);
            a1.rearrange(SWAP_HALVES_F).blend(b1, hi).intoArray(rowf2, o + 24);
            a2.blend(b2.rearrange(SWAP_HALVES_F), hi).intoArray(rowf2, o + 32);
            a2.rearrange(SWAP_HALVES_F).blend(b2, hi).intoArray(rowf2, o + 40);
            a3.blend(b3.rearrange(SWAP_HALVES_F), hi).intoArray(rowf2, o + 48);
            a3.rearrange(SWAP_HALVES_F).blend(b3, hi).intoArray(rowf2, o + 56);
        }
        for (int x = groups << 3; x < sw; x++) { // up to 7 tail pixels per row
            float ba = 0, ga = 0, ra = 0, bb = 0, gb = 0, rb = 0;
            for (int t = 0, si = f * sw + x; t < r; t++, si += sw) {
                int p = src[si];
                float b = p & 255, g = p >>> 8 & 255, rr = p >>> 16 & 255, wa = wA[t], wb = wB[t];
                ba += wa * b;
                ga += wa * g;
                ra += wa * rr;
                bb += wb * b;
                gb += wb * g;
                rb += wb * rr;
            }
            int o = x * 8;
            rowf2[o] = ba;
            rowf2[o + 1] = ga;
            rowf2[o + 2] = ra;
            rowf2[o + 4] = bb;
            rowf2[o + 5] = gb;
            rowf2[o + 6] = rb;
        }
    }

    /**
     * Rows A and B from {@code r} float ring rows at {@code rowOff}: {@code wPx} pixels into the interleaved float row
     * {@code rowf2} (a multiple of 8), or packed into {@code out} at {@code ooA} and {@code ooB}.
     */
    static void vRowsRing(float[] ring, int[] rowOff, int r, float[] wA, float[] wB, int wPx, float[] rowf2, int[] out, int ooA, int ooB) {
        FloatVector zero = FloatVector.zero(F256);
        VectorMask<Float> hi = VectorMask.fromLong(F256, 0xF0);
        int x = 0;
        for (; x + 8 <= wPx; x += 8) {
            FloatVector a0 = zero, a1 = zero, a2 = zero, a3 = zero, b0 = zero, b1 = zero, b2 = zero, b3 = zero;
            for (int t = 0; t < r; t++) {
                int si = rowOff[t] + x * 4;
                FloatVector wa = FloatVector.broadcast(F256, wA[t]), wb = FloatVector.broadcast(F256, wB[t]);
                FloatVector d0 = FloatVector.fromArray(F256, ring, si), d1 = FloatVector.fromArray(F256, ring, si + 8);
                FloatVector d2 = FloatVector.fromArray(F256, ring, si + 16), d3 = FloatVector.fromArray(F256, ring, si + 24);
                a0 = d0.fma(wa, a0);
                a1 = d1.fma(wa, a1);
                a2 = d2.fma(wa, a2);
                a3 = d3.fma(wa, a3);
                b0 = d0.fma(wb, b0);
                b1 = d1.fma(wb, b1);
                b2 = d2.fma(wb, b2);
                b3 = d3.fma(wb, b3);
            }
            if (out == null) {
                int o = x * 8;
                a0.blend(b0.rearrange(SWAP_HALVES_F), hi).intoArray(rowf2, o);
                a0.rearrange(SWAP_HALVES_F).blend(b0, hi).intoArray(rowf2, o + 8);
                a1.blend(b1.rearrange(SWAP_HALVES_F), hi).intoArray(rowf2, o + 16);
                a1.rearrange(SWAP_HALVES_F).blend(b1, hi).intoArray(rowf2, o + 24);
                a2.blend(b2.rearrange(SWAP_HALVES_F), hi).intoArray(rowf2, o + 32);
                a2.rearrange(SWAP_HALVES_F).blend(b2, hi).intoArray(rowf2, o + 40);
                a3.blend(b3.rearrange(SWAP_HALVES_F), hi).intoArray(rowf2, o + 48);
                a3.rearrange(SWAP_HALVES_F).blend(b3, hi).intoArray(rowf2, o + 56);
            } else {
                IntVector v = ((IntVector) a0.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
                ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts().intoArray(out, ooA + x);
                v = ((IntVector) a1.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
                ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts().intoArray(out, ooA + x + 2);
                v = ((IntVector) a2.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
                ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts().intoArray(out, ooA + x + 4);
                v = ((IntVector) a3.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
                ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts().intoArray(out, ooA + x + 6);
                v = ((IntVector) b0.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
                ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts().intoArray(out, ooB + x);
                v = ((IntVector) b1.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
                ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts().intoArray(out, ooB + x + 2);
                v = ((IntVector) b2.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
                ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts().intoArray(out, ooB + x + 4);
                v = ((IntVector) b3.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
                ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts().intoArray(out, ooB + x + 6);
            }
        }
        for (; x < wPx; x += 2) { // packed tail: 2 pixels, or the last single one (ring rows are padded)
            FloatVector a0 = zero, b0 = zero;
            for (int t = 0; t < r; t++) {
                FloatVector d = FloatVector.fromArray(F256, ring, rowOff[t] + x * 4);
                a0 = d.fma(FloatVector.broadcast(F256, wA[t]), a0);
                b0 = d.fma(FloatVector.broadcast(F256, wB[t]), b0);
            }
            IntVector v = ((IntVector) a0.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
            IntVector p = ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts();
            v = ((IntVector) b0.add(0.5f).convertShape(F2I, I256, 0)).max(0).min(255);
            IntVector q = ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts();
            int p0 = p.lane(0), p1 = p.lane(1), q0 = q.lane(0), q1 = q.lane(1);
            out[ooA + x] = p0;
            out[ooB + x] = q0;
            if (x + 1 < wPx) {
                out[ooA + x + 1] = p1;
                out[ooB + x + 1] = q1;
            }
        }
    }

    // ---- horizontal pass on an interleaved row pair: one load, one broadcast weight and one FMA per tap ----

    /** Packed output rows {@code ooA} (lanes 0-3) and {@code ooB} (lanes 4-7) from the interleaved float row. */
    static void hRows(float[] rowf2, Axis ax, int[] out, int ooA, int ooB, int dw) {
        FloatVector half = FloatVector.broadcast(F256, 0.5f);
        int taps = ax.taps();
        int[] first = ax.first();
        float[] w = ax.weight();
        int x = 0;
        for (; x + 4 <= dw; x += 4) {
            FloatVector a = half, b = half, c = half, d = half;
            int sa = first[x] * 8, sb = first[x + 1] * 8, sc = first[x + 2] * 8, sd = first[x + 3] * 8, wo = x * taps;
            for (int t = 0; t < taps; t++, sa += 8, sb += 8, sc += 8, sd += 8, wo++) {
                a = FloatVector.fromArray(F256, rowf2, sa).fma(FloatVector.broadcast(F256, w[wo]), a);
                b = FloatVector.fromArray(F256, rowf2, sb).fma(FloatVector.broadcast(F256, w[wo + taps]), b);
                c = FloatVector.fromArray(F256, rowf2, sc).fma(FloatVector.broadcast(F256, w[wo + 2 * taps]), c);
                d = FloatVector.fromArray(F256, rowf2, sd).fma(FloatVector.broadcast(F256, w[wo + 3 * taps]), d);
            }
            IntVector v = ((IntVector) a.convertShape(F2I, I256, 0)).max(0).min(255);
            IntVector p = ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts();
            out[ooA + x] = p.lane(0);
            out[ooB + x] = p.lane(1);
            v = ((IntVector) b.convertShape(F2I, I256, 0)).max(0).min(255);
            p = ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts();
            out[ooA + x + 1] = p.lane(0);
            out[ooB + x + 1] = p.lane(1);
            v = ((IntVector) c.convertShape(F2I, I256, 0)).max(0).min(255);
            p = ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts();
            out[ooA + x + 2] = p.lane(0);
            out[ooB + x + 2] = p.lane(1);
            v = ((IntVector) d.convertShape(F2I, I256, 0)).max(0).min(255);
            p = ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts();
            out[ooA + x + 3] = p.lane(0);
            out[ooB + x + 3] = p.lane(1);
        }
        for (; x < dw; x++) {
            FloatVector a = half;
            for (int t = 0, sa = first[x] * 8, wo = x * taps; t < taps; t++, sa += 8, wo++)
                a = FloatVector.fromArray(F256, rowf2, sa).fma(FloatVector.broadcast(F256, w[wo]), a);
            IntVector v = ((IntVector) a.convertShape(F2I, I256, 0)).max(0).min(255);
            IntVector p = ((ByteVector) v.convertShape(I2B, B64, 0)).reinterpretAsInts();
            out[ooA + x] = p.lane(0);
            out[ooB + x] = p.lane(1);
        }
    }

    // ---- horizontal pass on packed source rows (growing height): 2 taps per vector, two rows share the weights ----

    /** Float ring rows {@code o0} and {@code o1} from packed source rows ({@code o1 < 0}: the second row is a duplicate, stored once). */
    static void hRowsBytes(int[] s0, int ro0, int[] s1, int ro1, Axis ax, float[] hw, float[] ring, int o0, int o1, int dw) {
        FloatVector zero = FloatVector.zero(F256);
        int taps = ax.taps(), st = taps * 4, oB = o1 < 0 ? o0 : o1;
        int[] first = ax.first();
        for (int x = 0; x < dw; x++) {
            FloatVector a = zero, b = zero;
            for (int t = 0, sa = ro0 + first[x], sb = ro1 + first[x], wo = x * st; t < taps; t += 2, sa += 2, sb += 2, wo += 8) {
                FloatVector wv = FloatVector.fromArray(F256, hw, wo);
                a = ((FloatVector) ((IntVector) IntVector.fromArray(I64, s0, sa).reinterpretAsBytes().convertShape(ZERO_EXTEND_B2I, I256, 0)).convertShape(I2F, F256, 0)).fma(wv, a);
                b = ((FloatVector) ((IntVector) IntVector.fromArray(I64, s1, sb).reinterpretAsBytes().convertShape(ZERO_EXTEND_B2I, I256, 0)).convertShape(I2F, F256, 0)).fma(wv, b);
            }
            a.add(a.rearrange(SWAP_HALVES_F)).intoArray(ring, o0 + x * 4); // lanes 0-3 are the pixel, 4-7 land on the next slot
            b.add(b.rearrange(SWAP_HALVES_F)).intoArray(ring, oB + x * 4);
        }
    }
}
