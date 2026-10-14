package thumbnailer.source;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.image.ImageProbe;
import thumbnailer.image.Render;
import thumbnailer.image.Thumbnailer;

/**
 * End-to-end render pipeline timings (probe, WebP cover, JPEG thumb, parallel throughput) on synthetic camera-sized
 * JPEGs, in a directory and in a stored zip. Off by default: {@code THUMBNAILER_BENCH=1 ./gradlew -PskipFrontend
 * :backend:test --tests '*RenderBench'}; the table goes to {@code backend/build/render-bench.md}.
 */
@EnabledIfEnvironmentVariable(named = "THUMBNAILER_BENCH", matches = "1")
class RenderBench {

    @TempDir Path dir;

    @Test
    void pipeline() throws Exception {
        var album = Files.createDirectories(dir.resolve("album"));
        var names = new ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            boolean big = i % 2 == 0;
            var name = "IMG_%04d.jpg".formatted(i);
            Files.write(album.resolve(name), jpeg(big ? 6000 : 4000, big ? 4000 : 3000, i));
            names.add(name);
        }
        var zip = dir.resolve("album.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var n : names) {
                byte[] b = Files.readAllBytes(album.resolve(n));
                var e = new ZipEntry(n);
                e.setMethod(ZipEntry.STORED);
                e.setSize(b.length);
                var crc = new CRC32();
                crc.update(b);
                e.setCrc(crc.getValue());
                out.putNextEntry(e);
                out.write(b);
            }
        }
        var thumbnailer = new Thumbnailer();
        var report = new StringBuilder("| container | probe ms/img | WebP cover ms/img | JPEG thumb ms/img | covers/s, %d threads |\n|---|---|---|---|---|\n"
                .formatted(Runtime.getRuntime().availableProcessors()));
        for (var c : List.of(new DirectoryContainer(album, Exclusions.NONE), new ZipContainer(zip, Exclusions.NONE))) {
            try (c) {
                double probe = time(names, n -> uncheck(() -> ImageProbe.probe(c, n)));
                double cover = time(names, n -> uncheck(() -> thumbnailer.render(c, n, Render.COVERS)));
                double thumb = time(names, n -> uncheck(() -> thumbnailer.render(c, n, Render.THUMBS)));
                report.append("| %s | %.2f | %.1f | %.1f | %.1f |%n".formatted(c.getClass().getSimpleName(), probe, cover, thumb,
                        throughput(names, n -> uncheck(() -> thumbnailer.render(c, n, Render.COVERS)))));
            }
        }
        var out = Path.of("build/render-bench.md");
        Files.writeString(out, report);
        System.out.println(report);
    }

    /** Median ms per item over 5 rounds after 1 warm-up round. */
    private static double time(List<String> names, Consumer<String> op) {
        names.forEach(op);
        var t = new ArrayList<Double>();
        for (int r = 0; r < 5; r++)
            for (var n : names) {
                long s = System.nanoTime();
                op.accept(n);
                t.add((System.nanoTime() - s) / 1e6);
            }
        t.sort(null);
        return t.get(t.size() / 2);
    }

    /** Items per second with one platform thread per CPU, each rendering every item 3 times. */
    private static double throughput(List<String> names, Consumer<String> op) throws InterruptedException {
        int threads = Runtime.getRuntime().availableProcessors();
        long s = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) pool.submit(() -> { for (int r = 0; r < 3; r++) names.forEach(op); });
        }
        return threads * 3.0 * names.size() / ((System.nanoTime() - s) / 1e9);
    }

    /** Smooth colour fields, hard edges and grain, q90 4:2:0: decodes like a camera JPEG (a few MB). */
    static byte[] jpeg(int w, int h, long seed) throws IOException {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        IntStream.range(0, h).parallel().forEach(y -> {
            var rnd = new SplittableRandom(seed * 31 + y);
            for (int x = 0; x < w; x++) {
                double u = (double) x / w, v = (double) y / h;
                int g = rnd.nextInt(13) - 6;
                int r = (int) (128 + 90 * Math.sin(7 * u + seed) * Math.cos(5 * v)) + g;
                int gr = (int) (128 + 80 * Math.sin(3 * u * v + 11 * v)) + g;
                int b = (int) (128 + 70 * Math.cos(9 * u - 4 * v)) + g;
                px[y * w + x] = Math.clamp(r, 0, 255) << 16 | Math.clamp(gr, 0, 255) << 8 | Math.clamp(b, 0, 255);
            }
        });
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var rnd = new SplittableRandom(seed);
        for (int i = 0; i < 400; i++) {
            g.setColor(new Color(rnd.nextInt(0x1000000)));
            g.fillOval(rnd.nextInt(w), rnd.nextInt(h), 20 + rnd.nextInt(w / 10), 20 + rnd.nextInt(h / 10));
            g.drawLine(rnd.nextInt(w), rnd.nextInt(h), rnd.nextInt(w), rnd.nextInt(h));
        }
        g.dispose();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.9f);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    interface Io { Object run() throws IOException; }

    private static void uncheck(Io io) {
        try {
            io.run();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
