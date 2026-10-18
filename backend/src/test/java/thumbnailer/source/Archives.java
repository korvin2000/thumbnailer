package thumbnailer.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.sf.sevenzipjbinding.ArchiveFormat;
import net.sf.sevenzipjbinding.IOutCreateCallback;
import net.sf.sevenzipjbinding.IOutFeatureSetSolid;
import net.sf.sevenzipjbinding.IOutItemAllFormats;
import net.sf.sevenzipjbinding.ISequentialInStream;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.impl.OutItemFactory;
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream;
import net.sf.sevenzipjbinding.util.ByteArrayStream;
import thumbnailer.spi.AlbumContainer;

/** Test archives: GNU tar written by hand (long names as 'L' records), anything 7-Zip writes through 7-Zip-JBinding. */
public final class Archives {

    private Archives() {}

    public static byte[] tar(Map<String, byte[]> files) throws IOException {
        return tar(files.entrySet().stream().map(e -> new TarEntry(e.getKey(), e.getValue(), '0', "")).toList());
    }

    /** A tar entry: {@code type} '0' a file of {@code data}, '1' a hard or '2' a symbolic link to {@code link}. */
    public record TarEntry(String name, byte[] data, char type, String link) {}

    /** {@code entries} in order; a name may repeat. */
    public static byte[] tar(List<TarEntry> entries) throws IOException {
        var out = new ByteArrayOutputStream();
        for (var e : entries) {
            var name = e.name().getBytes(StandardCharsets.UTF_8);
            if (name.length > 100) {
                out.write(header("././@LongLink", name.length + 1, 'L', ""));
                out.write(name);
                out.write(new byte[512 - name.length % 512]);
            }
            out.write(header(e.name().length() > 100 ? e.name().substring(0, 100) : e.name(), e.data().length, e.type(), e.link()));
            out.write(e.data());
            out.write(new byte[(512 - e.data().length % 512) % 512]);
        }
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    /** An archive as the app opens it (7-Zip formats). */
    public static AlbumContainer open(Path file) { return new SevenZipContainer(file, Exclusions.NONE); }

    /** The registry with every container kind, for {@code openRaw} (no {@link Cry}: never wrapped). */
    public static Containers containers() {
        return new Containers(List.of(new DirectoryContainer.Factory(), new ZipContainer.Factory(), new SevenZipContainer.Factory()), null);
    }

    public static byte[] gzip(byte[] data) throws IOException {
        var b = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(b)) {
            out.write(data);
        }
        return b.toByteArray();
    }

    /** 7z (solid or not), xz, bzip2, gzip or tar of {@code content}, in its iteration order. */
    public static void sevenZip(Path file, ArchiveFormat format, Map<String, byte[]> content, boolean solid) throws IOException {
        SevenZipContainer.Native.init();
        var names = List.copyOf(content.keySet());
        try (var raf = new RandomAccessFile(file.toFile(), "rw"); var out = SevenZip.openOutArchive(format)) {
            if (out instanceof IOutFeatureSetSolid s) s.setSolid(solid);
            out.createArchive(new RandomAccessFileOutStream(raf), names.size(), new IOutCreateCallback<IOutItemAllFormats>() {
                @Override public void setOperationResult(boolean ok) {}
                @Override public void setTotal(long total) {}
                @Override public void setCompleted(long done) {}

                @Override
                public IOutItemAllFormats getItemInformation(int i, OutItemFactory<IOutItemAllFormats> f) {
                    var item = f.createOutItem();
                    item.setDataSize((long) content.get(names.get(i)).length);
                    item.setPropertyPath(names.get(i));
                    item.setPropertyLastModificationTime(new Date(1_700_000_000_000L));
                    return item;
                }

                @Override public ISequentialInStream getStream(int i) { return new ByteArrayStream(content.get(names.get(i)), false); }
            });
        }
    }

    /** A ustar header block claiming {@code size} bytes, whatever follows it. */
    static byte[] header(String name, long size, char type, String link) {
        var h = new byte[512];
        var n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, h, 0, Math.min(n.length, 100));
        put(h, 100, "0000644");
        put(h, 124, "%011o".formatted(size));
        put(h, 136, "%011o".formatted(1_700_000_000L));
        h[156] = (byte) type;
        put(h, 157, link);
        put(h, 257, "ustar");
        put(h, 263, "00");
        for (int i = 148; i < 156; i++) h[i] = ' ';
        int sum = 0;
        for (byte b : h) sum += b & 255;
        put(h, 148, "%06o".formatted(sum));
        return h;
    }

    private static void put(byte[] h, int at, String s) { System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, h, at, s.length()); }
}
