package thumbnailer.image;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import thumbnailer.image.ImageProbe.Exif;

/**
 * An image's stored size and the three EXIF tags the app reads (IFD0 Orientation and Artist, Exif-IFD DateTimeOriginal)
 * from a prefix of the file, without a decoder or a metadata tree: JPEG by walking the markers up to the first SOFn,
 * TIFF by its IFD0, PNG by IHDR and an {@code eXIf} chunk before the image data, GIF by its logical screen, BMP by its
 * DIB header. {@link #parse} returns null for any other format, or when the facts lie past the prefix.
 */
record Header(int width, int height, Exif exif) {

    private static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    static Header parse(MemorySegment d) {
        if (d.byteSize() < 4) return null;
        int a = u8(d, 0), b = u8(d, 1);
        Header h = null;
        try {
            if (a == 0xFF && b == 0xD8) h = jpeg(d);
            else if (a == 0x89 && b == 'P' && u8(d, 2) == 'N' && u8(d, 3) == 'G') h = png(d);
            else if (a == 'G' && b == 'I' && u8(d, 2) == 'F') h = gif(d);
            else if (a == 'B' && b == 'M') h = u32(d, 14, true) == 12 ? new Header(u16(d, 18, true), u16(d, 20, true), Exif.NONE)
                    : new Header((int) u32(d, 18, true), Math.abs((int) u32(d, 22, true)), Exif.NONE); // OS/2 core header, else Windows
            else if (a == b && (a == 'I' || a == 'M') && u16(d, 2, a == 'I') == 42) h = tiff(d);
        } catch (IndexOutOfBoundsException truncated) {
            // the facts lie past the prefix
        }
        return h != null && h.width > 0 && h.height > 0 ? h : null;
    }

    private static Header jpeg(MemorySegment d) {
        long n = d.byteSize();
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
                    exif = tiff(d.asSlice(seg + 6, Math.min(end, n) - seg - 6)).exif;
                if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC)
                    return seg + 5 <= n ? new Header(u16(d, seg + 3, false), u16(d, seg + 1, false), exif) : null;
                i = end;
            }
        } catch (IndexOutOfBoundsException truncated) {
            // a length or offset pointing past the prefix: nothing more to learn from it
        }
        return null;
    }

    /** IHDR, then the chunks before the image data for {@code eXIf}; null when the prefix ends before IDAT. */
    private static Header png(MemorySegment d) {
        var exif = Exif.NONE;
        for (long i = 8 + 25; u32(d, i + 4, false) != 0x49444154; i += 12 + u32(d, i, false)) // chunks after IHDR, until IDAT
            if (u32(d, i + 4, false) == 0x65584966) exif = tiff(d.asSlice(i + 8, u32(d, i, false))).exif; // eXIf
        return new Header((int) u32(d, 16, false), (int) u32(d, 20, false), exif);
    }

    /** The first frame's size, as ImageIO reads it (the logical screen may be larger): past the colour table and extensions. */
    private static Header gif(MemorySegment d) {
        int flags = u8(d, 10);
        long i = 13 + ((flags & 0x80) != 0 ? 3L << ((flags & 7) + 1) : 0);
        while (u8(d, i) == 0x21) { // an extension: label, then sub-blocks up to a zero length
            i += 2;
            for (int n; (n = u8(d, i)) != 0; i += n + 1) {}
            i++;
        }
        return u8(d, i) == 0x2C ? new Header(u16(d, i + 5, true), u16(d, i + 7, true), Exif.NONE) : null;
    }

    /**
     * A TIFF structure (a TIFF file, or the payload of APP1 "Exif" or PNG {@code eXIf}): IFD0 with the image size,
     * Orientation and Artist, then the Exif sub-IFD it points to; the size stays 0 where IFD0 has none, and also when
     * an offset points past the prefix, so a TIFF file with facts missing falls back to a full read.
     */
    private static Header tiff(MemorySegment t) {
        int orientation = 1, width = 0, height = 0;
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
                        case 0x0100 -> { if (pass == 0) width = value(t, e, le); }
                        case 0x0101 -> { if (pass == 0) height = value(t, e, le); }
                        case 0x0112 -> { if (pass == 0) orientation = u16(t, e + 8, le); }
                        case 0x013B -> { if (pass == 0) artist = ascii(t, e, le); }
                        case 0x8769 -> { if (pass == 0) sub = u32(t, e + 8, le); }
                        case 0x9003 -> { if (pass == 1) shotAt = date(ascii(t, e, le)); }
                        default -> {}
                    }
                }
            }
        } catch (IndexOutOfBoundsException truncated) {
            width = height = 0; // keep the tags read before the bad offset, but not as a complete file header
        }
        return new Header(width, height, new Exif(orientation >= 1 && orientation <= 8 ? orientation : 1, shotAt, artist));
    }

    /** A SHORT or LONG entry's value. */
    private static int value(MemorySegment t, long e, boolean le) { return u16(t, e + 2, le) == 3 ? u16(t, e + 8, le) : (int) u32(t, e + 8, le); }

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
