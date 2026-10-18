package thumbnailer.store.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import thumbnailer.album.Entry;

/**
 * An album's entries as one compact value instead of one row per image (10M images would be 10M rows, each repeating
 * the album id in the row and its index): varints, and each UTF-8 name front-coded against the previous one, since sorted
 * names share long prefixes ({@code IMG_0001.jpg}, {@code IMG_0002.jpg}). The index is the list position. A value
 * starts with {@code 0x00, version}: unambiguous, since the first layout started with the count, whose first byte is 0
 * only for an empty list (read as empty); values of that layout decode with flags 0.
 */
final class EntryCodec {

    private static final byte VERSION = 1;

    private EntryCodec() {}

    static byte[] pack(List<Entry> entries) {
        var bytes = new ByteArrayOutputStream(16 + entries.size() * 24);
        try (var out = new DataOutputStream(bytes)) {
            out.write(new byte[]{0, VERSION});
            put(out, entries.size());
            String prev = "";
            for (var e : entries) {
                int shared = 0, max = Math.min(prev.length(), e.name().length());
                while (shared < max && prev.charAt(shared) == e.name().charAt(shared)) shared++;
                if (shared > 0 && Character.isHighSurrogate(e.name().charAt(shared - 1))) shared--; // never split a pair
                var suffix = e.name().substring(shared).getBytes(UTF_8);
                put(out, shared);
                put(out, suffix.length);
                out.write(suffix);
                put(out, e.bytes());
                put(out, e.mtime());
                put(out, e.width());
                put(out, e.height());
                put(out, e.shotAt() == null ? 0 : zigzag(e.shotAt().toEpochSecond(ZoneOffset.UTC)) + 1); // 0 = unknown
                put(out, e.flags());
                prev = e.name();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static List<Entry> unpack(byte[] packed) {
        boolean versioned = packed.length > 1 && packed[0] == 0;
        if (versioned && packed[1] != VERSION) throw new IllegalStateException("Unknown entries version " + packed[1]);
        try (var in = new DataInputStream(new ByteArrayInputStream(packed, versioned ? 2 : 0, packed.length))) {
            int n = (int) get(in);
            var entries = new ArrayList<Entry>(n);
            String prev = "";
            for (int i = 0; i < n; i++) {
                var name = prev.substring(0, (int) get(in)) + new String(in.readNBytes((int) get(in)), UTF_8);
                long bytes = get(in), mtime = get(in);
                int width = (int) get(in), height = (int) get(in);
                long shot = get(in);
                entries.add(new Entry(i, name, bytes, mtime, width, height,
                        shot == 0 ? null : LocalDateTime.ofEpochSecond(unzigzag(shot - 1), 0, ZoneOffset.UTC),
                        versioned ? (int) get(in) : 0));
                prev = name;
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Signed to small unsigned (0, -1, 1, -2 … → 0, 1, 2, 3 …), so dates before 1970 stay short and never hit the 0 mark. */
    private static long zigzag(long v) { return v << 1 ^ v >> 63; }

    private static long unzigzag(long v) { return v >>> 1 ^ -(v & 1); }

    /** Unsigned LEB128: small for the usual non-negative values, a (rare) negative one still round-trips in 10 bytes. */
    private static void put(DataOutputStream out, long v) throws IOException {
        for (; (v & ~0x7FL) != 0; v >>>= 7) out.writeByte((int) (v & 0x7F | 0x80));
        out.writeByte((int) v);
    }

    private static long get(DataInputStream in) throws IOException {
        long v = 0;
        for (int shift = 0; ; shift += 7) {
            int b = in.readByte();
            v |= (long) (b & 0x7F) << shift;
            if (b >= 0) return v;
        }
    }
}
