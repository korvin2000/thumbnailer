package scalebench.candidates.opus;

import java.util.function.DoubleUnaryOperator;

/**
 * Filter taps of one axis with Pillow's geometry (pixel centres at i + 0.5, support widened by the scale when
 * shrinking, weights normalized per output): output i reads source [first[i], first[i] + count[i]) with weights
 * w[i * taps ..], zero padded to {@code taps}.
 */
record Axis(int[] first, int[] count, float[] w, int taps) {

    record Kernel(double support, DoubleUnaryOperator f) {}

    static final Kernel CATMULL_ROM = new Kernel(2, x -> x < 1 ? (1.5 * x - 2.5) * x * x + 1 : x < 2 ? ((-0.5 * x + 2.5) * x - 4) * x + 2 : 0);
    static final Kernel LANCZOS3 = new Kernel(3, x -> x == 0 ? 1 : x >= 3 ? 0 : sinc(x) * sinc(x / 3));

    static Axis of(int in, int out, Kernel k) { return of(in, (double) in / out, out, k); }

    /** {@code in} source pixels of which {@code scale} make one output pixel (in / out unless a box pre-shrink rounded in up). */
    static Axis of(int in, double scale, int out, Kernel k) {
        double fs = Math.max(1, scale), sup = k.support() * fs;
        int taps = (int) Math.ceil(2 * sup); // an open interval of length 2 sup holds at most this many pixel centres
        var first = new int[out];
        var count = new int[out];
        var w = new float[out * taps];
        var t = new double[taps];
        for (int i = 0; i < out; i++) {
            double c = (i + 0.5) * scale, sum = 0;
            int lo = Math.max(0, (int) Math.floor(c - sup - 0.5) + 1), n = Math.min(Math.min(in, (int) Math.ceil(c + sup - 0.5)) - lo, taps);
            for (int j = 0; j < n; j++) sum += t[j] = k.f().applyAsDouble(Math.abs((lo + j + 0.5 - c) / fs));
            for (int j = 0; j < n; j++) w[i * taps + j] = (float) (t[j] / sum);
            first[i] = lo;
            count[i] = n;
        }
        return new Axis(first, count, w, taps);
    }

    int maxCount() {
        int m = 0;
        for (int n : count) m = Math.max(m, n);
        return m;
    }

    /** Tap pairs per output for the vector horizontal pass. */
    int pairs() { return (taps + 1) / 2; }

    /** Weights expanded for two pixels per 8-lane vector: [x][pair][w(2p) x4, w(2p+1) x4]. */
    float[] expanded() {
        int out = first.length, p = pairs();
        var e = new float[out * p * 8];
        for (int i = 0; i < out; i++)
            for (int j = 0; j < taps; j++)
                for (int l = 0; l < 4; l++) e[(i * p + j / 2) * 8 + (j & 1) * 4 + l] = w[i * taps + j];
        return e;
    }

    /**
     * Two adjacent outputs per 8-lane vector: one 2-pixel load at step k feeds output 2q (low half) and 2q + 1 (high
     * half), so no fold is needed. Rows carry a one-pixel left margin; base[q] is the float offset of step 0 for pair q
     * and w holds k steps of 8 weights per pair (zero outside each output's window).
     */
    record Dual(int[] base, float[] w, int k) {}

    /** The dual layout, or null when it needs more steps than taps + 1 (shrinking: windows drift apart by 2 or more). */
    Dual dual() {
        int n = first.length, pairs = (n + 1) / 2, kmin = 0, kmax = taps;
        for (int q = 0; q < pairs; q++) {
            int d = 2 * q + 1 < n ? first[2 * q + 1] - first[2 * q] : 1;
            kmin = Math.min(kmin, d - 1);
            kmax = Math.max(kmax, d - 1 + taps);
        }
        int k = kmax - kmin;
        if (k > taps + 1) return null;
        var base = new int[pairs];
        var e = new float[pairs * k * 8];
        for (int q = 0; q < pairs; q++) {
            int x = 2 * q;
            base[q] = 4 * (first[x] + 1 + kmin);
            for (int j = 0; j < count[x]; j++)
                for (int l = 0; l < 4; l++) e[(q * k + j - kmin) * 8 + l] = w[x * taps + j];
            if (x + 1 < n)
                for (int j = 0, d = first[x + 1] - first[x]; j < count[x + 1]; j++)
                    for (int l = 4; l < 8; l++) e[(q * k + j + d - 1 - kmin) * 8 + l] = w[(x + 1) * taps + j];
        }
        return new Dual(base, e, k);
    }

    private static double sinc(double x) { return Math.sin(Math.PI * x) / (Math.PI * x); }
}
