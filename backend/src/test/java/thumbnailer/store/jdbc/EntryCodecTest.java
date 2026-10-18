package thumbnailer.store.jdbc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
        assertThat(EntryCodec.unpack(packed)).isEqualTo(ENTRIES);
        assertThat(EntryCodec.unpack(EntryCodec.pack(List.of()))).isEmpty();
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
        assertThat(EntryCodec.unpack(legacy.toByteArray())).isEqualTo(ENTRIES.stream().map(e -> new Entry(e.index(), e.name(),
                e.bytes(), e.mtime(), e.width(), e.height(), e.shotAt(), 0)).toList());
        assertThat(EntryCodec.unpack(new byte[]{0})).as("an empty list of the first layout").isEmpty();
    }

    private static void put(ByteArrayOutputStream out, long v) {
        for (; (v & ~0x7FL) != 0; v >>>= 7) out.write((int) (v & 0x7F | 0x80));
        out.write((int) v);
    }
}
