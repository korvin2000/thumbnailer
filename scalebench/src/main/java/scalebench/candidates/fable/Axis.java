package scalebench.candidates.fable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One resampling axis on a (possibly box-shrunk) cell grid: per output index the first input cell and the tap count,
 * and the weights ({@code weight[i * taps + t]}, zero padded to {@code taps}, an even number) summing to 1.
 * Tables are immutable and cached by geometry: the thumbnail path resizes the same shapes over and over.
 */
record Axis(int taps, int[] first, int[] count, float[] weight) {

    private static final ConcurrentHashMap<Long, Axis> CACHE = new ConcurrentHashMap<>();

    static Axis of(int srcLen, int k, int cells, int out, double lambda) {
        long key = (long) srcLen << 36 | (long) out << 8 | k;
        Axis a = CACHE.get(key);
        if (a == null) {
            if (CACHE.size() >= 64) CACHE.clear();
            CACHE.put(key, a = build(srcLen, k, cells, out, lambda));
        }
        return a;
    }

    /**
     * {@code srcLen} source pixels grouped into {@code cells} cells of {@code k} (the last one possibly narrower), Pillow
     * geometry: centres at i + 0.5, kernel support widened by the scale when shrinking. With a box ({@code k > 1}) the
     * sampled kernel is sharpened by a {@code [-l, 1 + 2l, -l]} Laplacian, which cancels the box's second-order sinc droop.
     */
    private static Axis build(int srcLen, int k, int cells, int out, double lambda) {
        double scale = (double) srcLen / out, fs = Math.max(1, scale), support = 2 * fs;
        int max = (int) Math.ceil(2 * support / k) + 2;
        var first = new int[out];
        var count = new int[out];
        var raw = new double[out * max];
        int longest = 0;
        for (int i = 0; i < out; i++) {
            double c = (i + 0.5) * scale;
            int lo = Math.max(0, (int) Math.floor((c - support) / k)), hi = Math.min(cells, (int) Math.ceil((c + support) / k)), base = i * max;
            for (int j = lo; j < hi; j++) raw[base + j - lo] = Fable.kernel(Math.abs(j * k + Math.min(k, srcLen - j * k) * 0.5 - c) / fs);
            int a = 0, b = hi - lo;
            while (b - a > 1 && raw[base + a] == 0) a++;
            while (b - a > 1 && raw[base + b - 1] == 0) b--;
            if (a > 0) System.arraycopy(raw, base + a, raw, base, b - a);
            first[i] = lo + a;
            count[i] = b - a;
            longest = Math.max(longest, b - a);
        }
        int taps = longest + 1 & ~1;
        var weight = new float[out * taps];
        var tmp = new double[max];
        for (int i = 0; i < out; i++) {
            int n = count[i], base = i * max;
            double sum = 0;
            for (int j = 0; j < n; j++)
                tmp[j] = k > 1 ? (1 + 2 * lambda) * raw[base + j] - lambda * ((j > 0 ? raw[base + j - 1] : 0) + (j + 1 < n ? raw[base + j + 1] : 0)) : raw[base + j];
            for (int j = 0; j < n; j++) sum += tmp[j];
            for (int j = 0; j < n; j++) weight[i * taps + j] = (float) (tmp[j] / sum);
        }
        return new Axis(taps, first, count, weight);
    }
}
