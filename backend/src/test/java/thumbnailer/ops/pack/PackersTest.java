package thumbnailer.ops.pack;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
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
import thumbnailer.source.Kind;

class PackersTest {

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
    void theNameMapRenamesAndTheHashesAreOfTheBytesReadByWrittenName() throws IOException {
        var files = Map.of("x/a.jpg", "a".getBytes(UTF_8), "X/sub/b.jpg", "b".getBytes(UTF_8), "X/notes.txt", "n".getBytes(UTF_8));
        zip(dir.resolve("X.zip"), files);
        var out = dir.resolve("X.part"); // created by the unpacker
        try (var raw = containers.openRaw(Kind.ZIP, dir.resolve("X.zip"))) {
            assertThat(unpacker.unpack(raw, out, Map.of("x/a.jpg", "a.jpg", "X/sub/b.jpg", "sub/b.jpg", "X/notes.txt", "notes.txt")))
                    .isEqualTo(Map.of("a.jpg", hash(files.get("x/a.jpg")), "sub/b.jpg", hash(files.get("X/sub/b.jpg")),
                            "notes.txt", hash(files.get("X/notes.txt"))));
        }
        assertThat(out.resolve("sub/b.jpg")).hasContent("b");
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
