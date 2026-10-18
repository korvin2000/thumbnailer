package thumbnailer.ops;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.ops.pack.Packers;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Archives;
import thumbnailer.source.Containers;
import thumbnailer.source.Cry;
import thumbnailer.source.Kind;

/**
 * An archive rewritten encrypted, then decrypted, through {@link Crypt#view} and the real packers (zip and 7z): the
 * names, the bytes ({@code .cry} files as {@link Cry} writes them, decrypting to the source), and the hashes the
 * packer returns are what the archive reads back as.
 */
class CryptTest {

    static final byte[] KEY = Cry.key("test");

    @TempDir Path dir;

    final Containers containers = Archives.containers();
    final Packers packers = new Packers(containers);

    @Test
    void archivesAreRewrittenEncryptedThenDecryptedAndReadBackAsPacked() throws IOException {
        var rnd = new SplittableRandom(4);
        var plain = new LinkedHashMap<String, byte[]>();
        for (var n : List.of("a.jpg", "b.jpg", "sub/c.png")) plain.put(n, random(rnd, 30_000 + n.length()));
        var source = new LinkedHashMap<String, byte[]>();
        source.put("a.jpg", plain.get("a.jpg"));
        source.put("b.jpg.cry", Cry.encrypt(plain.get("b.jpg"), "b.jpg", KEY)); // already encrypted: kept
        source.put("notes.txt", "Model: Anna".getBytes(UTF_8));
        source.put("sub/c.png", plain.get("sub/c.png"));
        source.put("y.cry", Cry.encrypt(plain.get("a.jpg"), "a.jpg", KEY)); // embeds a name taken on decrypt
        zip(dir.resolve("C.zip"), source);

        for (var format : List.of(Op.Format.ZIP, Op.Format.SEVEN_ZIP)) {
            var encrypted = dir.resolve(format + "-E.part");
            var names = rewrite(Kind.ZIP, dir.resolve("C.zip"), encrypted, true, format);
            assertThat(names).as(format.name()).isEqualTo(Map.of("a.jpg", "a.jpg.cry", "sub/c.png", "sub/c.png.cry"));
            try (var raw = containers.openRaw(format == Op.Format.ZIP ? Kind.ZIP : Kind.ARCHIVE, encrypted)) {
                assertThat(Cry.decrypt(raw.open("sub/c.png.cry").readAllBytes(), KEY)).isEqualTo(plain.get("sub/c.png"));
                assertThat(raw.open("b.jpg.cry").readAllBytes()).isEqualTo(source.get("b.jpg.cry"));
                assertThat(raw.items()).extracting(AlbumContainer.Item::name)
                        .containsExactlyInAnyOrder("a.jpg.cry", "b.jpg.cry", "notes.txt", "sub/c.png.cry", "y.cry");
            }

            var decrypted = dir.resolve(format + "-D.part");
            names = rewrite(format == Op.Format.ZIP ? Kind.ZIP : Kind.ARCHIVE, encrypted, decrypted, false, format);
            assertThat(names).isEqualTo(Map.of("a.jpg.cry", "a.jpg", "b.jpg.cry", "b.jpg", "sub/c.png.cry", "sub/c.png", "y.cry", "a (2).jpg"));
            var expected = new HashMap<String, Long>();
            plain.forEach((n, b) -> expected.put(n, Hashes.of(b)));
            expected.put("notes.txt", Hashes.of(source.get("notes.txt")));
            expected.put("a (2).jpg", Hashes.of(plain.get("a.jpg")));
            assertThat(packers.hashes(decrypted)).isEqualTo(expected);
        }
        try (var raw = containers.openRaw(Kind.ZIP, dir.resolve("ZIP-E.part"))) {
            assertThatThrownBy(() -> Crypt.names(raw, Cry.key("wrong"), false)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void anEmbeddedNameThatWouldLeaveItsFolderIsRefused() throws IOException {
        for (var name : List.of("D:stolen.jpg", "..", ".")) {
            var file = Cry.encrypt(new byte[100], name, KEY);
            assertThatThrownBy(() -> Crypt.name(file, file.length, KEY)).as(name).isInstanceOf(IOException.class);
        }
        var file = Cry.encrypt(new byte[100], "dir\\a.jpg", KEY);
        assertThat(Crypt.name(file, file.length, KEY)).isEqualTo("a.jpg");
    }

    @Test
    void filesAreEncryptedAndDecryptedStreamedAndReadBack() throws IOException {
        var plain = random(new SplittableRandom(7), 200_000);
        Path from = Files.write(dir.resolve("a.jpg"), plain), cry = dir.resolve("a.jpg.cry"), back = dir.resolve("back.jpg");
        long hash;
        try (var out = Files.newOutputStream(cry)) {
            hash = Crypt.write(from, "x/a.jpg", out, KEY, true);
        }
        assertThat(hash).isEqualTo(Hashes.of(plain));
        assertThat(Cry.decrypt(Files.readAllBytes(cry), KEY)).isEqualTo(plain);
        assertThat(Crypt.readBack(cry, hash, KEY, true)).as("the hash of the file as written").isEqualTo(Hashes.of(Files.readAllBytes(cry)));
        assertThatThrownBy(() -> Crypt.readBack(cry, hash + 1, KEY, true)).hasMessage("does not read back as written");
        try (var out = Files.newOutputStream(back)) {
            assertThat(Crypt.write(cry, "a.jpg.cry", out, KEY, false)).isEqualTo(hash);
        }
        assertThat(back).hasBinaryContent(plain);
        assertThat(Crypt.readBack(back, hash, KEY, false)).isEqualTo(hash);
    }

    /** One rewrite as {@code Steps} runs it: names, the view, one pack; the pack's hashes must read back. Returns the renames. */
    private Map<String, String> rewrite(Kind kind, Path src, Path target, boolean encrypt, Op.Format format) throws IOException {
        Map<String, String> names, all = new LinkedHashMap<>();
        Map<String, Long> packed;
        try (var raw = containers.openRaw(kind, src)) {
            names = Crypt.names(raw, KEY, encrypt);
            raw.items().forEach(i -> all.put(i.name(), names.getOrDefault(i.name(), i.name())));
            try (var view = Crypt.view(containers.openRaw(kind, src), names, KEY, encrypt)) {
                packed = packers.pack(view, all.values(), target, format);
            }
        }
        assertThat(packed).containsOnlyKeys(all.values());
        assertThat(packers.hashes(target)).isEqualTo(packed);
        return names;
    }

    private static byte[] random(SplittableRandom rnd, int n) {
        var b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }

    private static void zip(Path file, Map<String, byte[]> files) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
    }
}
