package thumbnailer.ops;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Cry;

/**
 * Encrypting and decrypting with an operation's own key through {@link Cry}'s format code (its container wrap uses the
 * session's key, which may be gone by the time the operation runs): the name map of a container, the transform of one
 * file (an encryption is checked by a round trip before anything is written), and the view an archive is rewritten
 * through by the {@link thumbnailer.ops.pack.Packer}, so a rewrite verifies like any pack.
 */
final class Crypt {

    private static final int HEAD = 4096; // the header and the blocks holding any real name

    private Crypt() {}

    /**
     * What encrypting or decrypting renames in {@code raw}, old → new, unique within each folder: plain images get
     * {@code .cry}, {@code .cry} files take their embedded name. A {@code .cry} file the key does not open fails it all.
     */
    static Map<String, String> names(AlbumContainer raw, byte[] key, boolean encrypt) throws IOException {
        var items = raw.items();
        var taken = items.stream().map(i -> i.name().toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(HashSet::new));
        var names = new LinkedHashMap<String, String>();
        var sizes = items.stream().collect(Collectors.toMap(AlbumContainer.Item::name, AlbumContainer.Item::bytes));
        var plain = new ConcurrentHashMap<String, String>();
        if (!encrypt) raw.heads(items.stream().map(AlbumContainer.Item::name).filter(Cry::is).toList(), HEAD, (name, head) ->
                plain.put(name, name(head, sizes.get(name), key)));
        for (var i : items) {
            var n = i.name();
            if (encrypt ? !AlbumContainer.isImage(n) || Cry.is(n) : !Cry.is(n)) continue;
            if (!encrypt && !plain.containsKey(n)) throw new IOException("Unreadable: " + n);
            int slash = n.lastIndexOf('/') + 1;
            var to = encrypt ? n + ".cry" : n.substring(0, slash) + plain.get(n);
            for (int k = 2; taken.contains(to.toLowerCase(Locale.ROOT)); k++)
                to = encrypt ? n.substring(0, slash) + Trees.numbered(n.substring(slash), k, true) + ".cry"
                        : n.substring(0, slash) + Trees.numbered(plain.get(n), k, true);
            taken.add(to.toLowerCase(Locale.ROOT));
            names.put(n, to);
        }
        return names;
    }

    /** The embedded base name of a {@code .cry} file of {@code size} bytes, or an IOException when the key does not fit. */
    static String name(byte[] head, long size, byte[] key) throws IOException {
        var name = Cry.plainName(head, size, key);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        if (name.isBlank() || name.equals("..")) throw new IOException("No usable name inside");
        return name;
    }

    /** {@code bytes} of file {@code name} encrypted (under its base name) or decrypted; an encryption is decrypted again and compared first. */
    static byte[] transform(String name, byte[] bytes, byte[] key, boolean encrypt) throws IOException {
        if (!encrypt) return Cry.decrypt(bytes, key);
        var out = Cry.encrypt(bytes, name.substring(name.lastIndexOf('/') + 1), key);
        if (!Arrays.equals(Cry.decrypt(out, key), bytes)) throw new IOException("Encryption does not read back: " + name);
        return out;
    }

    /**
     * {@code raw} as it reads encrypted or decrypted: the files of {@code names} renamed and transformed, the rest as is.
     * Each transformed file is read whole; sizes come from one {@code heads} pass (decrypt) or the format (encrypt).
     */
    static AlbumContainer view(AlbumContainer raw, Map<String, String> names, byte[] key, boolean encrypt) throws IOException {
        var back = names.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        var plainSizes = new ConcurrentHashMap<String, Long>();
        if (!encrypt) raw.heads(names.keySet(), 4, (n, head) -> plainSizes.put(n, (long) ByteBuffer.wrap(head).getInt()));
        var items = raw.items().stream().map(i -> !names.containsKey(i.name()) ? i : new AlbumContainer.Item(names.get(i.name()),
                encrypt ? encryptedSize(i.bytes(), i.name()) : plainSizes.getOrDefault(i.name(), -1L), i.mtime()))
                .sorted(AlbumContainer.BY_NAME).toList();
        return new AlbumContainer() {
            @Override public String name() { return raw.name(); }
            @Override public List<Item> items() { return items; }
            @Override public boolean sequential() { return raw.sequential(); }
            @Override public boolean cpuBound() { return raw.cpuBound(); }
            @Override public void close() throws IOException { raw.close(); }

            @Override
            public InputStream open(String name) throws IOException {
                var old = back.getOrDefault(name, name);
                if (!names.containsKey(old)) return raw.open(name);
                try (var in = raw.open(old)) {
                    return new ByteArrayInputStream(transform(old, in.readAllBytes(), key, encrypt));
                }
            }

            @Override
            public void extract(Collection<String> wanted, EntrySink sink) throws IOException {
                var byOld = items.stream().collect(Collectors.toMap(i -> back.getOrDefault(i.name(), i.name()), i -> i));
                raw.extract(wanted.stream().map(n -> back.getOrDefault(n, n)).toList(), (item, in) -> {
                    if (!names.containsKey(item.name())) sink.accept(item, in);
                    else sink.accept(byOld.get(item.name()), new ByteArrayInputStream(transform(item.name(), in.readAllBytes(), key, encrypt)));
                });
            }
        };
    }

    /** The size {@link Cry#encrypt} gives a file of {@code bytes} under the base name of {@code name}. */
    private static long encryptedSize(long bytes, String name) {
        int n = name.substring(name.lastIndexOf('/') + 1).getBytes(UTF_8).length;
        return 5 + 2 + n + (16 - (bytes + 2 + n) % 16) % 16 + bytes;
    }
}
