package thumbnailer.image;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import thumbnailer.source.AlbumContainer;

/** Image facts without a full decode: ImageIO reads the header, metadata-extractor stops before the pixel data. */
public final class ImageProbe {

    private static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    private ImageProbe() {}

    /** Display dimensions (EXIF orientation applied) and capture time. */
    public record Probe(int width, int height, LocalDateTime shotAt) {}

    /** The EXIF facts the application uses; {@link #NONE} when absent or unreadable. */
    public record Exif(int orientation, LocalDateTime shotAt, String artist) {
        static final Exif NONE = new Exif(1, null, null);

        /** Orientations 5..8 rotate by 90 degrees, so the stored width is the displayed height. */
        boolean swapsAxes() { return orientation >= 5 && orientation <= 8; }
    }

    public static Probe probe(AlbumContainer container, String name) throws IOException {
        int w, h;
        try (var raw = container.open(name); var in = ImageIO.createImageInputStream(raw)) {
            var reader = reader(in, name);
            try {
                w = reader.getWidth(0);
                h = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        }
        Exif exif;
        try (var raw = container.open(name)) {
            exif = exif(raw);
        }
        return exif.swapsAxes() ? new Probe(h, w, exif.shotAt()) : new Probe(w, h, exif.shotAt());
    }

    public static Exif exif(InputStream in) {
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

    static ImageReader reader(ImageInputStream in, String name) throws IOException {
        var readers = in == null ? null : ImageIO.getImageReaders(in);
        if (readers == null || !readers.hasNext()) throw new IOException("Unsupported image format: " + name);
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
