package thumbnailer.image;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import thumbnailer.image.ImageProbe.Exif;

/**
 * A JPEG's frame size and the three EXIF tags the app reads (IFD0 Orientation and Artist, Exif-IFD DateTimeOriginal),
 * found by walking the markers up to the first SOFn: no decoder, no metadata tree, no allocation beyond the strings.
 * Parses a prefix; {@link #parse} returns null when the prefix ends before the frame header or the data is no JPEG.
 */
record JpegHeader(int width, int height, Exif exif) {

    private static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    static JpegHeader parse(MemorySegment d) {
        long n = d.byteSize();
        if (n < 4 || u8(d, 0) != 0xFF || u8(d, 1) != 0xD8) return null;
        Exif exif = Exif.NONE;
        try {
            for (long i = 2; i + 4 <= n; ) {
                if (u8(d, i) != 0xFF) return null;
                int m = u8(d, i + 1);
                if (m == 0xFF) { i++; continue; } // fill byte
                if (m == 0x01 || m >= 0xD0 && m <= 0xD8) { i += 2; continue; } // standalone markers
                if (m == 0xD9 || m == 0xDA) return null; // EOI / SOS before any frame header
                long seg = i + 4, end = i + 2 + u16(d, i + 2, false);
                if (m == 0xE1 && exif == Exif.NONE && seg + 14 <= n && u8(d, seg) == 'E' && u8(d, seg + 1) == 'x'
                        && u8(d, seg + 2) == 'i' && u8(d, seg + 3) == 'f' && u8(d, seg + 4) == 0)
                    exif = tiff(d.asSlice(seg + 6, Math.min(end, n) - seg - 6));
                if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC)
                    return seg + 5 <= n ? new JpegHeader(u16(d, seg + 3, false), u16(d, seg + 1, false), exif) : null;
                i = end;
            }
        } catch (IndexOutOfBoundsException truncated) {
            // a length or offset pointing past the prefix: nothing more to learn from it
        }
        return null;
    }

    /** The TIFF structure inside APP1 "Exif": IFD0, then the Exif sub-IFD it points to. */
    private static Exif tiff(MemorySegment t) {
        int orientation = 1;
        String artist = null;
        LocalDateTime shotAt = null;
        try {
            boolean le = u8(t, 0) == 'I';
            long ifd = u32(t, 4, le), sub = -1;
            for (int pass = 0; pass < 2 && ifd > 0; pass++, ifd = sub, sub = -1) {
                int count = u16(t, ifd, le);
                for (long e = ifd + 2, last = e + 12L * count; e < last; e += 12) {
                    int tag = u16(t, e, le);
                    switch (tag) {
                        case 0x0112 -> { if (pass == 0) orientation = u16(t, e + 8, le); }
                        case 0x013B -> { if (pass == 0) artist = ascii(t, e, le); }
                        case 0x8769 -> { if (pass == 0) sub = u32(t, e + 8, le); }
                        case 0x9003 -> { if (pass == 1) shotAt = date(ascii(t, e, le)); }
                        default -> {}
                    }
                }
            }
        } catch (IndexOutOfBoundsException truncated) {
            // keep what was read before the bad offset
        }
        return new Exif(orientation >= 1 && orientation <= 8 ? orientation : 1, shotAt, artist);
    }

    private static String ascii(MemorySegment t, long e, boolean le) {
        long len = u32(t, e + 4, le), at = len <= 4 ? e + 8 : u32(t, e + 8, le);
        var s = new String(t.asSlice(at, Math.min(len, 4096)).toArray(JAVA_BYTE), StandardCharsets.UTF_8).replace("\0", "").strip();
        return s.isEmpty() ? null : s;
    }

    private static LocalDateTime date(String s) {
        try {
            return s == null ? null : LocalDateTime.parse(s, EXIF_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int u8(MemorySegment d, long i) { return d.get(JAVA_BYTE, i) & 255; }

    private static int u16(MemorySegment d, long i, boolean le) {
        int a = u8(d, i), b = u8(d, i + 1);
        return le ? b << 8 | a : a << 8 | b;
    }

    private static long u32(MemorySegment d, long i, boolean le) {
        long a = u16(d, i, le), b = u16(d, i + 2, le);
        return le ? b << 16 | a : a << 16 | b;
    }
}
