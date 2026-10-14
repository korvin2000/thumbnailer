package scalebench.candidates.opus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Separable two-pass resampler on interleaved float rows. The per-pixel horizontal pass is the expensive one, so it runs
 * on the shorter side: vertical first when shrinking (source rows stream through a ring of unpacked rows), horizontal
 * first when enlarging (a ring of filtered rows). Large reductions first box-shrink by a power of two. Output rows are
 * made in groups of {@link #G}: the vertical pass walks L1-sized column chunks so the group's source rows are read from
 * L2 once, and the horizontal pass shares each weight load across the group.
 */
public final class Resampler {

    static final int G = 4;
    private static final int L1 = 20 << 10; // bytes of L1 the vertical pass may fill per column chunk

    private Resampler() {}

    /**
     * Power-of-two box pre-shrink for a ratio, keeping at least 2 source pixels per output for the kernel (less lets the
     * box alias visibly: residual 1.4 costs 4 dB on D3).
     */
    static int shrink(double ratio) { return Integer.highestOneBit(Math.clamp((int) (ratio / 2), 1, 16)); }

    /** What a size pair needs besides pixels: box factors, both axes, the horizontal weights as the pass wants them. */
    private record Plan(int bx, int by, Axis ax, Axis ay, float[] wx, Axis.Dual dual, boolean vFirst) {}

    private record Key(Pass p, int sw, int sh, int dw, int dh) {}

    private static final Map<Key, Plan> PLANS = new ConcurrentHashMap<>(); // immutable plans of recently seen sizes

    private static Plan plan(Pass p, int sw, int sh, int dw, int dh) {
        var key = new Key(p, sw, sh, dw, dh);
        var plan = PLANS.get(key);
        if (plan != null) return plan;
        int bx = shrink((double) sw / dw), by = shrink((double) sh / dh), bw = (sw + bx - 1) / bx, bh = (sh + by - 1) / by;
        // Catmull-Rom (production's kernel) when filtering directly; after a box, Lanczos3 restores the sharpness the box took
        var k = bx * by > 1 ? Axis.LANCZOS3 : Axis.CATMULL_ROM;
        Axis ax = Axis.of(bw, (double) sw / bx / dw, dw, k), ay = Axis.of(bh, (double) sh / by / dh, dh, k);
        long vFirst = (long) dh * bw * ay.taps() + (long) dh * dw * (ax.taps() + 8);
        long hFirst = (long) bh * dw * (ax.taps() + 8) + (long) dh * dw * ay.taps();
        boolean v = vFirst <= hFirst || bx * by > 1;
        plan = new Plan(bx, by, ax, ay, p.prepare(ax), v ? null : p.prepareDual(ax), v);
        if (PLANS.size() >= 64) PLANS.clear();
        PLANS.put(key, plan);
        return plan;
    }

    public static int[] resize(int[] src, int sw, int sh, int dw, int dh) { return resize(Pass.INSTANCE, src, sw, sh, dw, dh); }

    static int[] resize(Pass p, int[] src, int sw, int sh, int dw, int dh) {
        if (sw == dw && sh == dh) return src.clone();
        var pl = plan(p, sw, sh, dw, dh);
        return pl.vFirst() ? vFirst(p, src, sw, sh, dw, dh, pl) : hFirst(p, src, sw, sh, dw, dh, pl);
    }

    /** Source rows a group of output rows [y, y + G) needs at most, over all groups. */
    private static int span(Axis ay) {
        int[] first = ay.first(), count = ay.count();
        int m = 0;
        for (int y = 0; y < first.length; y += G) {
            int last = Math.min(y + G, first.length) - 1;
            m = Math.max(m, first[last] + count[last] - first[y]);
        }
        return m;
    }

    private static int chunk(int rows) { return Math.max(64, L1 / (4 * rows) & -64); }

    private static int[] vFirst(Pass p, int[] src, int sw, int sh, int dw, int dh, Plan pl) {
        Axis ax = pl.ax(), ay = pl.ay();
        int[] out = new int[dw * dh], fy = ay.first(), cy = ay.count();
        int bx = pl.bx(), by = pl.by(), bw = (sw + bx - 1) / bx, len = 4 * bw, ring = span(ay), ch = chunk(ring), taps = ay.taps();
        float[] w = ay.w();
        float[][] u = new float[ring][len], mid = new float[G][4 * (bw + 2 * ax.pairs())], tmp = new float[4][512];
        float[][][] rows = new float[G][taps][];
        short[] sums = bx * by > 1 ? new short[4 * sw] : null;
        for (int y0 = 0, next = 0; y0 < dh; y0 += G) {
            int g = Math.min(G, dh - y0), last = y0 + g - 1;
            for (; next < fy[last] + cy[last]; next++)
                if (bx * by == 1) p.unpack(src, next * sw, u[next % ring], 0, bw);
                else p.box(src, sw, next * by, Math.min(by, sh - next * by), bx, u[next % ring], 0, bw, sums);
            for (int j = 0; j < g; j++)
                for (int k = 0; k < cy[y0 + j]; k++) rows[j][k] = u[(fy[y0 + j] + k) % ring];
            for (int c = 0; c < len; c += ch)
                for (int j = 0, e = Math.min(len, c + ch); j < g; j++) p.blend(rows[j], w, (y0 + j) * taps, cy[y0 + j], mid[j], c, e);
            p.filterPack(mid, g, ax, pl.wx(), out, y0 * dw, dw, 0, dw, tmp);
        }
        return out;
    }

    private static int[] hFirst(Pass p, int[] src, int sw, int sh, int dw, int dh, Plan pl) {
        Axis ax = pl.ax(), ay = pl.ay();
        int[] out = new int[dw * dh], first = ay.first(), count = ay.count();
        int len = 4 * dw, ring = span(ay) + G, ch = chunk(span(ay)), taps = ay.taps();
        float[] w = ay.w(), line = new float[len];
        float[][] h = new float[ring][len + 4], tmp = new float[G][4 * (sw + 4 + 2 * ax.taps())], dst = new float[G][];
        float[][][] rows = new float[G][taps][];
        for (int y0 = 0, next = 0; y0 < dh; y0 += G) {
            int g = Math.min(G, dh - y0), last = y0 + g - 1, need = first[last] + count[last];
            while (next < need) {
                int b = Math.min(G, sh - next); // filter ahead in fours: the 4-row kernel shares every weight load
                for (int j = 0; j < b; j++) {
                    p.unpack(src, (next + j) * sw, tmp[j], 1, sw + 1); // one pixel of left margin
                    dst[j] = h[(next + j) % ring];
                }
                p.filter(tmp, b, ax, pl.wx(), pl.dual(), dst);
                next += b;
            }
            for (int j = 0; j < g; j++)
                for (int k = 0; k < count[y0 + j]; k++) rows[j][k] = h[(first[y0 + j] + k) % ring];
            for (int c = 0; c < len; c += ch)
                for (int j = 0, e = Math.min(len, c + ch); j < g; j++) p.blendPack(rows[j], w, (y0 + j) * taps, count[y0 + j], out, (y0 + j) * dw, c, e, line);
        }
        return out;
    }
}
