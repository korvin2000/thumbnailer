package thumbnailer.metadata;

import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import thumbnailer.album.Entry;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Kind;

/**
 * Everything an extractor or cover strategy may look at; built once per index by the indexer, shared by both chains.
 * {@code longestDir}: the longest directory name inside an archive (null for a folder); {@code sampleImage}: the median
 * image name; {@code texts}: the {@code *.txt} members by name, decoded ({@link #decode}) within {@link #TEXT_FILES},
 * {@link #TEXT_BYTES} each and {@link #TEXT_TOTAL} in all; {@code container}: null outside indexing.
 */
public record Extraction(String name, String path, Kind kind, List<Entry> images, List<AlbumContainer.Item> items,
                         String longestDir, String sampleImage, Map<String, String> texts, AlbumContainer container) {

    public static final int TEXT_FILES = 8, TEXT_BYTES = 64 << 10, TEXT_TOTAL = 256 << 10;

    public Extraction {
        images = List.copyOf(images);
        items = List.copyOf(items);
        texts = Map.copyOf(texts);
    }

    /** Entries only, no container: cover choice outside indexing. */
    public static Extraction of(List<Entry> images) {
        return new Extraction(null, null, null, images, List.of(), null, sample(images), Map.of(), null);
    }

    /** The median image name in name order, or null. */
    public static String sample(List<Entry> images) { return images.isEmpty() ? null : images.get(images.size() / 2).name(); }

    /** The longest directory name among the members of an archive; null if none lies in a directory. */
    public static String longestDir(List<AlbumContainer.Item> items) {
        String longest = null;
        for (var item : items) {
            var parts = item.name().split("/");
            for (int i = 0; i < parts.length - 1; i++)
                if (longest == null || parts[i].length() > longest.length()) longest = parts[i];
        }
        return longest == null || longest.isEmpty() ? null : longest;
    }

    /**
     * The text members to read and how many bytes of each: {@code album.txt} first, then by name, within the
     * limits (files, bytes per file, total).
     */
    public static Map<String, Integer> budget(List<AlbumContainer.Item> items) {
        var budget = new LinkedHashMap<String, Integer>();
        int left = TEXT_TOTAL;
        for (var item : items.stream().filter(i -> isText(i.name()))
                .sorted(Comparator.comparing((AlbumContainer.Item i) -> !fileName(i.name()).equals("album.txt"))).toList()) {
            if (budget.size() == TEXT_FILES || left == 0) break;
            int n = (int) Math.min(item.bytes(), Math.min(TEXT_BYTES, left));
            budget.put(item.name(), n);
            left -= n;
        }
        return budget;
    }

    public static boolean isText(String name) { return fileName(name).endsWith(".txt"); }

    /** Lower-case file name of an entry path. */
    public static String fileName(String entry) { return entry.substring(entry.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT); }

    /**
     * The first {@code n} bytes of {@code b} as text, in the charset all of {@code b} shows (read past {@code n} for
     * that): its BOM (UTF-8, UTF-16 LE/BE), else strict UTF-8, else CP1251. {@code more}: the file goes on past
     * {@code b}, so an incomplete UTF-8 sequence at its end is not taken for CP1251. A character cut at {@code n}
     * (short of the file's end) is dropped.
     */
    public static String decode(byte[] b, int n, boolean more) {
        boolean cut = more || n < b.length;
        if (b.length >= 3 && (b[0] & 255) == 0xEF && (b[1] & 255) == 0xBB && (b[2] & 255) == 0xBF)
            return new String(b, 3, Math.max(0, (cut ? complete(b, n) : n) - 3), UTF_8);
        if (b.length >= 2 && (b[0] & 255) == 0xFF && (b[1] & 255) == 0xFE) return new String(b, 2, Math.max(0, n - 2) & ~(cut ? 1 : 0), UTF_16LE);
        if (b.length >= 2 && (b[0] & 255) == 0xFE && (b[1] & 255) == 0xFF) return new String(b, 2, Math.max(0, n - 2) & ~(cut ? 1 : 0), UTF_16BE);
        try {
            UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, 0, more ? complete(b, b.length) : b.length));
            return new String(b, 0, cut ? complete(b, n) : n, UTF_8); // a prefix of valid UTF-8, ending on a whole character
        } catch (CharacterCodingException e) {
            return new String(b, 0, n, Charset.forName("windows-1251"));
        }
    }

    /** {@code end}, less a trailing incomplete UTF-8 sequence before it. */
    private static int complete(byte[] b, int end) {
        for (int i = end - 1, back = 0; i >= 0 && back < 4; i--, back++) {
            int c = b[i] & 255;
            if (c < 0x80) return end; // ASCII: nothing open
            if (c >= 0xC0) { // a lead byte: is its sequence whole?
                int need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : 2;
                return back + 1 >= need ? end : i;
            }
        }
        return end;
    }
}
