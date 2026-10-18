package thumbnailer.ops.pack;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.sf.sevenzipjbinding.ArchiveFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.ops.Hashes;
import thumbnailer.ops.Op;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Archives;
import thumbnailer.source.Containers;
import thumbnailer.source.Cry;
import thumbnailer.source.Kind;

class PackersTest {

    static final byte[] KEY = Cry.key("test");

    @TempDir Path dir;

    final Containers containers = Archives.containers();
    final Packers packers = new Packers(containers);
    final FolderUnpacker unpacker = new FolderUnpacker();

    @Test
    void folderToZipToFolder_imagesStoredTextDeflated_hashesAgree() throws IOException {
        var src = dir.resolve("Shoot");
        var files = album(src);
        var zip = dir.resolve("Shoot.zip.part");
        assertThat(pack(src, zip, Op.Format.AUTO)).isEqualTo(hashes(files)).isEqualTo(packers.hashes(zip));
        try (var z = new ZipFile(zip.toFile())) {
            for (var n : files.keySet()) {
                var e = z.getEntry(n);
                assertThat(e.getMethod()).as(n).isEqualTo(n.endsWith(".txt") ? ZipEntry.DEFLATED : ZipEntry.STORED);
                assertThat(e.getTime()).as(n).isCloseTo(mtime(src.resolve(n)), within(2000L));
            }
            assertThat(z.getEntry("notes.txt").getCompressedSize()).isLessThan(files.get("notes.txt").length / 10);
        }
        unpackRoundTrip(Files.move(zip, dir.resolve("Shoot.zip")), Kind.ZIP, src, files);
    }

    @Test
    void folderToSevenZipToFolder_copyMethodNotSolid() throws IOException {
        var src = dir.resolve("Shoot");
        var files = album(src);
        var file = dir.resolve("Shoot.7z.part");
        assertThat(pack(src, file, Op.Format.SEVEN_ZIP)).isEqualTo(hashes(files)).isEqualTo(packers.hashes(file));
        var archive = Files.move(file, dir.resolve("Shoot.7z"));
        try (var c = Archives.open(archive)) {
            assertThat(c.sequential()).as("not solid").isFalse();
            assertThat(c.items()).allMatch(i -> Math.abs(i.mtime() - mtime(src.resolve(i.name()))) < 2000);
        }
        try (var raf = new java.io.RandomAccessFile(archive.toFile(), "r");
             var in = net.sf.sevenzipjbinding.SevenZip.openInArchive(null, new net.sf.sevenzipjbinding.impl.RandomAccessFileInStream(raf))) {
            for (int i = 0; i < in.getNumberOfItems(); i++)
                assertThat(in.getProperty(i, net.sf.sevenzipjbinding.PropID.METHOD)).as("stored as it is").isEqualTo("Copy");
        }
        unpackRoundTrip(archive, Kind.ARCHIVE, src, files);
        assertThat(spills()).isEmpty();
    }

    @Test
    void sevenZipAsksForEntriesInTheOrderThePassFollows() throws IOException {
        var names = new ArrayList<>(List.of("b.jpg", "A.jpg", "a_b.jpg", "a.b.jpg", "a b.jpg", "Ä.jpg", "a10.jpg", "a2.jpg",
                "sub/x.jpg", "Sub2/a.jpg", "sub-1.jpg", "~x.jpg", "Z.jpg"));
        Collections.shuffle(names, new java.util.Random(1));
        var content = new LinkedHashMap<String, byte[]>();
        names.forEach(n -> content.put(n, n.getBytes(UTF_8)));
        var file = dir.resolve("order.7z");
        Archives.sevenZip(file, ArchiveFormat.SEVEN_ZIP, content, false); // packing order = the order 7-Zip asked in
        var packed = new ArrayList<String>();
        try (var c = Archives.open(file)) {
            c.heads(names, 1, (n, h) -> packed.add(n));
        }
        assertThat(packed).containsExactlyElementsOf(names.stream().sorted(SevenZipPacker.ORDER).toList());
    }

    @Test
    void repacksStreamFromEveryKindOfSource_spillingWhatComesEarly() throws IOException {
        var files = new LinkedHashMap<String, byte[]>(); // a tar in reverse name order: 7-Zip asks the other way round
        var rnd = new SplittableRandom(9);
        for (var n : List.of("z.jpg", "m/y.jpg", "c.jpg", "b.txt", "a.jpg")) files.put(n, random(rnd, 70_000 + n.length()));
        Files.write(dir.resolve("t.tar.gz"), Archives.gzip(Archives.tar(files)));
        Archives.sevenZip(dir.resolve("solid.7z"), ArchiveFormat.SEVEN_ZIP, files, true);
        zip(dir.resolve("plain.zip"), files);
        for (var source : List.of("t.tar.gz", "solid.7z", "plain.zip")) {
            for (var format : List.of(Op.Format.SEVEN_ZIP, Op.Format.ZIP)) {
                var target = dir.resolve(source + "." + format + ".part");
                Map<String, Long> packed;
                try (var raw = containers.openRaw(source.endsWith(".zip") ? Kind.ZIP : Kind.ARCHIVE, dir.resolve(source))) {
                    packed = packers.pack(raw, names(raw), target, format);
                }
                assertThat(packed).as(source + " → " + format).isEqualTo(hashes(files)).isEqualTo(packers.hashes(target));
            }
        }
        assertThat(spills()).isEmpty();
    }

    @Test
    void theVendoredNativeWritesZipToo() throws IOException {
        var files = Map.of("a.jpg", random(new SplittableRandom(2), 10_000), "dir/b.txt", "text".getBytes(UTF_8));
        var file = dir.resolve("native.zip");
        Archives.sevenZip(file, ArchiveFormat.ZIP, files, false);
        assertThat(packers.hashes(file)).isEqualTo(hashes(files));
    }

    @Test
    void anArchiveHoldingOnlyAFolderOfItsOwnNameIsFlattened() throws IOException {
        var files = Map.of("x/a.jpg", "a".getBytes(UTF_8), "X/sub/b.jpg", "b".getBytes(UTF_8), "X/notes.txt", "n".getBytes(UTF_8));
        zip(dir.resolve("X.zip"), files);
        var out = Files.createDirectory(dir.resolve("X.part"));
        try (var raw = containers.openRaw(Kind.ZIP, dir.resolve("X.zip"))) {
            var map = FolderUnpacker.flatten(raw);
            assertThat(map).containsExactlyInAnyOrderEntriesOf(Map.of("x/a.jpg", "a.jpg", "X/sub/b.jpg", "sub/b.jpg", "X/notes.txt", "notes.txt"));
            assertThat(unpacker.unpack(raw, out, map)).containsOnlyKeys("a.jpg", "sub/b.jpg", "notes.txt");
        }
        assertThat(out.resolve("sub/b.jpg")).hasContent("b");
        zip(dir.resolve("Y.zip"), Map.of("Y/a.jpg", new byte[1], "top.jpg", new byte[1]));
        zip(dir.resolve("Z.zip"), Map.of("Y/a.jpg", new byte[1]));
        for (var n : List.of("Y.zip", "Z.zip")) {
            try (var raw = containers.openRaw(Kind.ZIP, dir.resolve(n))) {
                assertThat(FolderUnpacker.flatten(raw)).as(n).isEmpty();
            }
        }
    }

    @Test
    void entryNamesThatLeaveTheFolderAreRefusedBeforeAnythingIsWritten() throws IOException {
        var out = Files.createDirectory(dir.resolve("out"));
        for (var n : List.of("../a.jpg", "a/../../b.jpg", "a/..", "/abs.jpg", "\\\\server\\x.jpg", "C:/x.jpg", "c:x.jpg", "a:b.jpg", "", "a\u0001.jpg"))
            assertThatThrownBy(() -> FolderUnpacker.contained(out, n)).as(n).isInstanceOf(IOException.class);
        assertThat(FolderUnpacker.contained(out, "a/./b.jpg")).isEqualTo(out.resolve("a/b.jpg"));
        assertThat(FolderUnpacker.contained(out, "sub\\c.jpg")).isEqualTo(out.resolve("sub/c.jpg"));
        zip(dir.resolve("evil.zip"), Map.of("good.jpg", new byte[1], "../evil.jpg", new byte[1]));
        try (var raw = containers.openRaw(Kind.ZIP, dir.resolve("evil.zip"))) {
            assertThatThrownBy(() -> unpacker.unpack(raw, out, Map.of())).isInstanceOf(IOException.class).hasMessageContaining("evil");
        }
        assertThat(out).isEmptyDirectory();
        assertThat(dir.resolve("evil.jpg")).doesNotExist();
    }

    @Test
    void archivesAreRewrittenEncryptedThenDecrypted_plaintextHashesVerifyBothWays() throws IOException {
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

        var encrypted = dir.resolve("C.zip.part");
        CryptRewriter.Result r;
        try (var raw = containers.openRaw(Kind.ZIP, dir.resolve("C.zip"))) {
            r = CryptRewriter.rewrite(raw, encrypted, true, KEY);
        }
        assertThat(r.nameMap()).isEqualTo(Map.of("a.jpg", "a.jpg.cry", "sub/c.png", "sub/c.png.cry"));
        assertThat(r.kept()).containsOnlyKeys("b.jpg.cry", "notes.txt", "y.cry");
        assertThat(r.plain()).isEqualTo(Map.of("a.jpg.cry", hash(plain.get("a.jpg")), "sub/c.png.cry", hash(plain.get("sub/c.png"))));
        CryptRewriter.verify(encrypted, r, KEY);
        try (var z = new ZipFile(encrypted.toFile())) {
            var cry = z.getInputStream(z.getEntry("sub/c.png.cry")).readAllBytes();
            var back = new ByteArrayOutputStream();
            assertThat(Cry.decrypt(new ByteArrayInputStream(cry), cry.length, KEY, back)).isEqualTo("c.png");
            assertThat(back.toByteArray()).isEqualTo(plain.get("sub/c.png"));
            assertThat(z.getInputStream(z.getEntry("b.jpg.cry")).readAllBytes()).isEqualTo(source.get("b.jpg.cry"));
        }
        var tampered = new CryptRewriter.Result(true, r.nameMap(), Map.of("a.jpg.cry", 1L, "sub/c.png.cry", 2L), r.kept());
        assertThatThrownBy(() -> CryptRewriter.verify(encrypted, tampered, KEY)).isInstanceOf(IOException.class);

        var decrypted = dir.resolve("D.zip.part");
        try (var raw = containers.openRaw(Kind.ZIP, encrypted)) {
            r = CryptRewriter.rewrite(raw, decrypted, false, KEY);
        }
        assertThat(r.nameMap()).isEqualTo(Map.of("a.jpg.cry", "a.jpg", "b.jpg.cry", "b.jpg", "sub/c.png.cry", "sub/c.png", "y.cry", "a (2).jpg"));
        assertThat(r.kept()).containsOnlyKeys("notes.txt");
        CryptRewriter.verify(decrypted, r, KEY);
        var expected = new HashMap<>(Map.of("notes.txt", source.get("notes.txt"), "a (2).jpg", plain.get("a.jpg")));
        expected.putAll(plain);
        assertThat(packers.hashes(decrypted)).isEqualTo(hashes(expected));
        try (var raw = containers.openRaw(Kind.ZIP, encrypted)) {
            assertThatThrownBy(() -> CryptRewriter.rewrite(raw, dir.resolve("E.zip.part"), false, Cry.key("wrong")))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void copiedPhotosSkipIdenticalFilesAndNumberOthers() throws IOException {
        assertThat(PhotoNames.numbered("a.jpg", 1)).isEqualTo("a.jpg");
        assertThat(PhotoNames.numbered("a.jpg", 2)).isEqualTo("a (2).jpg");
        assertThat(PhotoNames.numbered("README", 3)).isEqualTo("README (3)");
        assertThat(PhotoNames.numbered(".hidden", 2)).isEqualTo(".hidden (2)");
        assertThat(PhotoNames.numbered("d.x/file", 2)).isEqualTo("d.x/file (2)");
        assertThat(PhotoNames.numbered("a.jpg.cry", 2)).isEqualTo("a.jpg (2).cry");
        byte[] x = {1, 2, 3}, y = {4, 5, 6}, z = {7};
        assertThat(PhotoNames.target(dir, "a.jpg", 3, hash(x))).contains(dir.resolve("a.jpg"));
        Files.write(dir.resolve("a.jpg"), x);
        assertThat(PhotoNames.target(dir, "a.jpg", 3, hash(x))).as("the same photo").isEmpty();
        assertThat(PhotoNames.target(dir, "a.jpg", 3, hash(y))).contains(dir.resolve("a (2).jpg"));
        Files.write(dir.resolve("a (2).jpg"), y);
        assertThat(PhotoNames.target(dir, "a.jpg", 3, hash(y))).as("copied before").isEmpty();
        assertThat(PhotoNames.target(dir, "a.jpg", 1, hash(z))).contains(dir.resolve("a (3).jpg"));
    }

    /** An album folder: incompressible images (mixed case, a space, a non-ASCII name), a text sidecar, distinct mtimes. */
    private static Map<String, byte[]> album(Path folder) throws IOException {
        var files = new LinkedHashMap<String, byte[]>();
        var rnd = new SplittableRandom(5);
        for (var n : List.of("b.jpg", "A.jpg", "c 10.PNG", "c_2.jpg", "Ä.webp")) files.put(n, random(rnd, 50_000 + n.length()));
        files.put("notes.txt", "Model: Anna\n".repeat(500).getBytes(UTF_8));
        Files.createDirectories(folder);
        long t = 1_700_000_000_000L;
        for (var e : files.entrySet()) {
            var p = Files.write(folder.resolve(e.getKey()), e.getValue());
            Files.setLastModifiedTime(p, FileTime.fromMillis(t += 61_001));
        }
        return files;
    }

    private Map<String, Long> pack(Path folder, Path target, Op.Format format) throws IOException {
        try (var raw = containers.openRaw(Kind.DIRECTORY, folder)) {
            return packers.pack(raw, names(raw), target, format);
        }
    }

    private void unpackRoundTrip(Path archive, Kind kind, Path src, Map<String, byte[]> files) throws IOException {
        var out = Files.createDirectory(dir.resolve(archive.getFileName() + ".part"));
        try (var raw = containers.openRaw(kind, archive)) {
            assertThat(FolderUnpacker.flatten(raw)).isEmpty();
            assertThat(unpacker.unpack(raw, out, Map.of())).isEqualTo(hashes(files));
        }
        try (Stream<Path> list = Files.list(out)) {
            assertThat(list).hasSize(files.size());
        }
        for (var e : files.entrySet()) {
            assertThat(out.resolve(e.getKey())).hasBinaryContent(e.getValue());
            assertThat(mtime(out.resolve(e.getKey()))).as(e.getKey()).isCloseTo(mtime(src.resolve(e.getKey())), within(2000L));
        }
    }

    private List<Path> spills() throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.toString().endsWith(".spill")).toList();
        }
    }

    private static List<String> names(AlbumContainer c) { return c.items().stream().map(AlbumContainer.Item::name).toList(); }

    private static Map<String, Long> hashes(Map<String, byte[]> files) {
        var m = new HashMap<String, Long>();
        files.forEach((n, b) -> m.put(n, hash(b)));
        return m;
    }

    private static long hash(byte[] b) {
        try {
            return Hashes.of(new ByteArrayInputStream(b));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
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
