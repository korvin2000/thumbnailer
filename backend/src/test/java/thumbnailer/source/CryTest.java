package thumbnailer.source;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import net.sf.sevenzipjbinding.ArchiveFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.image.ImageProbe;

/**
 * Against {@code cry-golden.tsv}: files written by xanderView's own {@code Encryptor.encrypt0} with the password
 * "test" (17 sizes × name lengths covering every padding, a PNG with a Cyrillic name, a 100 KiB payload), which also
 * validate {@link #encrypt}, the test writer used for everything larger.
 */
public class CryTest {

    static final byte[] KEY = Cry.key("test");
    static final HexFormat HEX = HexFormat.of();
    record Golden(String name, byte[] plain, byte[] cry) {}
    static final List<Golden> GOLDEN = new ArrayList<>();
    static final Map<String, String> FACTS = new LinkedHashMap<>(); // PNG → "plainHex\tcryHex", BIG → shas, KEY → hex

    static {
        try (var in = CryTest.class.getResourceAsStream("cry-golden.tsv")) {
            for (var line : new String(in.readAllBytes(), UTF_8).split("\n")) {
                var f = line.strip().split("\t");
                if (f[0].equals("CASE")) GOLDEN.add(new Golden(f[1], HEX.parseHex(f[2]), HEX.parseHex(f[3])));
                else if (f.length > 1) FACTS.put(f[0], String.join("\t", Arrays.copyOfRange(f, 1, f.length)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TempDir Path dir;

    /** xanderView's layout: header, biased name length, zero padding of the name to fill the last block. */
    public static byte[] encrypt(byte[] plain, String name, byte[] key) {
        var n = name.getBytes(UTF_8);
        int p = (16 - (plain.length + 2 + n.length) % 16) % 16, l = n.length + p;
        var body = ByteBuffer.allocate(2 + l + plain.length).putShort((short) (Math.min(l, 32767) - 32768)).put(n).put(new byte[p]).put(plain).array();
        try {
            var c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return ByteBuffer.allocate(5 + body.length).putInt(plain.length).put((byte) 1).put(c.doFinal(body)).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void keyAndWriterMatchXanderView() throws Exception {
        assertThat(HEX.formatHex(KEY)).isEqualTo(FACTS.get("KEY"));
        for (var g : GOLDEN) assertThat(encrypt(g.plain, g.name, KEY)).as(g.name).isEqualTo(g.cry);
        var png = FACTS.get("PNG").split("\t");
        assertThat(encrypt(HEX.parseHex(png[0]), "Фото №1.png", KEY)).isEqualTo(HEX.parseHex(png[1]));
        var big = big();
        var sha = MessageDigest.getInstance("SHA-256");
        assertThat(FACTS.get("BIG")).contains("sha256(plain)=" + HEX.formatHex(sha.digest(big)))
                .contains("sha256(cry)=" + HEX.formatHex(sha.digest(encrypt(big, "big.bin", KEY))));
    }

    @Test
    void goldenFilesDecryptWholeAsHeadsAndAsStreams() throws IOException {
        for (var g : GOLDEN) Files.write(dir.resolve(g.name + ".cry"), g.cry);
        var png = FACTS.get("PNG").split("\t");
        Files.write(dir.resolve("png.cry"), HEX.parseHex(png[1]));
        try (var c = Cry.wrap(new DirectoryContainer(dir, Exclusions.NONE), KEY); var arena = Arena.ofConfined()) {
            assertThat(c.images()).hasSize(GOLDEN.size() + 1);
            for (var g : GOLDEN) {
                var name = g.name + ".cry";
                assertThat(c.read(name, arena).toArray(ValueLayout.JAVA_BYTE)).as("read " + name).isEqualTo(g.plain);
                assertThat(c.head(name, ImageProbe.HEAD)).as("head " + name).isEqualTo(g.plain);
                try (var in = c.open(name)) {
                    assertThat(in.readAllBytes()).as("open " + name).isEqualTo(g.plain);
                }
            }
            assertThat(c.read("png.cry", arena).toArray(ValueLayout.JAVA_BYTE)).isEqualTo(HEX.parseHex(png[0]));
            var probe = ImageProbe.probe(c.head("png.cry", ImageProbe.HEAD));
            assertThat(probe.width() + "x" + probe.height()).isEqualTo("3x2");
            assertThat(c.keyTag()).isEqualTo(Cry.tag(KEY)).startsWith("+").hasSize(9);
        }
    }

    @Test
    void aHeadDecryptsAlone_aWrongKeyIsRefused_noKeyHidesTheFiles() throws IOException {
        var big = big();
        Files.write(dir.resolve("big.cry"), encrypt(big, "big.bin", KEY));
        Files.write(dir.resolve("plain.jpg"), new byte[]{1, 2, 3});
        try (var c = Cry.wrap(new DirectoryContainer(dir, Exclusions.NONE), KEY)) {
            // 4096 bytes read: 4080 decrypt, minus the length field and the 14-byte padded name
            assertThat(c.head("big.cry", 4096)).isEqualTo(Arrays.copyOf(big, 4080 - 16));
            assertThat(c.head("plain.jpg", 4096)).containsExactly(1, 2, 3);
        }
        var wrong = new byte[16];
        try (var c = Cry.wrap(new DirectoryContainer(dir, Exclusions.NONE), wrong); var arena = Arena.ofConfined()) {
            assertThat(c.images()).hasSize(2);
            assertThatThrownBy(() -> c.read("big.cry", arena)).isInstanceOf(IOException.class).hasMessageContaining("password");
            assertThatThrownBy(() -> c.head("big.cry", 4096)).isInstanceOf(IOException.class);
            var seen = new ArrayList<String>();
            c.heads(List.of("big.cry", "plain.jpg"), 4096, (name, head) -> seen.add(name));
            assertThat(seen).as("skipped like an unreadable file").containsExactly("plain.jpg");
        }
        try (var c = Cry.wrap(new DirectoryContainer(dir, Exclusions.NONE), null); var arena = Arena.ofConfined()) {
            assertThat(c.images()).extracting(AlbumContainer.Item::name).containsExactly("plain.jpg");
            assertThat(c.items()).hasSize(2);
            assertThat(c.keyTag()).isEqualTo("+locked");
            assertThatThrownBy(() -> c.read("big.cry", arena)).isInstanceOf(IOException.class);
            var seen = new ArrayList<String>();
            c.heads(List.of("big.cry", "plain.jpg"), 4096, (name, head) -> seen.add(name));
            assertThat(seen).containsExactly("plain.jpg");
        }
    }

    @Test
    void archivesDecryptInOnePassAndKeepTheirHeadsReadable() throws IOException {
        var a = new byte[300 << 10];
        var b = new byte[70 << 10];
        new Random(7).nextBytes(a);
        new Random(8).nextBytes(b);
        var files = new LinkedHashMap<String, byte[]>();
        files.put("a.cry", encrypt(a, "a.jpg", KEY));
        files.put("b.cry", encrypt(b, "b.png", KEY));
        files.put("c.jpg", new byte[]{9});
        var solid = dir.resolve("solid.7z");
        Archives.sevenZip(solid, ArchiveFormat.SEVEN_ZIP, files, true);
        var zip = dir.resolve("z.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
            }
        }
        for (var inner : List.of(Archives.open(solid), new ZipContainer(zip, Exclusions.NONE))) {
            try (var c = Cry.wrap(inner, KEY); var arena = Arena.ofConfined()) {
                var heads = new LinkedHashMap<String, byte[]>();
                c.heads(List.of("a.cry", "b.cry", "c.jpg"), ImageProbe.HEAD, heads::put);
                assertThat(heads.keySet()).containsExactlyInAnyOrder("a.cry", "b.cry", "c.jpg");
                assertThat(heads.get("a.cry")).isEqualTo(Arrays.copyOf(a, heads.get("a.cry").length)).hasSizeBetween(ImageProbe.HEAD - 64, ImageProbe.HEAD);
                assertThat(heads.get("b.cry")).isEqualTo(b);
                assertThat(heads.get("c.jpg")).containsExactly(9);
                assertThat(c.head("a.cry", ImageProbe.HEAD)).as("a cached head is decrypted again, not in place").isEqualTo(heads.get("a.cry"));
                assertThat(c.read("a.cry", arena).toArray(ValueLayout.JAVA_BYTE)).isEqualTo(a);
                assertThat(c.sequential()).isEqualTo(inner instanceof SevenZipContainer);
            }
        }
    }

    @Test
    void storedSignaturesCarryTheKeyStateOfEncryptedMembersOnly() throws IOException {
        Files.write(dir.resolve("a.jpg"), new byte[]{1});
        var plain = Cry.wrap(new DirectoryContainer(dir, Exclusions.NONE), KEY);
        var sig = plain.signature();
        assertThat(Cry.signature(plain)).isEqualTo(sig + "+");
        assertThat(Cry.matches(sig + "+", plain)).isTrue();
        assertThat(Cry.matches(sig, plain)).as("indexed before .cry support: once more").isFalse();

        Files.write(dir.resolve("b.cry"), encrypt(new byte[]{2}, "b.jpg", KEY));
        var keyed = Cry.wrap(new DirectoryContainer(dir, Exclusions.NONE), KEY);
        var locked = Cry.wrap(new DirectoryContainer(dir, Exclusions.NONE), null);
        sig = keyed.signature();
        assertThat(Cry.signature(keyed)).isEqualTo(sig + Cry.tag(KEY));
        assertThat(Cry.signature(locked)).isEqualTo(sig + "+locked");
        assertThat(keyed.contentId()).as("identity does not depend on the key").isEqualTo(locked.contentId());
        assertThat(Cry.matches(sig + Cry.tag(KEY), keyed)).isTrue();
        assertThat(Cry.matches(sig + Cry.tag(KEY), locked)).as("no key now: kept as indexed").isTrue();
        assertThat(Cry.matches(sig + "+locked", keyed)).isFalse();
        assertThat(Cry.matches(sig + Cry.tag(new byte[16]), keyed)).isFalse();
    }

    /** The generator's 100 KiB payload: its {@code Random(42)} after the 17 case payloads. */
    private static byte[] big() {
        var rnd = new Random(42);
        for (int i = 1; i <= 17; i++) rnd.nextBytes(new byte[i]);
        var big = new byte[100 << 10];
        rnd.nextBytes(big);
        return big;
    }
}
