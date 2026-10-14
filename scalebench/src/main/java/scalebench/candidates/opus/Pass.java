package scalebench.candidates.opus;

/**
 * Row kernels on interleaved float rows (4 lanes per pixel in memory byte order B, G, R, X; X is garbage that rides
 * along). {@link #INSTANCE} is the Vector API implementation when jdk.incubator.vector is present, else scalar.
 */
abstract class Pass {

    static final Pass INSTANCE = load();

    /** dst[4 i ..] = the XRGB pixel src[off + i - i0] as floats, for i0 <= i < i1. */
    abstract void unpack(int[] src, int off, float[] dst, int i0, int i1);

    /**
     * dst[4 i ..] = mean of box column i (bx pixels wide, the last one may be narrower) over source rows [y, y + rows),
     * for i0 <= i < i1; tmp holds 4 * sw shorts.
     */
    abstract void box(int[] src, int sw, int y, int rows, int bx, float[] dst, int i0, int i1, short[] tmp);

    /** dst[from .. to) = sum over k < n of w[wo + k] * rows[k][from .. to). */
    abstract void blend(float[][] rows, float[] w, int wo, int n, float[] dst, int from, int to);

    /** {@link #blend} of floats [from, to) via tmp (a row as long as to), rounded, clamped and packed into out[off + from / 4 ..]. */
    abstract void blendPack(float[][] rows, float[] w, int wo, int n, int[] out, int off, int from, int to, float[] tmp);

    /**
     * Horizontal pass of rows src[0 .. g) (g <= 4, pixel s at 4 s + 4: one pixel of left margin) into dst[0 .. g) (4 floats
     * per output pixel, 4 floats of slack), with {@link #prepare} weights or, when not null, {@link #prepareDual} ones.
     */
    abstract void filter(float[][] src, int g, Axis ax, float[] wx, Axis.Dual dual, float[][] dst);

    /**
     * Output pixels [x0, x1) of the horizontal pass of rows src[0 .. g), rounded, clamped and packed into
     * out[off + r * stride + x]; tmp holds 4 rows of 512 floats.
     */
    abstract void filterPack(float[][] src, int g, Axis ax, float[] wx, int[] out, int off, int stride, int x0, int x1, float[][] tmp);

    /** Expanded weights this implementation wants for the horizontal pass (null if it reads {@link Axis#w}). */
    abstract float[] prepare(Axis ax);

    /** The two-outputs-per-vector layout for {@link #filter}, or null to use {@link #prepare}. */
    abstract Axis.Dual prepareDual(Axis ax);

    static int clamp(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    static void box(int[] src, int sw, int y, int rows, int bx, float[] d, int i0, int i1) {
        for (int i = i0; i < i1; i++) {
            int x0 = i * bx, cw = Math.min(bx, sw - x0), b = 0, g = 0, r = 0;
            for (int j = 0; j < rows; j++)
                for (int c = 0, o = (y + j) * sw + x0; c < cw; c++) {
                    int p = src[o + c];
                    b += p & 255;
                    g += p >> 8 & 255;
                    r += p >> 16 & 255;
                }
            float s = 1f / (cw * rows);
            d[4 * i] = b * s;
            d[4 * i + 1] = g * s;
            d[4 * i + 2] = r * s;
        }
    }

    /** Output pixel x of one row whose pixel s sits at 4 s + m (scalar, all 4 lanes) into d[o .. o + 4). */
    static void one(float[] s, int m, Axis ax, int x, float[] d, int o) {
        int t = ax.taps(), j = 4 * ax.first()[x] + m;
        float[] w = ax.w();
        float b = 0, g = 0, r = 0, q = 0;
        for (int k = 0; k < ax.count()[x]; k++, j += 4) {
            float c = w[x * t + k];
            b += c * s[j];
            g += c * s[j + 1];
            r += c * s[j + 2];
            q += c * s[j + 3];
        }
        d[o] = b;
        d[o + 1] = g;
        d[o + 2] = r;
        d[o + 3] = q;
    }

    private static Pass load() {
        if (ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent()) {
            try {
                return (Pass) Class.forName("scalebench.candidates.opus.VecPass").getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                // fall through to the scalar kernels
            }
        }
        return new ScalarPass();
    }
}
