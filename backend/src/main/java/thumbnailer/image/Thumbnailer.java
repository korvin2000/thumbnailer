package thumbnailer.image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import thumbnailer.source.AlbumContainer;

/**
 * Renders thumbnails as a {@link Render} spec says: longer side {@code size}, proportions kept, never upscaled, WebP or
 * JPEG. The chain is native where it is hot and copies nothing between stages: the entry is read once into native
 * memory (no multi-MB heap arrays), a JPEG decodes through TurboJPEG at the smallest DCT scale that still covers
 * {@link #GAP} times the target (1/4 for a camera image and a 512 px thumbnail) straight into {@link Pixels}, the
 * {@link Resampler} finishes the shrink, EXIF orientation is applied to the small result, and libwebp or TurboJPEG
 * encode the same array.
 * WebP stills decode through libwebp; animations and other formats through ImageIO, subsampled to at most twice
 * the target.
 */
@Component
public class Thumbnailer {

    /**
     * Decode to at least this multiple of the target. A DCT scale averages like a box; on a 24 MP JPEG to 512 px
     * (PSNR against a full decode + float Lanczos3): 1/8 scored 38.4 dB in 50 ms, 1/4 + Lanczos3 50.8 dB in 57 ms,
     * 1/2 50.6 dB in 64 ms, a full decode 49.3 dB in 77 ms; entropy decoding dominates, so smaller scales save little.
     */
    static final double GAP = 1.5;
    private static final Logger log = LoggerFactory.getLogger(Thumbnailer.class);

    public Thumbnailer() {
        WebP.features(new byte[16]); // loads both natives now: a missing one fails the start, not the first scan
        TurboJpeg.isJpeg(MemorySegment.ofArray(new byte[0]));
        log.info("Resampler: {}", Resampler.PATH);
        Thread.ofPlatform().daemon().name("resampler-warmup").start(Resampler::warmUp); // ~1 s of one core; a scan at start must not starve it
    }

    /** Encoded bytes with the format and {@link Render#epoch()} that made them. */
    public record Thumb(byte[] bytes, Render.Format format, long epoch, boolean portrait) {}

    /** Decoded pixels with the stored full size, the EXIF orientation and whether a DCT scale already averaged them. */
    private record Decoded(Pixels pixels, int width, int height, int orientation, boolean boxed) {}

    public Thumb render(AlbumContainer container, String name, Render spec) throws IOException {
        try (var arena = Arena.ofConfined()) {
            return render(container.read(name, arena), name, spec);
        }
    }

    /** From the whole file in memory: the caller reads it, so the disk is free again while this decodes. */
    public Thumb render(MemorySegment data, String name, Render spec) throws IOException {
        int size = spec.size();
        var d = decode(data, name, size);
        double scale = Math.min(1, (double) size / Math.max(d.width, d.height));
        var p = d.pixels;
        p = Resampler.resize(p, Math.clamp(Math.round(d.width * scale), 1, p.width()), Math.clamp(Math.round(d.height * scale), 1, p.height()),
                d.boxed).oriented(d.orientation);
        var bytes = spec.format() == Render.Format.WEBP ? WebP.encode(p, spec.quality()) : TurboJpeg.encode(p, spec.quality());
        return new Thumb(bytes, spec.format(), spec.epoch(), p.height() > p.width());
    }

    private static Decoded decode(MemorySegment data, String name, int size) throws IOException {
        if (TurboJpeg.isJpeg(data)) {
            var h = Header.parse(data);
            try {
                var p = TurboJpeg.decode(data, h == null ? Integer.MAX_VALUE : (int) Math.ceil(size * GAP));
                return h == null ? new Decoded(p, p.width(), p.height(), 1, false)
                        : new Decoded(p, h.width(), h.height(), h.exif().orientation(), p.width() < h.width());
            } catch (IOException e) {
                // CMYK, 12-bit or broken beyond libjpeg's tolerance: ImageIO below
            }
        }
        var bytes = data.heapBase().orElse(null) instanceof byte[] b && b.length == data.byteSize() ? b : data.toArray(ValueLayout.JAVA_BYTE);
        var exif = ImageProbe.exif(bytes);
        if (WebP.isWebP(bytes)) {
            try {
                var p = WebP.decode(bytes);
                return new Decoded(p, p.width(), p.height(), exif.orientation(), false);
            } catch (IOException animated) {
                // ImageIO below decodes the first frame
            }
        }
        return ImageProbe.decode(new ByteArrayInputStream(bytes), name, exif, (reader, x) -> {
            int w = reader.getWidth(0), h = reader.getHeight(0), step = Math.max(1, Math.ceilDiv(Math.max(w, h), 2 * size));
            var param = reader.getDefaultReadParam();
            param.setSourceSubsampling(step, step, 0, 0);
            return new Decoded(Pixels.of(reader.read(0, param)), w, h, x.orientation(), false); // point-sampled, not averaged
        });
    }
}
