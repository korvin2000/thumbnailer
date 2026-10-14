package scalebench;

/** Fidelity of an 8-bit XRGB result against a float reference, on luma (BT.601 weights). */
public final class Metrics {

    private Metrics() {}

    static double[] luma(double[][] rgb) {
        double[] y = new double[rgb[0].length];
        for (int i = 0; i < y.length; i++) y[i] = 0.299 * rgb[0][i] + 0.587 * rgb[1][i] + 0.114 * rgb[2][i];
        return y;
    }

    static double[] luma(int[] px) {
        double[] y = new double[px.length];
        for (int i = 0; i < y.length; i++) y[i] = 0.299 * (px[i] >>> 16 & 255) + 0.587 * (px[i] >>> 8 & 255) + 0.114 * (px[i] & 255);
        return y;
    }

    static double psnr(double[] ref, double[] out) {
        double mse = 0;
        for (int i = 0; i < ref.length; i++) mse += (ref[i] - out[i]) * (ref[i] - out[i]);
        mse /= ref.length;
        return mse == 0 ? 99 : 10 * Math.log10(255 * 255 / mse);
    }

    /** Mean SSIM with the usual 11x11 Gaussian window (sigma 1.5). */
    static double ssim(double[] a, double[] b, int w, int h) {
        double[] ab = new double[a.length], aa = new double[a.length], bb = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            ab[i] = a[i] * b[i];
            aa[i] = a[i] * a[i];
            bb[i] = b[i] * b[i];
        }
        double[] ma = blur(a, w, h), mb = blur(b, w, h), sab = blur(ab, w, h), saa = blur(aa, w, h), sbb = blur(bb, w, h);
        double c1 = 6.5025, c2 = 58.5225, sum = 0;
        for (int i = 0; i < a.length; i++) {
            double va = saa[i] - ma[i] * ma[i], vb = sbb[i] - mb[i] * mb[i], cov = sab[i] - ma[i] * mb[i];
            sum += (2 * ma[i] * mb[i] + c1) * (2 * cov + c2) / ((ma[i] * ma[i] + mb[i] * mb[i] + c1) * (va + vb + c2));
        }
        return sum / a.length;
    }

    private static double[] blur(double[] in, int w, int h) {
        double[] k = new double[11];
        double s = 0;
        for (int i = 0; i < 11; i++) s += k[i] = Math.exp(-(i - 5) * (i - 5) / (2 * 1.5 * 1.5));
        for (int i = 0; i < 11; i++) k[i] /= s;
        double[] t = new double[in.length], out = new double[in.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double v = 0;
                for (int i = 0; i < 11; i++) v += k[i] * in[y * w + Math.clamp(x + i - 5, 0, w - 1)];
                t[y * w + x] = v;
            }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double v = 0;
                for (int i = 0; i < 11; i++) v += k[i] * t[Math.clamp(y + i - 5, 0, h - 1) * w + x];
                out[y * w + x] = v;
            }
        return out;
    }

    /**
     * Zone plate {@link Images#zonePlate} of side {@code n} scaled to side {@code m}: {@code [mtf, alias]}, both relative
     * to the original's RMS amplitude. mtf = contrast kept where the local output frequency is below 0.25 cycles/px
     * (ideal 1), alias = residual where it is above 0.6, i.e. beyond the output Nyquist limit (ideal 0).
     */
    static double[] zone(int[] out, int n, int m) {
        double s = (double) n / m, k = 1.0 / n, c = n / 2.0, rms = 0.5 / Math.sqrt(2);
        double pass = 0, passRef = 0, stop = 0;
        int stopCount = 0;
        for (int y = 0; y < m; y++)
            for (int x = 0; x < m; x++) {
                double dx = (x + 0.5) * s - c, dy = (y + 0.5) * s - c, r2 = dx * dx + dy * dy, r = Math.sqrt(r2);
                if (r > c) continue;
                double f = k * r * s, v = (out[y * m + x] & 255) / 255.0 - 0.5;
                if (f < 0.25) {
                    double ref = 0.5 * Math.cos(Math.PI * k * r2);
                    pass += v * v;
                    passRef += ref * ref;
                } else if (f > 0.6) {
                    stop += v * v;
                    stopCount++;
                }
            }
        return new double[] {Math.sqrt(pass / passRef), stopCount == 0 ? 0 : Math.sqrt(stop / stopCount) / rms};
    }
}
