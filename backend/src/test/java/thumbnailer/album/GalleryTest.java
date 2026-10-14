package thumbnailer.album;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.sf.sevenzipjbinding.ArchiveFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.image.Limiter;
import thumbnailer.image.Render;
import thumbnailer.image.Thumbnailer;
import thumbnailer.source.Archives;
import thumbnailer.source.Disks;

class GalleryTest {

    @TempDir Path dir;

    @Test
    void aSolidArchiveRendersEveryThumbnailInOnePassWhateverOrderTheyAreAskedIn() throws Exception {
        var images = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < Gallery.LOOKAHEAD + 6; i++) images.put("p%02d.jpg".formatted(i), jpeg(320 + i, 200));
        var file = dir.resolve("solid.7z");
        Archives.sevenZip(file, ArchiveFormat.SEVEN_ZIP, images, true);
        var opens = new AtomicInteger();
        var names = new ArrayList<>(images.keySet());
        names.add("missing.jpg");
        var lane = new Disks(1).lane(new Disks.Disk("t", false), Disks.Use.VIEW);
        var gallery = new Gallery(() -> {
            opens.incrementAndGet();
            return Archives.open(file);
        }, names, lane, new Limiter(), new Limiter(), Render.THUMBS, new Thumbnailer());

        var lastFirst = new ArrayList<>(images.keySet()).reversed(); // all asked before the pass starts: none passed over
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var thumbs = lastFirst.stream().map(n -> pool.submit(() -> gallery.take(n))).toList();
            Thread.sleep(300);
            gallery.start();
            for (var t : thumbs) assertThat(t.get().bytes()).isNotEmpty();
        }
        assertThat(opens).as("one pass for the whole gallery").hasValue(1);
        assertThat(gallery.take("p00.jpg")).as("a taken thumbnail is forgotten: the caller renders it alone").isNull();
        assertThat(gallery.take("missing.jpg")).as("not in the archive").isNull();
        try (var turn = lane.enter()) {
            assertThat(turn).as("the pass gave its disk turn back").isNotNull();
        }
        gallery.close();
        assertThat(gallery.closed()).isTrue();
    }

    @Test
    void anIdlePassStopsLookaheadThumbnailsAheadAndClosingIt() throws Exception {
        var images = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < Gallery.LOOKAHEAD * 2; i++) images.put("q%02d.jpg".formatted(i), jpeg(64, 48 + i));
        var file = dir.resolve("solid2.7z");
        Archives.sevenZip(file, ArchiveFormat.SEVEN_ZIP, images, true);
        var lane = new Disks(1).lane(new Disks.Disk("u", false), Disks.Use.VIEW);
        var gallery = new Gallery(() -> Archives.open(file), new ArrayList<>(images.keySet()), lane, new Limiter(), new Limiter(),
                Render.THUMBS, new Thumbnailer());
        gallery.start();
        assertThat(gallery.take("q00.jpg")).isNotNull();
        Thread.sleep(500); // the pass parks LOOKAHEAD ahead, without the disk's turn
        try (var turn = lane.enter()) {
            assertThat(turn).isNotNull();
        }
        gallery.close();
        assertThat(gallery.take("q%02d.jpg".formatted(Gallery.LOOKAHEAD * 2 - 1))).as("never reached: rendered alone").isNull();
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }
}
