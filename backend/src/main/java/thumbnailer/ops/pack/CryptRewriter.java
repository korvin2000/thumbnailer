package thumbnailer.ops.pack;

import com.dynatrace.hash4j.hashing.Hashing;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import thumbnailer.ops.Hashes;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Cry;

/**
 * Rewrites an archive into a zip in one {@link AlbumContainer#extract} pass with its plain images encrypted or its
 * {@code .cry} members decrypted (catalog_plan.md §4.5 Encrypt / Decrypt); members already in the target state and
 * other files are copied as they are. An encrypted member is named {@code <name>.cry} and embeds its base name; a
 * decrypted one takes the name it embeds, in its own folder; a name already taken gets {@link PhotoNames#numbered (2)}.
 */
public final class CryptRewriter {

    private static final Pattern UNSAFE = Pattern.compile("\\.{0,2}|.*[/\\\\:\\x00-\\x1f].*");

    private CryptRewriter() {}

    /**
     * {@code nameMap}: old → new name of each member encrypted or decrypted; {@code plain}: by new name, the hash of
     * its plaintext (what decrypting an encrypted member must give); {@code kept}: by name, the hash of a member
     * copied as it is.
     */
    public record Result(boolean encrypt, Map<String, String> nameMap, Map<String, Long> plain, Map<String, Long> kept) {}

    /** Writes {@code target} (a zip, whatever {@code raw} was) from the raw container {@code raw} with {@code key}. */
    public static Result rewrite(AlbumContainer raw, Path target, boolean encrypt, byte[] key) throws IOException {
        var items = raw.items();
        Set<String> taken = ConcurrentHashMap.newKeySet(); // result names, lower case: the sink may run on 7-Zip's threads
        items.stream().filter(i -> !changes(i.name(), encrypt)).forEach(i -> taken.add(lower(i.name())));
        var result = new Result(encrypt, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        try (var zip = new ZipPacker(target)) {
            raw.extract(items.stream().map(AlbumContainer.Item::name).toList(), (item, in) -> {
                var name = item.name();
                if (!changes(name, encrypt)) {
                    result.kept.put(name, zip.put(name, item.mtime(), item.bytes(), in));
                    return;
                }
                var buf = new ByteArrayOutputStream((int) Math.min(item.bytes() + 64, Integer.MAX_VALUE - 8));
                String to;
                long hash;
                if (encrypt) {
                    var h = new Hashed(in);
                    Cry.encrypt(h, item.bytes(), name.substring(name.lastIndexOf('/') + 1), key, buf);
                    to = free(name + ".cry", taken);
                    hash = h.hash();
                    zip.put(to, item.mtime(), buf.size(), new ByteArrayInputStream(buf.toByteArray()));
                } else {
                    var embedded = Cry.decrypt(in, item.bytes(), key, buf);
                    int slash = name.lastIndexOf('/');
                    var base = UNSAFE.matcher(embedded).matches() ? name.substring(slash + 1, name.length() - 4) : embedded;
                    to = free(name.substring(0, slash + 1) + base, taken);
                    hash = zip.put(to, item.mtime(), buf.size(), new ByteArrayInputStream(buf.toByteArray()));
                }
                result.nameMap.put(name, to);
                result.plain.put(to, hash);
            });
        }
        return result;
    }

    /**
     * Reopens a rewritten zip and checks every member against {@code r}: a kept one by its bytes, an encrypted one by
     * decrypting it with {@code key}, a decrypted one by its plaintext; anything missing or extra fails.
     */
    public static void verify(Path zip, Result r, byte[] key) throws IOException {
        try (var z = new ZipFile(zip.toFile())) {
            if (z.size() != r.kept.size() + r.plain.size()) throw new IOException(zip.getFileName() + " holds " + z.size() + " entries");
            for (var e : r.kept.entrySet()) check(z, e.getKey(), e.getValue(), null);
            for (var e : r.plain.entrySet()) check(z, e.getKey(), e.getValue(), r.encrypt ? key : null);
        }
    }

    private static void check(ZipFile z, String name, long hash, byte[] key) throws IOException {
        var entry = z.getEntry(name);
        if (entry == null) throw new IOException("Missing " + name);
        long h;
        try (InputStream in = z.getInputStream(entry)) {
            if (key == null) h = Hashes.of(in);
            else {
                var s = Hashing.wyhashFinal4().hashStream();
                Cry.decrypt(in, entry.getSize(), key, new OutputStream() {
                    @Override public void write(int b) { s.putByte((byte) b); }
                    @Override public void write(byte[] b, int off, int len) { s.putBytes(b, off, len); }
                });
                h = s.getAsLong();
            }
        }
        if (h != hash) throw new IOException("Differs from its source: " + name);
    }

    private static boolean changes(String name, boolean encrypt) {
        return encrypt ? AlbumContainer.isImage(name) && !Cry.is(name) : Cry.is(name);
    }

    private static String free(String name, Set<String> taken) {
        for (int n = 1; ; n++) {
            var candidate = PhotoNames.numbered(name, n);
            if (taken.add(lower(candidate))) return candidate;
        }
    }

    private static String lower(String name) { return name.toLowerCase(Locale.ROOT); }
}
