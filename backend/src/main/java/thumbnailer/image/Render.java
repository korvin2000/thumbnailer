package thumbnailer.image;

import com.dynatrace.hash4j.hashing.Hashing;
import java.util.Locale;
import java.util.Objects;

/** How covers or entry thumbnails are encoded: format, quality, longer side in pixels, renders at once (0 = CPU count). */
public record Render(Format format, int quality, int size, int threads) {

    /** WEBP must stay first: its ordinal 0 is the seed that reproduces {@link #LEGACY_EPOCH}. */
    public enum Format {
        WEBP, JPEG;

        public String mediaType() { return "image/" + name().toLowerCase(Locale.ROOT); }
    }

    /** The epoch of covers stored before rendering was configurable; {@code (WEBP, 75, 512)} keeps it. */
    public static final long LEGACY_EPOCH = Hashing.wyhashFinal4().hashIntIntToLong(512, Float.floatToIntBits(75f));
    public static final Render COVERS = new Render(Format.WEBP, 75, 512, 4), THUMBS = new Render(Format.JPEG, 85, 512, 0);

    public Render {
        Objects.requireNonNull(format, "Render format is required");
        if (quality < 1 || quality > 100) throw new IllegalArgumentException("Render quality must be 1..100: " + quality);
        if (size < 64 || size > 4096) throw new IllegalArgumentException("Render size must be 64..4096: " + size);
        if (threads < 0 || threads > 64) throw new IllegalArgumentException("Render threads must be 0..64: " + threads);
    }

    public int permits() { return threads > 0 ? threads : Runtime.getRuntime().availableProcessors(); }

    /** Identifies the bytes this spec renders; threads never change them, so they are not part of it. */
    public long epoch() { return Hashing.wyhashFinal4(format.ordinal()).hashIntIntToLong(size, Float.floatToIntBits(quality)); }
}
