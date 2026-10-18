package thumbnailer.source;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import thumbnailer.settings.SettingsStore;
import thumbnailer.spi.AlbumContainer;

/**
 * xanderView's encrypted images, {@code .cry}, read byte for byte as that program writes them: big-endian
 * {@code int32 F | byte alg = 1 | AES-128/ECB/NoPadding(int16 L − 32768 | name[L] | image[F])}, the UTF-8 base name
 * zero-padded so the plaintext fills 16-byte blocks, hence {@code L == C − 2 − F} for a ciphertext of C bytes. Key =
 * PBKDF2WithHmacSHA1(trimmed password, salt "z!a3", 1024 rounds, 128 bits); its "AES_128/ECB" is the standard AES/ECB
 * with a 16-byte key. ECB decrypts any 16-aligned prefix on its own, so a probe costs no full read. Nothing is
 * authenticated: a wrong key fails the L check with probability 1 − 2⁻¹⁶. Every container opens {@linkplain #wrap
 * wrapped}: {@code .cry} members read as the images they hold, or without a key are no images at all.
 */
@Component
public class Cry {

    private static final Logger log = LoggerFactory.getLogger(Cry.class);
    private static final int HEADER = 5, CHUNK = 64 << 10;
    private static final ValueLayout.OfInt INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final HexFormat HEX = HexFormat.of();
    private static final String LOCKED = "+locked";

    private final SettingsStore settings;

    public Cry(SettingsStore settings) { this.settings = settings; }

    public static boolean is(String name) { return name.regionMatches(true, name.length() - 4, ".cry", 0, 4); }

    /** The AES key xanderView derives from the trimmed contents of its key file. */
    public static byte[] key(String password) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(new PBEKeySpec(password.trim().toCharArray(), "z!a3".getBytes(UTF_8), 1024, 128)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #encrypt(InputStream, long, String, byte[], OutputStream)} of a whole file in memory. */
    public static byte[] encrypt(byte[] plain, String name, byte[] key) {
        var out = new ByteArrayOutputStream(HEADER + plain.length + name.length() * 3 + 18);
        try {
            encrypt(new ByteArrayInputStream(plain), plain.length, name, key, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * Writes xanderView's file for the {@code size} image bytes of {@code in} under their original {@code name} (its
     * UTF-8 base name), 64 KiB at a time; the header needs the size up front. A padded name over 32767 bytes or an
     * image over 2 GiB cannot be written; fewer or more bytes than {@code size} fail.
     */
    public static void encrypt(InputStream in, long size, String name, byte[] key, OutputStream out) throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE) throw new IllegalArgumentException("Not a .cry size: " + size);
        var n = name.getBytes(UTF_8);
        int p = (int) ((16 - (size + 2 + n.length) % 16) % 16), l = n.length + p;
        if (l > 32767) throw new IllegalArgumentException("Name too long for a .cry file: " + n.length + " bytes");
        var cipher = aes(Cipher.ENCRYPT_MODE, key);
        out.write(ByteBuffer.allocate(HEADER).putInt((int) size).put((byte) 1).array());
        var buf = new byte[CHUNK];
        var head = ByteBuffer.allocate(2 + l).putShort((short) (l - 32768)).put(n).array();
        int at = head.length;
        System.arraycopy(head, 0, buf, 0, Math.min(at, CHUNK));
        for (long left = size; ; ) { // head (≤ 32769 bytes) shares the first chunk with the image
            int k = in.readNBytes(buf, at, (int) Math.min(CHUNK - at, left));
            left -= k;
            at += k;
            if (left == 0 || at == CHUNK) {
                out.write(cipher.update(buf, 0, at));
                at = 0;
            }
            if (left == 0) break;
            if (k == 0) throw new EOFException(name + " ended before " + size + " bytes");
        }
        if (in.read() >= 0) throw new IOException(name + " is longer than " + size + " bytes");
        try {
            out.write(cipher.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /**
     * Writes the image a {@code .cry} file of {@code size} bytes holds, 64 KiB at a time; returns the original name it
     * embeds. A wrong key (the header check) or a short file fails before or while writing.
     */
    public static String decrypt(InputStream in, long size, byte[] key, OutputStream out) throws IOException {
        var header = in.readNBytes(HEADER);
        if (header.length < HEADER || size < HEADER + 16 || (size - HEADER) % 16 != 0) throw new IOException("Not a .cry file");
        long f = ByteBuffer.wrap(header).getInt();
        var cipher = aes(Cipher.DECRYPT_MODE, key);
        var buf = new byte[CHUNK];
        long skip = -1, left = size - HEADER;
        String name = null;
        while (left > 0) {
            int k = (int) Math.min(CHUNK, left);
            if (in.readNBytes(buf, 0, k) < k) throw new EOFException("The .cry file ended early");
            left -= k;
            var plain = cipher.update(buf, 0, k);
            if (skip < 0) { // the first chunk holds the length field and the whole name (≤ 32769 bytes)
                int l = nameLength(plain);
                if (!header(f, l, size)) throw new IOException("Not a .cry file for this password");
                skip = 2 + l;
                int end = 2;
                while (end < skip && plain[end] != 0) end++;
                name = new String(plain, 2, end - 2, UTF_8);
            }
            int from = (int) Math.min(skip, plain.length);
            skip -= from;
            out.write(plain, from, plain.length - from);
        }
        return name;
    }

    /** {@link #decrypt(InputStream, long, byte[], OutputStream)} of a whole file in memory: the image it holds. */
    public static byte[] decrypt(byte[] file, byte[] key) throws IOException {
        var out = new ByteArrayOutputStream(Math.max(0, file.length - HEADER));
        decrypt(new ByteArrayInputStream(file), file.length, key, out);
        return out.toByteArray();
    }

    /** The original name a {@code .cry} file embeds, from its leading bytes (the header and the blocks holding the name). */
    public static String plainName(byte[] head, byte[] key) throws IOException { return plainName(head, -1, key); }

    /**
     * {@link #plainName(byte[], byte[])} of a file of {@code size} bytes (negative = unknown): with the size known, a
     * key the header check of {@link #decrypt} refuses fails here already.
     */
    public static String plainName(byte[] head, long size, byte[] key) throws IOException {
        int blocks = (head.length - HEADER) & ~15;
        if (blocks <= 0 || size >= 0 && (size < HEADER + 16 || (size - HEADER) % 16 != 0)) throw new IOException("Not a .cry file");
        byte[] plain;
        try {
            plain = aes(Cipher.DECRYPT_MODE, key).doFinal(head, HEADER, blocks);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
        int end = 2, l = nameLength(plain), stop = 2 + l;
        if (!header(ByteBuffer.wrap(head).getInt(), l, size)) throw new IOException("Not a .cry file for this password");
        while (end < Math.min(stop, blocks) && plain[end] != 0) end++;
        if (end == blocks && end < stop) throw new IOException("The head ends inside the name");
        return new String(plain, 2, end - 2, UTF_8);
    }

    /** Decrypts with the configured key ({@code cryKey}, hex), captured now: a later change does not reach this container. */
    public AlbumContainer wrap(AlbumContainer in) {
        var hex = settings.get().cryKey();
        return wrap(in, hex == null ? null : HEX.parseHex(hex));
    }

    static AlbumContainer wrap(AlbumContainer in, byte[] key) { return new Decrypted(in, key, tag(key)); }

    /** The key state a stored signature carries: "+locked", or "+" and its {@linkplain #fingerprint fingerprint}. */
    static String tag(byte[] key) { return key == null ? LOCKED : "+" + fingerprint(key); }

    /** 8 hex digits of SHA-256(key): names a key without revealing it (signatures, the wire). */
    public static String fingerprint(byte[] key) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(key), 0, 4);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stored form of a signature: the container's, plus the key state of its {@code .cry} members or "+" if it has none. */
    public static String signature(AlbumContainer c) {
        return Stamps.signature(c) + (c.items().stream().anyMatch(i -> is(i.name())) ? c.keyTag() : "+");
    }

    /**
     * Whether a stored signature still holds without listing the container: same files, and if any were encrypted, the
     * same key, or no key now (the key is session-only: a launch without it keeps the album as indexed, only its
     * {@code .cry} reads fail). A different key, a key where there was none, or a signature stored before {@code .cry}
     * support re-indexes.
     */
    public static boolean matches(String stored, AlbumContainer c) {
        var sig = Stamps.signature(c);
        var tag = stored.startsWith(sig) ? stored.substring(sig.length()) : "";
        return tag.equals("+") || tag.equals(c.keyTag()) || c.keyTag().equals(LOCKED) && tag.matches("\\+\\p{XDigit}{8}");
    }

    /** Other members pass through. Arrays from the inner container are never written: 7-Zip hands out its cached heads. */
    private static final class Decrypted implements AlbumContainer {
        private final AlbumContainer in;
        private final byte[] key;
        private final String tag;
        private volatile Map<String, Long> sizes;
        private final AtomicInteger refused = new AtomicInteger();

        Decrypted(AlbumContainer in, byte[] key, String tag) {
            this.in = in;
            this.key = key;
            this.tag = tag;
        }

        @Override public String name() { return in.name(); }
        @Override public List<Item> items() { return in.items(); }
        @Override public String signature() { return Stamps.signature(in); }
        @Override public String contentId() { return Stamps.contentId(in); }
        @Override public boolean sequential() { return in.sequential(); }
        @Override public boolean cpuBound() { return in.cpuBound(); }
        @Override public String keyTag() { return tag; }

        @Override
        public List<Item> images() {
            return key != null ? in.images() : in.images().stream().filter(i -> !is(i.name())).toList();
        }

        @Override
        public InputStream open(String name) throws IOException {
            if (!is(name)) return in.open(name);
            var whole = in.head(name, Integer.MAX_VALUE);
            return new ByteArrayInputStream(decrypt(name, whole, whole.length));
        }

        @Override
        public byte[] head(String name, int limit) throws IOException {
            var head = in.head(name, limit);
            return is(name) ? decrypt(name, head, sizes().getOrDefault(name, -1L)) : head;
        }

        @Override
        public void heads(Collection<String> names, int limit, HeadSink sink) throws IOException {
            if (key == null) names = names.stream().filter(n -> !is(n)).toList(); // entries stored with a key, asked without
            else if (names.stream().anyMatch(Cry::is)) sizes(); // listed before the pass: a sink must not touch the container
            in.heads(names, limit, (name, head) -> {
                if (is(name)) {
                    try {
                        head = decrypt(name, head, sizes().getOrDefault(name, -1L));
                    } catch (IOException e) {
                        return; // skipped, as the container skips a member it cannot read
                    }
                }
                sink.accept(name, head);
            });
        }

        @Override
        public MemorySegment read(String name, Arena arena) throws IOException {
            var data = in.read(name, arena);
            return is(name) ? decrypt(name, data) : data;
        }

        @Override
        public void close() throws IOException {
            if (refused.get() > 0) log.warn("{} .cry files in {} do not decrypt with the configured password", refused, in.name());
            in.close();
        }

        private Map<String, Long> sizes() {
            var s = sizes;
            if (s == null) sizes = s = in.items().stream().collect(toMap(Item::name, Item::bytes, (a, b) -> a));
            return s;
        }

        /** The image bytes a head (or a whole file of {@code size} bytes, negative = unknown) holds, in a new array. */
        private byte[] decrypt(String name, byte[] cry, long size) throws IOException {
            var cipher = cipher(name);
            int blocks = (cry.length - HEADER) & ~15;
            if (blocks <= 0) throw refuse(name);
            var out = new byte[blocks];
            try {
                cipher.doFinal(cry, HEADER, blocks, out, 0);
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            int skip = skip(name, ByteBuffer.wrap(cry).getInt(), size, out);
            return skip >= blocks ? new byte[0] : Arrays.copyOfRange(out, skip, blocks);
        }

        /** Decrypts a whole file in place, 64 KiB at a time through two heap buffers: the image ends up at offset 0. */
        private MemorySegment decrypt(String name, MemorySegment cry) throws IOException {
            var cipher = cipher(name);
            long n = cry.byteSize();
            if (n < HEADER + 16 || (n - HEADER) % 16 != 0) throw refuse(name);
            int f = cry.get(INT_BE, 0);
            var cin = new byte[CHUNK];
            var cout = new byte[CHUNK];
            long src = HEADER, dst = 0;
            int skip = -1;
            try {
                while (src < n) {
                    int k = (int) Math.min(CHUNK, n - src);
                    MemorySegment.copy(cry, ValueLayout.JAVA_BYTE, src, cin, 0, k);
                    int m = cipher.update(cin, 0, k, cout, 0);
                    if (skip < 0) skip = skip(name, f, n, cout);
                    int from = Math.min(skip, m);
                    skip -= from;
                    MemorySegment.copy(cout, from, cry, ValueLayout.JAVA_BYTE, dst, m - from);
                    dst += m - from;
                    src += k;
                }
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
            return cry.asSlice(0, f);
        }

        /** The bytes before the image (length field and name) once the header predicts L; a wrong key fails here. */
        private int skip(String name, int f, long size, byte[] plain) throws IOException {
            int l = nameLength(plain);
            if (!header(f, l, size)) throw refuse(name);
            return 2 + l;
        }

        private IOException refuse(String name) {
            refused.incrementAndGet();
            return new IOException("Not a .cry file for this password: " + name);
        }

        private Cipher cipher(String name) throws IOException {
            if (key == null) throw new IOException("No password for " + name);
            return aes(Cipher.DECRYPT_MODE, key);
        }
    }

    /** The padded name's length, from the first two plaintext bytes (biased by 32768). */
    private static int nameLength(byte[] plain) { return ((plain[0] & 0xFF) << 8 | plain[1] & 0xFF) ^ 0x8000; }

    /**
     * Whether image length {@code f} and padded name length {@code l} are what the writer gives a file of {@code size}
     * bytes (negative = unknown): every reader checks this before it touches the name; a wrong key fails it.
     */
    private static boolean header(long f, int l, long size) {
        return f >= 0 && l <= 32767 && (size < 0 || (size - HEADER) % 16 == 0 && l == size - HEADER - 2 - f);
    }

    private static Cipher aes(int mode, byte[] key) {
        try {
            var c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
