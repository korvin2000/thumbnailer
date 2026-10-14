package scalebench.candidates.opus;

/** Plain-Java kernels for runtimes without jdk.incubator.vector; the blend loops are shaped for C2's auto-vectorizer. */
final class ScalarPass extends Pass {

    @Override
    void unpack(int[] src, int off, float[] d, int i0, int i1) {
        for (int i = i0, o = off; i < i1; i++, o++) {
            int p = src[o];
            d[4 * i] = p & 255;
            d[4 * i + 1] = p >> 8 & 255;
            d[4 * i + 2] = p >> 16 & 255;
        }
    }

    @Override
    void box(int[] src, int sw, int y, int rows, int bx, float[] d, int i0, int i1, short[] tmp) { box(src, sw, y, rows, bx, d, i0, i1); }

    @Override
    void blend(float[][] rows, float[] w, int wo, int n, float[] d, int from, int to) {
        float[] r0 = rows[0];
        float c0 = w[wo];
        int k = 1;
        if (n >= 3) {
            float[] r1 = rows[1], r2 = rows[2];
            float c1 = w[wo + 1], c2 = w[wo + 2];
            for (int i = from; i < to; i++) d[i] = c0 * r0[i] + c1 * r1[i] + c2 * r2[i];
            k = 3;
        } else {
            for (int i = from; i < to; i++) d[i] = c0 * r0[i];
        }
        for (; k + 3 <= n; k += 3) {
            float[] r1 = rows[k], r2 = rows[k + 1], r3 = rows[k + 2];
            float c1 = w[wo + k], c2 = w[wo + k + 1], c3 = w[wo + k + 2];
            for (int i = from; i < to; i++) d[i] += c1 * r1[i] + c2 * r2[i] + c3 * r3[i];
        }
        for (; k < n; k++) {
            float[] r1 = rows[k];
            float c1 = w[wo + k];
            for (int i = from; i < to; i++) d[i] += c1 * r1[i];
        }
    }

    @Override
    void blendPack(float[][] rows, float[] w, int wo, int n, int[] out, int off, int from, int to, float[] d) {
        blend(rows, w, wo, n, d, from, to);
        for (int i = from; i < to; i += 4) out[off + i / 4] = clamp(d[i + 2]) << 16 | clamp(d[i + 1]) << 8 | clamp(d[i]);
    }

    @Override
    void filter(float[][] src, int g, Axis ax, float[] wx, Axis.Dual dual, float[][] dst) {
        int[] first = ax.first(), count = ax.count();
        float[] w = ax.w();
        for (int r = 0; r < g; r++) {
            float[] row = src[r], d = dst[r];
            for (int x = 0, t = ax.taps(); x < first.length; x++) {
                float b = 0, gr = 0, rd = 0;
                for (int k = 0, j = 4 * first[x] + 4, wo = x * t; k < count[x]; k++, j += 4) {
                    float c = w[wo + k];
                    b += c * row[j];
                    gr += c * row[j + 1];
                    rd += c * row[j + 2];
                }
                d[4 * x] = b;
                d[4 * x + 1] = gr;
                d[4 * x + 2] = rd;
            }
        }
    }

    @Override
    void filterPack(float[][] src, int g, Axis ax, float[] wx, int[] out, int off, int stride, int x0, int x1, float[][] tmp) {
        int[] first = ax.first(), count = ax.count();
        float[] w = ax.w();
        for (int r = 0; r < g; r++) {
            float[] row = src[r];
            for (int x = x0, t = ax.taps(), o = off + r * stride; x < x1; x++) {
                float b = 0, gr = 0, rd = 0;
                for (int k = 0, j = 4 * first[x], wo = x * t; k < count[x]; k++, j += 4) {
                    float c = w[wo + k];
                    b += c * row[j];
                    gr += c * row[j + 1];
                    rd += c * row[j + 2];
                }
                out[o + x] = clamp(rd) << 16 | clamp(gr) << 8 | clamp(b);
            }
        }
    }

    @Override float[] prepare(Axis ax) { return null; }

    @Override Axis.Dual prepareDual(Axis ax) { return null; }
}
