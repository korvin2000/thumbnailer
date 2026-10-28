package thumbnailer.store.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import thumbnailer.spi.Entry;

class EntryCodecTest {

    private static final List<Entry> ENTRIES = List.of(
            new Entry(0, "IMG_0001.jpg", 4_000_000, 1_700_000_000_123L, 6000, 4000, LocalDateTime.of(2019, 5, 1, 10, 0, 7), Entry.META_KNOWN | Entry.META),
            new Entry(1, "IMG_0002.jpg", 300, 5, 640, 480, null, Entry.META_KNOWN));

    @Test
    void roundTripsWithFlagsBehindTheVersionMarker() {
        var packed = EntryCodec.pack(ENTRIES);
        assertThat(packed).startsWith(0, 1);
        assertThat(EntryCodec.unpack("a", packed)).isEqualTo(ENTRIES);
        assertThat(EntryCodec.unpack("a", EntryCodec.pack(List.of()))).isEmpty();
        var extremes = List.of(new Entry(0, "", Long.MAX_VALUE, Long.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, LocalDateTime.of(-999, 1, 1, 0, 0), -1),
                new Entry(1, "a", -1, 0, -1, 0, LocalDateTime.of(9999, 12, 31, 23, 59, 59), Integer.MIN_VALUE));
        assertThat(EntryCodec.unpack("a", EntryCodec.pack(extremes))).as("10-byte varints, ints at their bounds").isEqualTo(extremes);
    }

    @Test
    void rejectsCorruptValuesNamingTheAlbum() {
        var valid = versioned(1, 0, 1, 'a', 1, 2, 3, 4, 0, 0); // count, then shared, suffix length, suffix, bytes, mtime, w, h, shot, flags
        assertThat(EntryCodec.unpack("a", valid)).containsExactly(new Entry(0, "a", 1, 2, 3, 4, null, 0));
        corrupt(Arrays.copyOf(valid, valid.length - 1), "truncated");
        corrupt(Arrays.copyOf(valid, valid.length + 1), "1 trailing bytes");
        corrupt(versioned(Integer.MAX_VALUE), "count 2147483647 out of 0..0");
        corrupt(versioned(1, -1, 1, 'a', 1, 2, 3, 4, 0, 0), "prefix -1 out of 0..0");
        corrupt(versioned(2, 0, 1, 'a', 1, 2, 3, 4, 0, 0, 2, 0, 1, 2, 3, 4, 0, 0), "prefix 2 out of 0..1");
        corrupt(versioned(1, 0, 100, 'a', 1, 2, 3, 4, 0, 0), "suffix 100 out of 0..8");
        corrupt(versioned(1, 0, 1, 'a', 1, 2, 1L << 32, 4, 0, 0), "4294967296 out of int range");
        corrupt(new byte[]{0, 1, -128, -128, -128, -128, -128, -128, -128, -128, -128, 2}, "varint over 64 bits");
        corrupt(new byte[]{0, 9, 0}, "unknown version 9");
    }

    private static void corrupt(byte[] packed, String why) {
        assertThatThrownBy(() -> EntryCodec.unpack("a", packed)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("corrupt entries for a: ").hasMessageContaining(why);
    }

    private static byte[] versioned(long... varints) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0, 1});
        for (var v : varints) put(out, v);
        return out.toByteArray();
    }

    @Test
    void readsTheFirstLayoutWithUnknownFlags() {
        var legacy = new ByteArrayOutputStream(); // the layout before flags, written as that version did
        put(legacy, 2);
        String prev = "";
        for (var e : ENTRIES) {
            int shared = 0;
            while (shared < Math.min(prev.length(), e.name().length()) && prev.charAt(shared) == e.name().charAt(shared)) shared++;
            var suffix = e.name().substring(shared).getBytes(UTF_8);
            put(legacy, shared);
            put(legacy, suffix.length);
            legacy.writeBytes(suffix);
            put(legacy, e.bytes());
            put(legacy, e.mtime());
            put(legacy, e.width());
            put(legacy, e.height());
            put(legacy, e.shotAt() == null ? 0 : (e.shotAt().toEpochSecond(ZoneOffset.UTC) << 1) + 1);
            prev = e.name();
        }
        assertThat(EntryCodec.unpack("a", legacy.toByteArray())).isEqualTo(ENTRIES.stream().map(e -> new Entry(e.index(), e.name(),
                e.bytes(), e.mtime(), e.width(), e.height(), e.shotAt(), 0)).toList());
        assertThat(EntryCodec.unpack("a", new byte[]{0})).as("an empty list of the first layout").isEmpty();
    }

    private static void put(ByteArrayOutputStream out, long v) {
        for (; (v & ~0x7FL) != 0; v >>>= 7) out.write((int) (v & 0x7F | 0x80));
        out.write((int) v);
    }
}
