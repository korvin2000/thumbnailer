package scalebench;

import com.mortennobel.imagescaling.AdvancedResizeOp;
import com.mortennobel.imagescaling.MultiStepRescaleOp;
import com.mortennobel.imagescaling.ResampleFilter;
import com.mortennobel.imagescaling.ResampleFilters;
import com.mortennobel.imagescaling.ResampleOp;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.util.Arrays;
import java.util.List;

/** The scalers to beat: thumbnailer's frozen Resampler, Java2D, and xview's ScaleFactory NOBEL_* (java-image-scaling). */
public final class Baselines {

    private Baselines() {}

    public static List<Scaler> all() {
        return List.of(new Current(), awt("AwtBilinear", RenderingHints.VALUE_INTERPOLATION_BILINEAR),
                awt("AwtBicubic", RenderingHints.VALUE_INTERPOLATION_BICUBIC), new NobelMultistep(),
                nobel("NobelLanczos3", ResampleFilters.getLanczos3Filter()), nobel("NobelBicubic", ResampleFilters.getBiCubicFilter()));
    }

    /** Wraps pixels as a TYPE_INT_RGB image without copying. */
    static BufferedImage image(int[] px, int w, int h) {
        var cm = new DirectColorModel(24, 0xFF0000, 0xFF00, 0xFF);
        return new BufferedImage(cm, Raster.createWritableRaster(cm.createCompatibleSampleModel(w, h), new DataBufferInt(px, px.length), null), false, null);
    }

    static int[] pixels(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB || img.getType() == BufferedImage.TYPE_INT_ARGB)
            return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private static Scaler awt(String name, Object hint) {
        return new Scaler() {
            @Override public String name() { return name; }

            @Override
            public int[] resize(int[] src, int sw, int sh, int dw, int dh) {
                var dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
                var g = dst.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(image(src, sw, sh), 0, 0, dw, dh, null);
                g.dispose();
                return pixels(dst);
            }
        };
    }

    private static Scaler nobel(String name, ResampleFilter filter) {
        return new Scaler() {
            @Override public String name() { return name; }

            @Override
            public int[] resize(int[] src, int sw, int sh, int dw, int dh) {
                var op = new ResampleOp(dw, dh);
                op.setFilter(filter);
                op.setUnsharpenMask(AdvancedResizeOp.UnsharpenMask.None);
                op.setNumberOfThreads(1);
                return pixels(op.filter(image(src, sw, sh), null));
            }
        };
    }

    static final class NobelMultistep implements Scaler {
        @Override public String name() { return "NobelMultistep"; }

        @Override
        public int[] resize(int[] src, int sw, int sh, int dw, int dh) {
            return pixels(new MultiStepRescaleOp(dw, dh).filter(image(src, sw, sh), null));
        }
    }

    /**
     * thumbnailer.image.Resampler as of 2026-10-13, ported to int[] unchanged: antialiased Catmull-Rom with Pillow's
     * geometry, 22-bit fixed point, horizontal pass into byte planes, vertical pass as axpy rows. Downscale only.
     */
    public static final class Current implements Scaler {
        private static final int PRECISION = 22, HALF = 1 << (PRECISION - 1);

        @Override public String name() { return "Current"; }

        @Override public boolean supports(int sw, int sh, int dw, int dh) { return dw <= sw && dh <= sh; }

        @Override
        public int[] resize(int[] in, int sw, int sh, int w, int h) {
            if (w == sw && h == sh) return in.clone();
            var ax = Axis.of(sw, w);
            int plane = sh * w;
            var mid = new byte[3 * plane];
            for (int y = 0, base = 0, o = 0; y < sh; y++, base += sw, o += w) {
                for (int x = 0; x < w; x++) {
                    int s = base + ax.first[x], n = ax.count[x], ko = x * ax.stride, r = HALF, g = HALF, b = HALF;
                    for (int k = 0; k < n; k++) {
                        int c = ax.weight[ko + k], p = in[s + k];
                        r += c * (p >>> 16 & 255);
                        g += c * (p >>> 8 & 255);
                        b += c * (p & 255);
                    }
                    mid[o + x] = clip(r);
                    mid[plane + o + x] = clip(g);
                    mid[2 * plane + o + x] = clip(b);
                }
            }
            var ay = Axis.of(sh, h);
            int[] out = new int[w * h];
            var acc = new int[3 * w];
            for (int y = 0; y < h; y++) {
                Arrays.fill(acc, HALF);
                int s = ay.first[y], n = ay.count[y], ko = y * ay.stride;
                for (int k = 0; k < n; k++) {
                    int c = ay.weight[ko + k], o = (s + k) * w;
                    for (int ch = 0; ch < 3; ch++) axpy(acc, ch * w, c, mid, ch * plane + o, w);
                }
                for (int x = 0, o = y * w; x < w; x++)
                    out[o + x] = (clip(acc[x]) & 255) << 16 | (clip(acc[w + x]) & 255) << 8 | clip(acc[2 * w + x]) & 255;
            }
            return out;
        }

        private static void axpy(int[] acc, int ao, int c, byte[] src, int so, int n) {
            for (int i = 0; i < n; i++) acc[ao + i] += c * (src[so + i] & 255);
        }

        private static byte clip(int v) { return (byte) Math.clamp(v >> PRECISION, 0, 255); }

        private record Axis(int stride, int[] first, int[] count, int[] weight) {
            static Axis of(int in, int out) {
                double scale = (double) in / out, support = 2 * scale;
                int stride = (int) Math.ceil(support) * 2 + 1;
                var first = new int[out];
                var count = new int[out];
                var weight = new int[out * stride];
                var k = new double[stride];
                for (int i = 0; i < out; i++) {
                    double center = (i + 0.5) * scale, sum = 0;
                    int lo = Math.max(0, (int) (center - support + 0.5)), n = Math.min(in, (int) (center + support + 0.5)) - lo;
                    for (int j = 0; j < n; j++) sum += k[j] = keys((j + lo - center + 0.5) / scale);
                    int base = i * stride, total = 0, peak = 0;
                    for (int j = 0; j < n; j++) {
                        total += weight[base + j] = (int) Math.round(k[j] / sum * (1 << PRECISION));
                        if (weight[base + j] > weight[base + peak]) peak = j;
                    }
                    weight[base + peak] += (1 << PRECISION) - total;
                    first[i] = lo;
                    count[i] = n;
                }
                return new Axis(stride, first, count, weight);
            }

            private static double keys(double x) {
                x = Math.abs(x);
                return x < 1 ? (1.5 * x - 2.5) * x * x + 1 : x < 2 ? ((-0.5 * x + 2.5) * x - 4) * x + 2 : 0;
            }
        }
    }
}
