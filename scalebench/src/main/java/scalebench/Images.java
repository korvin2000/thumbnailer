package scalebench;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/** Deterministic synthetic test images; no photo leaves or enters the machine. */
public final class Images {

    private Images() {}

    /**
     * Photo-like content with a roughly 1/f spectrum: fractal value noise per channel (colour clouds down to 2 px
     * detail), antialiased shapes with hard edges, text from 5 px up, hair-thin curves and fine sensor/fabric grain.
     */
    public static int[] photo(int w, int h, long seed) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int top = Integer.highestOneBit(Math.max(w, h) / 2);
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0, amp = 1, total = 0;
                for (int p = top, o = 0; p >= 2; p >>= 1, o++, amp *= 0.6) {
                    double l = noise(x, y, p, seed + o) - 0.5, c1 = noise(x, y, p, seed + 101 + o) - 0.5, c2 = noise(x, y, p, seed + 211 + o) - 0.5;
                    r += amp * (l + 0.35 * c1);
                    g += amp * (l - 0.2 * c1 + 0.2 * c2);
                    b += amp * (l - 0.35 * c2);
                    total += amp;
                }
                int grain = (int) (hash(x, y, seed ^ 0x5DEECE66DL) & 15) - 8;
                px[y * w + x] = clamp(128 + 300 * r / total + grain) << 16 | clamp(128 + 300 * g / total + grain) << 8 | clamp(128 + 300 * b / total + grain);
            }
        });
        var rnd = new SplittableRandom(seed);
        var g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double area = (double) w * h, unit = Math.sqrt(area);
            for (int i = 0, n = (int) (area / 60_000) + 8; i < n; i++) {
                g.setColor(new Color(rnd.nextInt(0x1000000) | (150 + rnd.nextInt(106)) << 24, true));
                double sw = unit * (0.005 + 0.12 * Math.pow(rnd.nextDouble(), 3)), sh = sw * (0.3 + 1.4 * rnd.nextDouble());
                double sx = rnd.nextDouble() * w, sy = rnd.nextDouble() * h;
                g.fill(rnd.nextBoolean() ? new Rectangle2D.Double(sx, sy, sw, sh) : new Ellipse2D.Double(sx, sy, sw, sh));
            }
            for (int i = 0, n = (int) (area / 40_000) + 20; i < n; i++) {
                g.setColor(new Color(rnd.nextInt(0x303030), false));
                g.setStroke(new BasicStroke((float) (0.5 + rnd.nextDouble())));
                double x0 = rnd.nextDouble() * w, y0 = rnd.nextDouble() * h, len = unit * 0.15 * rnd.nextDouble();
                g.draw(new QuadCurve2D.Double(x0, y0, x0 + (rnd.nextDouble() - 0.5) * len, y0 + (rnd.nextDouble() - 0.5) * len,
                        x0 + (rnd.nextDouble() - 0.5) * len, y0 + (rnd.nextDouble() - 0.5) * len));
            }
            for (int i = 0, n = (int) (area / 150_000) + 6; i < n; i++) {
                g.setColor(rnd.nextBoolean() ? Color.BLACK : Color.WHITE);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 5 + (int) (unit * 0.02 * Math.pow(rnd.nextDouble(), 2))));
                g.drawString("Studio Lumen 2026 Anna K. #%04d".formatted(i), (float) (rnd.nextDouble() * w), (float) (rnd.nextDouble() * h));
            }
        } finally {
            g.dispose();
        }
        return px;
    }

    /** Grey zone plate {@code 0.5 + 0.5 cos(pi k r^2)}, k = 1/n: local frequency k r reaches 0.5 cycles/px at r = n/2. */
    public static int[] zonePlate(int n) {
        int[] px = new int[n * n];
        double k = 1.0 / n, c = n / 2.0;
        IntStream.range(0, n).parallel().forEach(y -> {
            for (int x = 0; x < n; x++) {
                double dx = x + 0.5 - c, dy = y + 0.5 - c;
                int v = clamp(127.5 + 127.5 * Math.cos(Math.PI * k * (dx * dx + dy * dy)));
                px[y * n + x] = v << 16 | v << 8 | v;
            }
        });
        return px;
    }

    /** Smooth value noise in [0, 1] with lattice period {@code p} pixels. */
    private static double noise(int x, int y, int p, long seed) {
        int gx = x / p, gy = y / p;
        double fx = smooth((x % p + 0.5) / p), fy = smooth((y % p + 0.5) / p);
        double a = unit(gx, gy, seed), b = unit(gx + 1, gy, seed), c = unit(gx, gy + 1, seed), d = unit(gx + 1, gy + 1, seed);
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    private static double unit(int x, int y, long seed) { return (hash(x, y, seed) >>> 11) * 0x1.0p-53; }

    private static long hash(int x, int y, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + y * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        return h ^ h >>> 33;
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }
}
