package thumbnailer.demo;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;

/**
 * {@code thumbnailer.demo=true}: ~40 deterministic sample albums (directories and zips, 40% portrait, real-style
 * names, cover-hint files, sidecars, a nested album, a folder below min-images) under {@code <data-dir>/demo},
 * registered as the scan root when none is configured. Runs before the scan on start.
 */
@Component
@ConditionalOnProperty(name = "thumbnailer.demo", havingValue = "true")
class DemoAlbums implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAlbums.class);
    private static final String[] CODES = {"CLS", "MPL", "SXA"};
    private static final String[] STUDIOS = {"EL-Studio", "Aurora Photo", "Nordlicht Lab", "Blue Hour Photo", "Studio Nine"};
    private static final String[] MODELS = {"Ellena", "Aurora", "Jeny", "Roksana", "Nelya", "Felicia", "Mira", "Daria",
            "Kira", "Lana", "Sofia", "Vera"};
    private static final String[] EXTRAS = {"New Model", "Outdoor", "Beach Day", "Golden Hour", "Backstage"};
    private static final String[] PHOTOGRAPHERS = {"Anna Kowalska", "Mark Feld", "Ivo Lind"};
    private static final String[] TAGS = {"wedding", "outdoor", "sport", "advertising", "fashion", "portrait", "beach"};
    private static final String[] COVERS = {"cover.jpg", "poster.jpg", "x_cv.jpg"};

    private final SettingsStore settings;

    DemoAlbums(SettingsStore settings) { this.settings = settings; }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        var demo = settings.dataDir().resolve("demo");
        if (Files.notExists(demo)) {
            long t0 = System.nanoTime();
            generate(demo);
            log.info("Generated demo albums in {} in {} ms", demo, (System.nanoTime() - t0) / 1_000_000);
        }
        var s = settings.get();
        if (s.roots().isEmpty())
            settings.set(new Settings(List.of(new Settings.Root(demo.toString(), List.of())), s.exclusions(), s.scanOnStart(),
                    s.pathMap(), s.covers(), s.thumbs()));
    }

    private static void generate(Path demo) throws IOException {
        var rnd = new Random(42);
        for (int i = 0; i < 40; i++) {
            boolean portrait = rnd.nextInt(100) < 40;
            var model = MODELS[rnd.nextInt(MODELS.length)];
            var studio = rnd.nextInt(3) > 0 ? STUDIOS[rnd.nextInt(STUDIOS.length)] + " - " : "";
            var extra = rnd.nextInt(3) == 0 ? " - " + EXTRAS[rnd.nextInt(EXTRAS.length)] : "";
            int count = 6 + rnd.nextInt(35);
            var date = LocalDate.of(2016, 1, 1).plusDays(rnd.nextInt(3000));
            var name = String.format(Locale.ROOT, "%s-%s - %s%s%s - (vol-%d) 1800px (x%d) %.1fMb",
                    CODES[i % 3], date, studio, model, extra, i + 1, count, count * .45);
            float hue = rnd.nextFloat();
            var files = new LinkedHashMap<String, byte[]>();
            for (int n = 1; n <= count; n++) files.put("%03d.jpg".formatted(n), image(portrait, hue, model + " " + n, rnd));
            if (i % 6 == 0) files.put(COVERS[i / 6 % 3], image(portrait, hue + .5f, model, rnd));
            if (i % 7 == 3) files.put("album.txt", sidecar(model, rnd).getBytes(UTF_8));
            var parent = i < 6 ? demo.resolve("Collections").resolve(String.valueOf(2018 + i % 3)) : demo;
            boolean zip = i % 5 < 2;
            write(parent.resolve(zip ? name + ".zip" : name), zip, files);
        }
        var archive = demo.resolve("Studio Archive"); // a directory album with a nested album inside
        write(archive, false, Map.of("a.jpg", image(false, .1f, "Archive A", rnd), "b.jpg", image(true, .2f, "Archive B", rnd)));
        write(archive.resolve("Outtakes"), false, Map.of("c.jpg", image(false, .3f, "Outtake C", rnd), "d.jpg", image(false, .4f, "Outtake D", rnd)));
        write(demo.resolve("Single Shot"), false, Map.of("only.jpg", image(false, .5f, "Below min-images", rnd)));
    }

    private static String sidecar(String model, Random rnd) {
        return "model: %s%nstudio: %s%nphotographer: %s%ndescription: Studio session with %s%n#%s #%s%n".formatted(model,
                STUDIOS[rnd.nextInt(STUDIOS.length)], PHOTOGRAPHERS[rnd.nextInt(PHOTOGRAPHERS.length)], model,
                TAGS[rnd.nextInt(TAGS.length)], TAGS[rnd.nextInt(TAGS.length)]);
    }

    private static void write(Path target, boolean zip, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(zip ? target.getParent() : target);
        if (!zip) {
            for (var f : files.entrySet()) Files.write(target.resolve(f.getKey()), f.getValue());
            return;
        }
        try (var out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var f : files.entrySet()) {
                out.putNextEntry(new ZipEntry(f.getKey()));
                out.write(f.getValue());
                out.closeEntry();
            }
        }
    }

    private static byte[] image(boolean portrait, float hue, String label, Random rnd) throws IOException {
        int w = portrait ? 270 : 480, h = portrait ? 480 : 270;
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, .55f, .95f), w, h, Color.getHSBColor(hue + .15f, .7f, .3f)));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(Color.HSBtoRGB(hue + rnd.nextFloat() * .3f, .45f, 1f) & 0xFFFFFF | 0x66000000, true));
            int x = rnd.nextInt(w), y = rnd.nextInt(h), s = 30 + rnd.nextInt(120);
            if (rnd.nextBoolean()) g.fillOval(x - s / 2, y - s / 2, s, s);
            else g.fillRect(x - s / 2, y - s / 3, s, s * 2 / 3);
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.drawString(label, 16, h - 20);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
