package thumbnailer.source;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
        return c.signature() + (c.items().stream().anyMatch(i -> is(i.name())) ? c.keyTag() : "+");
    }

    /**
     * Whether a stored signature still holds without listing the container: same files, and if any were encrypted, the
     * same key, or no key now (the key is session-only: a launch without it keeps the album as indexed, only its
     * {@code .cry} reads fail). A different key, a key where there was none, or a signature stored before {@code .cry}
     * support re-indexes.
     */
    public static boolean matches(String stored, AlbumContainer c) {
        var sig = c.signature();
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
        @Override public String signature() { return in.signature(); }
        @Override public String contentId() { return in.contentId(); }
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
            int l = ((plain[0] & 0xFF) << 8 | plain[1] & 0xFF) ^ 0x8000;
            boolean ok = f >= 0 && (size < 0 ? l <= 32767 : (size - HEADER) % 16 == 0 && l == size - HEADER - 2 - f);
            if (!ok) throw refuse(name);
            return 2 + l;
        }

        private IOException refuse(String name) {
            refused.incrementAndGet();
            return new IOException("Not a .cry file for this password: " + name);
        }

        private Cipher cipher(String name) throws IOException {
            if (key == null) throw new IOException("No password for " + name);
            try {
                var c = Cipher.getInstance("AES/ECB/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
                return c;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
