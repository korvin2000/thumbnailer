package thumbnailer.image;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.foreign.MemorySegment;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import thumbnailer.spi.AlbumContainer;

/**
 * Image facts without a decode, from the first {@link #HEAD} bytes of a file: JPEG, TIFF, PNG, GIF and BMP by their
 * headers ({@link Header}), WebP by its bitstream header. Anything else, and a file whose facts lie past the head (a
 * JPEG frame header behind a huge XMP, a TIFF with IFD0 at the end), goes through metadata-extractor and an ImageIO
 * header read ({@link #read}).
 */
public final class ImageProbe {

    /** Holds the EXIF block (at most 64 KiB) and the usual XMP/ICC segments before a JPEG's frame header. */
    public static final int HEAD = 128 << 10;

    private static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    private ImageProbe() {}

    /** Display dimensions (EXIF orientation applied) and capture time. */
    public record Probe(int width, int height, LocalDateTime shotAt) {
        static Probe of(int w, int h, Exif exif) { return exif.swapsAxes() ? new Probe(h, w, exif.shotAt()) : new Probe(w, h, exif.shotAt()); }
    }

    /** The EXIF facts the application uses; {@link #NONE} when absent or unreadable. */
    public record Exif(int orientation, LocalDateTime shotAt, String artist) {
        static final Exif NONE = new Exif(1, null, null);

        /** Orientations 5..8 rotate by 90 degrees, so the stored width is the displayed height. */
        boolean swapsAxes() { return orientation >= 5 && orientation <= 8; }
    }

    /** Opens a file from its start. */
    public interface Source { InputStream open() throws IOException; }

    public static Probe probe(AlbumContainer container, String name) throws IOException {
        return probe(container.head(name, HEAD), name, () -> container.open(name));
    }

    /** Probes from an already read {@code head} of the file; only the fallback opens it, through {@code file}. */
    public static Probe probe(byte[] head, String name, Source file) throws IOException {
        var p = probe(head);
        return p != null ? p : read(file, name, (reader, exif) -> Probe.of(reader.getWidth(0), reader.getHeight(0), exif));
    }

    /** From the head alone, touching nothing else; null when the file needs {@link #probe(AlbumContainer, String)}. */
    public static Probe probe(byte[] head) {
        var h = Header.parse(MemorySegment.ofArray(head));
        if (h != null) return Probe.of(h.width(), h.height(), h.exif());
        var f = WebP.isWebP(head) ? WebP.features(head) : null;
        return f == null ? null : new Probe(f.width, f.height, null);
    }

    /** EXIF of a file from its head: {@link Header} where it knows the format, metadata-extractor for anything else. */
    public static Exif exif(byte[] head) {
        var h = Header.parse(MemorySegment.ofArray(head));
        return h != null ? h.exif() : exif(new ByteArrayInputStream(head));
    }

    interface Body<T> { T apply(ImageReader reader, Exif exif) throws IOException; }

    /**
     * Usually opens the entry once: metadata-extractor parses the EXIF, the bytes it consumed are kept, and the reader
     * gets them followed by the rest of the stream. Only a parse that went past {@link #HEAD} (a PNG reads every chunk,
     * a JPEG with a huge XMP segment) costs a second open, so memory never holds more than the head.
     */
    static <T> T read(Source file, String name, Body<T> body) throws IOException {
        Exif exif;
        try (var raw = file.open()) {
            var head = new Head(raw);
            exif = exif(head);
            if (head.whole) return decode(new SequenceInputStream(new ByteArrayInputStream(head.kept.toByteArray()), raw), name, exif, body);
        }
        try (var raw = file.open()) {
            return decode(raw, name, exif, body);
        }
    }

    /** Passes the stream through and keeps what was read while that stays within {@link #HEAD}. */
    private static final class Head extends FilterInputStream {
        final ByteArrayOutputStream kept = new ByteArrayOutputStream(16 << 10);
        boolean whole = true; // kept holds every byte read so far

        Head(InputStream in) { super(in); }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0 && (whole &= kept.size() < HEAD)) kept.write(b);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0 && (whole &= kept.size() + n <= HEAD)) kept.write(b, off, n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            whole = false;
            return in.skip(n);
        }

        @Override public boolean markSupported() { return false; }
    }

    static <T> T decode(InputStream stream, String name, Exif exif, Body<T> body) throws IOException {
        try (var in = ImageIO.createImageInputStream(stream)) {
            if (in == null) throw new IOException("Cannot read " + name);
            var reader = reader(in, name);
            try {
                return body.apply(reader, exif);
            } finally {
                reader.dispose();
            }
        }
    }

    static Exif exif(InputStream in) {
        try {
            var metadata = ImageMetadataReader.readMetadata(in);
            var ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            var sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            Integer orientation = ifd0 == null ? null : ifd0.getInteger(ExifDirectoryBase.TAG_ORIENTATION);
            String artist = ifd0 == null ? null : ifd0.getString(ExifDirectoryBase.TAG_ARTIST);
            String date = sub == null ? null : sub.getString(ExifDirectoryBase.TAG_DATETIME_ORIGINAL);
            return new Exif(orientation == null ? 1 : orientation, date(date),
                    artist == null || artist.isBlank() ? null : artist.strip());
        } catch (ImageProcessingException | IOException | RuntimeException e) {
            return Exif.NONE;
        }
    }

    private static ImageReader reader(ImageInputStream in, String name) throws IOException {
        var readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) throw new IOException("Unsupported image format: " + name);
        var reader = readers.next();
        reader.setInput(in, true, true);
        return reader;
    }

    private static LocalDateTime date(String exif) {
        try {
            return exif == null ? null : LocalDateTime.parse(exif.strip(), EXIF_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
