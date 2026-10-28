package thumbnailer.source;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import net.sf.sevenzipjbinding.ArchiveFormat;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.source.Archives.TarEntry;
import thumbnailer.spi.AlbumContainer;

class SevenZipContainerTest {

    @TempDir Path dir;

    /** Incompressible "images" larger than a head, a nested one, a long tar name, a small sidecar. */
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    {
        var rnd = new SplittableRandom(3);
        for (var n : List.of("b.jpg", "a.jpg", "raw/c.jpg", "x".repeat(120) + "/long.jpg")) {
            var b = new byte[300_000 + rnd.nextInt(1000)];
            rnd.nextBytes(b);
            files.put(n, b);
        }
        files.put("album.txt", "Model: Anna".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everyFormatListsHeadsAndReads() throws IOException {
        byte[] tar = Archives.tar(files);
        Files.write(dir.resolve("t.tar"), tar);
        Files.write(dir.resolve("t.tar.gz"), Archives.gzip(tar));
        Archives.sevenZip(dir.resolve("t.tar.xz"), ArchiveFormat.XZ, Map.of("t.tar", tar), false);
        Archives.sevenZip(dir.resolve("t.tar.bz2"), ArchiveFormat.BZIP2, Map.of("t.tar", tar), false);
        Archives.sevenZip(dir.resolve("solid.7z"), ArchiveFormat.SEVEN_ZIP, files, true);
        Archives.sevenZip(dir.resolve("plain.7z"), ArchiveFormat.SEVEN_ZIP, files, false);
        for (var name : List.of("t.tar", "t.tar.gz", "t.tar.xz", "t.tar.bz2", "solid.7z", "plain.7z")) {
            try (var c = new SevenZipContainer(dir.resolve(name), Exclusions.NONE); var arena = Arena.ofConfined()) {
                assertThat(c.items()).as(name).extracting(AlbumContainer.Item::name)
                        .containsExactly("a.jpg", "album.txt", "b.jpg", "raw/c.jpg", "x".repeat(120) + "/long.jpg");
                assertThat(c.items()).as(name).allMatch(i -> i.bytes() == files.get(i.name()).length);
                assertThat(c.head("b.jpg", 1000)).as(name).isEqualTo(slice(files.get("b.jpg"), 1000));
                assertThat(c.read("raw/c.jpg", arena).toArray(JAVA_BYTE)).as(name).isEqualTo(files.get("raw/c.jpg"));
                assertThat(c.open("album.txt").readAllBytes()).as(name).isEqualTo(files.get("album.txt"));
                var heads = new LinkedHashMap<String, byte[]>();
                c.heads(List.of("a.jpg", "b.jpg", "raw/c.jpg", "album.txt"), 4096, heads::put);
                assertThat(heads).as(name).hasSize(4);
                if (name.startsWith("t.")) assertThat(heads.keySet()).as(name + ": packing order (tar as written), not name order")
                        .containsExactly("b.jpg", "a.jpg", "raw/c.jpg", "album.txt");
                assertThat(c.sequential()).as(name).isEqualTo(!name.equals("t.tar") && !name.equals("plain.7z"));
                assertThat(c.cpuBound()).as(name + ": one compressed pass").isEqualTo(c.sequential());
                heads.forEach((n, h) -> assertThat(h).as(name + " " + n).isEqualTo(slice(files.get(n), 4096)));
                assertThatThrownBy(() -> c.head("missing.jpg", 10)).isInstanceOf(FileNotFoundException.class);
            }
        }
    }

    @Test
    void exclusionsHideEntriesAndTheSignatureIsTheArchiveStat() throws IOException {
        var file = dir.resolve("e.7z");
        Archives.sevenZip(file, ArchiveFormat.SEVEN_ZIP, files, true);
        var exclusions = Exclusions.of(List.of("*/raw/*"));
        try (var c = new SevenZipContainer(file, exclusions)) {
            String before = Stamps.signature(c);
            assertThat(c.items()).extracting(AlbumContainer.Item::name).doesNotContain("raw/c.jpg");
            assertThat(Stamps.signature(new SevenZipContainer(file, Exclusions.NONE))).as("globs count").isNotEqualTo(before);
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
            assertThat(Stamps.signature(new SevenZipContainer(file, exclusions))).as("touched").isNotEqualTo(before);
        }
        assertThat(new SevenZipContainer.Factory().accepts(Path.of("x.tar.gz"))).isTrue();
        assertThat(new SevenZipContainer.Factory().accepts(Path.of("x.zip"))).isFalse();
        assertThat(new SevenZipContainer(Path.of("Shoot 01.tar.bz2"), Exclusions.NONE).name()).isEqualTo("Shoot 01");
    }

    @Test
    void extractDeliversWholeFilesInPackingOrderInOnePassAndFailsClosed() throws IOException {
        Files.write(dir.resolve("t.tar.gz"), Archives.gzip(Archives.tar(files)));
        Archives.sevenZip(dir.resolve("solid.7z"), ArchiveFormat.SEVEN_ZIP, files, true);
        Archives.sevenZip(dir.resolve("plain.7z"), ArchiveFormat.SEVEN_ZIP, files, false);
        var asked = List.of("raw/c.jpg", "album.txt", "b.jpg", "a.jpg");
        for (var name : List.of("t.tar.gz", "solid.7z", "plain.7z")) {
            var got = new LinkedHashMap<String, byte[]>();
            var heads = new LinkedHashMap<String, byte[]>();
            try (var c = new SevenZipContainer(dir.resolve(name), Exclusions.NONE)) {
                c.extract(asked, (item, in) -> {
                    assertThat(item.bytes()).isEqualTo(files.get(item.name()).length);
                    got.put(item.name(), in.readAllBytes());
                });
                c.heads(asked, 1, heads::put);
                assertThatThrownBy(() -> c.extract(List.of("a.jpg", "missing.jpg"), (i, in) -> got.clear()))
                        .isInstanceOf(FileNotFoundException.class);
                assertThatThrownBy(() -> c.extract(asked, (i, in) -> { throw new IOException("sink full"); }))
                        .hasMessage("sink full");
            }
            assertThat(got.keySet()).as(name + ": packing order, as heads").containsExactlyElementsOf(heads.keySet());
            if (name.startsWith("t.")) assertThat(got.keySet()).containsExactly("b.jpg", "a.jpg", "raw/c.jpg", "album.txt");
            got.forEach((n, b) -> assertThat(b).as(name + " " + n).isEqualTo(files.get(n)));
        }
        for (var name : List.of("solid.7z", "plain.7z")) { // a flipped byte in the data: heads skip, extract fails
            var bytes = Files.readAllBytes(dir.resolve(name));
            for (int i = 1000; i < 1100; i++) bytes[i] ^= 0x55;
            var bad = dir.resolve("bad-" + name);
            Files.write(bad, bytes);
            try (var c = new SevenZipContainer(bad, Exclusions.NONE)) {
                assertThatThrownBy(() -> c.extract(asked, (i, in) -> {})).as(name).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void namesListedTwiceAndLinksAreListedButExtractRefusesThem() throws IOException {
        var one = "1111".getBytes(StandardCharsets.UTF_8);
        var tar = Archives.tar(List.of(new TarEntry("a.jpg", one, '0', ""), new TarEntry("d.jpg", one, '0', ""),
                new TarEntry("d.jpg", "2222".getBytes(StandardCharsets.UTF_8), '0', ""),
                new TarEntry("l.jpg", new byte[0], '2', "a.jpg"), new TarEntry("h.jpg", new byte[0], '1', "a.jpg")));
        Files.write(dir.resolve("t.tar"), tar);
        Files.write(dir.resolve("t.tar.gz"), Archives.gzip(tar)); // a wrapped tar lists its links too (TarStream), so a repack refuses them
        for (var name : List.of("t.tar", "t.tar.gz")) {
            try (var c = new SevenZipContainer(dir.resolve(name), Exclusions.NONE)) {
                assertThat(c.items()).as(name).extracting(AlbumContainer.Item::name).containsSubsequence("d.jpg", "d.jpg").contains("l.jpg", "h.jpg");
                assertThatThrownBy(() -> c.extract(List.of("a.jpg", "d.jpg"), (i, in) -> {})).as(name)
                        .isInstanceOf(IOException.class).hasMessageContaining("Duplicate entry name d.jpg");
                var read = new LinkedHashMap<String, byte[]>();
                c.extract(List.of("a.jpg"), (i, in) -> read.put(i.name(), in.readAllBytes()));
                assertThat(read).as(name).containsOnlyKeys("a.jpg");
                for (var link : List.of("l.jpg", "h.jpg"))
                    assertThatThrownBy(() -> c.extract(List.of("a.jpg", link), (i, in) -> {})).as(name)
                            .isInstanceOf(IOException.class).hasMessageContaining("Link entry " + link);
            }
        }
    }

    @Test
    void aWrappedTarWhoseStreamIsDamagedFailsExtract() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(bytes) {{ def.setLevel(Deflater.NO_COMPRESSION); }}) { // stored: the bytes stay findable
            gz.write(Archives.tar(files));
        }
        var gz = bytes.toByteArray();
        var probe = Arrays.copyOfRange(files.get("b.jpg"), 1000, 1032);
        int at = IntStream.range(0, gz.length - 32).filter(i -> Arrays.equals(gz, i, i + 32, probe, 0, 32)).findFirst().orElseThrow();
        gz[at + 16] ^= 1; // inflates fine; only the gzip CRC at the end tells
        Files.write(dir.resolve("bad.tar.gz"), gz);
        try (var c = new SevenZipContainer(dir.resolve("bad.tar.gz"), Exclusions.NONE)) {
            var names = c.items().stream().map(AlbumContainer.Item::name).toList();
            assertThat(names).hasSize(files.size());
            assertThatThrownBy(() -> c.extract(names, (i, in) -> {})).isInstanceOf(IOException.class).hasMessageContaining("Damaged archive");
        }
    }

    @Test
    void aSizeTheHeaderInventsIsNeverAllocated() throws IOException {
        var tar = new ByteArrayOutputStream();
        tar.write(Archives.header("big.jpg", 0x7FFF_FFF0L, '0', ""));
        tar.write("abcd".getBytes(StandardCharsets.UTF_8));
        tar.write(new byte[508 + 1024]); // its padding and the end: 2 GiB short of what the header says
        Files.write(dir.resolve("big.tar"), tar.toByteArray());
        Files.write(dir.resolve("big.tar.gz"), Archives.gzip(tar.toByteArray())); // no index: the header's size reaches the buffer
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (var name : List.of("big.tar", "big.tar.gz")) {
            try (var c = new SevenZipContainer(dir.resolve(name), Exclusions.NONE)) {
                assertThat(c.items()).as(name).extracting(AlbumContainer.Item::bytes).containsExactly(0x7FFF_FFF0L);
                long before = threads.getTotalThreadAllocatedBytes();
                assertThatThrownBy(() -> c.extract(List.of("big.jpg"), (i, in) -> {})).as(name).isInstanceOf(IOException.class);
                assertThat(threads.getTotalThreadAllocatedBytes() - before).as(name + ": heap bytes for 1.5 KiB read").isLessThan(64L << 20);
            }
        }
    }

    @Test
    void aMemberBeyondTheByteLimitFailsByNameBeforeItsBufferIsAllocated() throws IOException {
        long size = 600L << 20; // over the 512 MiB render limit, under the 2 GiB an array could take
        long giant = 3L << 30; // over what any array could take: refused by extract too
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (var name : List.of("huge.tar", "huge.tar.gz", "giant.tar")) {
            long bytes = name.startsWith("giant") ? giant : size;
            var tar = new ByteArrayOutputStream();
            tar.write(Archives.header("big.jpg", bytes, '0', ""));
            tar.write("abcd".getBytes(StandardCharsets.UTF_8));
            tar.write(new byte[508 + 1024]);
            Files.write(dir.resolve(name), name.endsWith(".gz") ? Archives.gzip(tar.toByteArray()) : tar.toByteArray());
            try (var c = new SevenZipContainer(dir.resolve(name), Exclusions.NONE); var arena = Arena.ofConfined()) {
                c.items();
                long before = threads.getTotalThreadAllocatedBytes();
                for (ThrowingCallable read : List.<ThrowingCallable>of(() -> c.read("big.jpg", arena), () -> c.open("big.jpg")))
                    assertThatThrownBy(read).as(name).isInstanceOf(IOException.class).hasMessage("big.jpg: " + bytes + " bytes exceeds 536870912 bytes");
                if (bytes == giant) // an operation's extract keeps the array maximum: a 600 MiB member still packs
                    assertThatThrownBy(() -> c.extract(List.of("big.jpg"), (i, in) -> {})).as(name).isInstanceOf(IOException.class)
                            .hasMessage("big.jpg: " + bytes + " bytes exceeds " + (Integer.MAX_VALUE - 8) + " bytes");
                var got = new ArrayList<String>();
                c.heads(List.of("big.jpg"), Integer.MAX_VALUE, (n, head) -> got.add(n)); // unreadable: skipped
                assertThat(got).as(name).isEmpty();
                assertThat(threads.getTotalThreadAllocatedBytes() - before).as(name + ": heap bytes").isLessThan(16L << 20);
            }
        }
    }

    private static byte[] slice(byte[] b, int n) { return java.util.Arrays.copyOf(b, Math.min(n, b.length)); }
}
