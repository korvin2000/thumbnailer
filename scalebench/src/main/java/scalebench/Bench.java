package scalebench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import scalebench.candidates.AstraScaler;
import scalebench.candidates.FableScaler;
import scalebench.candidates.OpusScaler;
import scalebench.candidates.SolScaler;

/**
 * Quality and speed of every {@link Scaler} on the same deterministic inputs.
 * <pre>
 * ./gradlew -p scalebench run --args="[quality|speed|all] [--only=A,B] [--quick] [--time=ms]"   (speed: one JVM per scaler)
 * </pre>
 * Quality: PSNR/SSIM (luma) against the float64 Lanczos3 downscale of a photo-like image; for upscales, against the
 * original that a Lanczos3 downscale degraded; zone plate MTF (passband contrast, 1 = ideal) and alias (residual
 * beyond the output Nyquist limit, 0 = ideal). Speed: median and best ms per call, single thread, after warm-up.
 */
public final class Bench {

    record Case(String name, int sw, int sh, int dw, int dh, boolean up) {
        String label() { return "%s %dx%d->%dx%d".formatted(name, sw, sh, dw, dh); }
    }

    static final List<Case> CASES = List.of(
            new Case("D1", 6000, 4000, 512, 341, false),   // full-size decode (PNG/WebP, or no DCT scaling)
            new Case("D2", 750, 500, 512, 341, false),     // after a 1/8 DCT-scaled JPEG decode: the main thumbnail path
            new Case("D3", 1500, 1000, 512, 341, false),   // after a 1/4 DCT-scaled decode
            new Case("D4", 3000, 2000, 1600, 1067, false), // viewer-like moderate downscale
            new Case("D5", 4000, 3000, 512, 384, false),   // 12 MP full-size decode, ratio 7.8
            new Case("D6", 6001, 4001, 512, 341, false),   // odd camera size
            new Case("U1", 400, 300, 1600, 1200, true),
            new Case("U2", 1000, 667, 1500, 1000, true));
    static final List<String> QUICK = List.of("D2", "D3", "U2");

    public static void main(String[] args) {
        java.util.Locale.setDefault(java.util.Locale.ROOT); // decimal points in every child JVM
        String mode = "all";
        List<String> only = null;
        boolean quick = false;
        long time = 1500;
        for (var a : args) {
            if (a.startsWith("--only=")) only = Arrays.asList(a.substring(7).split(","));
            else if (a.equals("--quick")) quick = true;
            else if (a.startsWith("--time=")) time = Long.parseLong(a.substring(7));
            else mode = a;
        }
        var filter = only;
        var scalers = Stream.concat(Baselines.all().stream(), Stream.of(new FableScaler(), new OpusScaler(), new AstraScaler(), new SolScaler(), new thumbnailer.image.ProductionScaler()))
                .filter(s -> filter == null || filter.contains(s.name())).toList();
        boolean q = quick;
        var cases = CASES.stream().filter(c -> !q || QUICK.contains(c.name())).toList();
        System.out.printf("JDK %s, %d CPUs, scalers: %s%n%n", Runtime.version(), Runtime.getRuntime().availableProcessors(),
                scalers.stream().map(Scaler::name).toList());
        if (!mode.equals("speed") && !mode.equals("child")) quality(scalers, cases, quick);
        if (mode.equals("child") || scalers.size() == 1) speed(scalers, cases, time);
        else if (!mode.equals("quality")) forked(scalers, args);
    }

    /**
     * One JVM per scaler: Vector API kernels share the JDK's JIT profiles, so a candidate measured after another
     * one ran up to 2x slower than alone. Each child prints its table; its row is collected here.
     */
    static void forked(List<Scaler> scalers, String[] args) {
        var java = ProcessHandle.current().info().command().orElse("java");
        var rows = new ArrayList<String>();
        var header = new ArrayList<String>();
        for (var s : scalers) {
            var cmd = new ArrayList<>(List.of(java, "--add-modules", "jdk.incubator.vector", "-Xmx6g", "-cp", System.getProperty("java.class.path"), Bench.class.getName(), "child"));
            for (var a : args) if (a.startsWith("--") && !a.startsWith("--only=")) cmd.add(a);
            cmd.add("--only=" + s.name());
            try {
                var p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                for (var line : new String(p.getInputStream().readAllBytes()).lines().toList()) {
                    if ((line.startsWith("| scaler") || line.startsWith("|---")) && header.size() < 2) header.add(line);
                    else if (line.startsWith("| " + s.name() + " ")) rows.add(line);
                }
                p.waitFor();
            } catch (Exception e) {
                rows.add("| " + s.name() + " | ERR " + e + " |");
            }
        }
        System.out.println("## Speed, single thread, one JVM per scaler (lower is better)\n");
        header.forEach(System.out::println);
        rows.forEach(System.out::println);
        System.out.println();
    }

    static void quality(List<Scaler> scalers, List<Case> cases, boolean quick) {
        var rows = new LinkedHashMap<String, List<String>>();
        var header = new ArrayList<String>();
        for (var c : cases) {
            header.add(c.label() + " PSNR/SSIM");
            int[] src;
            double[] ref;
            if (c.up) { // degrade an original with the ideal downscale, score the reconstruction against it
                int[] orig = Images.photo(c.dw, c.dh, 7);
                src = Reference.LANCZOS3.resize(orig, c.dw, c.dh, c.sw, c.sh);
                ref = Metrics.luma(orig);
            } else {
                src = Images.photo(c.sw, c.sh, 7);
                ref = Metrics.luma(Reference.LANCZOS3.planes(src, c.sw, c.sh, c.dw, c.dh));
            }
            for (var s : scalers) {
                var cell = !s.supports(c.sw, c.sh, c.dw, c.dh) ? "n/a" : guard(() -> {
                    double[] y = Metrics.luma(run(s, src, c));
                    return "%.2f/%.4f".formatted(Metrics.psnr(ref, y), Metrics.ssim(ref, y, c.dw, c.dh));
                });
                rows.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(cell);
            }
        }
        for (int[] z : quick ? new int[][] {{1024, 700}} : new int[][] {{4096, 512}, {1024, 700}}) {
            header.add("zone %d->%d MTF/alias".formatted(z[0], z[1]));
            int[] src = Images.zonePlate(z[0]);
            for (var s : scalers) {
                var c = new Case("Z", z[0], z[0], z[1], z[1], false);
                var cell = !s.supports(c.sw, c.sh, c.dw, c.dh) ? "n/a" : guard(() -> {
                    double[] m = Metrics.zone(run(s, src, c), z[0], z[1]);
                    return "%.3f/%.3f".formatted(m[0], m[1]);
                });
                rows.get(s.name()).add(cell);
            }
        }
        table("Quality (higher PSNR/SSIM/MTF and lower alias are better)", header, rows);
    }

    static void speed(List<Scaler> scalers, List<Case> cases, long millis) {
        var rows = new LinkedHashMap<String, List<String>>();
        var header = new ArrayList<String>();
        long sink = 0;
        for (var c : cases) {
            header.add(c.label() + " ms med/best");
            int[] src = Images.photo(c.sw, c.sh, 11);
            for (var s : scalers) {
                if (!s.supports(c.sw, c.sh, c.dw, c.dh)) {
                    rows.computeIfAbsent(s.name(), k -> new ArrayList<>()).add("n/a");
                    continue;
                }
                var times = new ArrayList<Long>();
                try {
                    for (long end = System.nanoTime() + millis * 1_000_000L, n = 0; n < 5 || System.nanoTime() < end; n++) sink += run(s, src, c)[0];
                    for (long end = System.nanoTime() + millis * 1_000_000L; times.size() < 5 || System.nanoTime() < end; ) {
                        long t = System.nanoTime();
                        sink += run(s, src, c)[c.dw * c.dh - 1];
                        times.add(System.nanoTime() - t);
                    }
                    times.sort(null);
                    rows.computeIfAbsent(s.name(), k -> new ArrayList<>())
                            .add("%.2f/%.2f".formatted(times.get(times.size() / 2) / 1e6, times.getFirst() / 1e6));
                } catch (RuntimeException | Error e) {
                    rows.computeIfAbsent(s.name(), k -> new ArrayList<>()).add("ERR " + e);
                }
            }
        }
        table("Speed, single thread (lower is better)", header, rows);
        if (sink == 42) System.out.println();
    }

    private static int[] run(Scaler s, int[] src, Case c) {
        int[] out = s.resize(src, c.sw, c.sh, c.dw, c.dh);
        if (out.length != c.dw * c.dh) throw new IllegalStateException(s.name() + ": output length " + out.length + " != " + c.dw * c.dh);
        return out;
    }

    private static String guard(Supplier<String> cell) {
        try {
            return cell.get();
        } catch (RuntimeException | Error e) {
            return "ERR " + e;
        }
    }

    private static void table(String title, List<String> header, Map<String, List<String>> rows) {
        System.out.println("## " + title + "\n");
        System.out.println("| scaler | " + String.join(" | ", header) + " |");
        System.out.println("|---|" + "---|".repeat(header.size()));
        rows.forEach((name, cells) -> System.out.println("| " + name + " | " + String.join(" | ", cells) + " |"));
        System.out.println();
    }
}
