package thumbnailer.source;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import net.sf.sevenzipjbinding.ArchiveFormat;
import net.sf.sevenzipjbinding.ExtractAskMode;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IArchiveExtractCallback;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import org.springframework.stereotype.Component;
import thumbnailer.natives.LibraryLoader;

/**
 * Every file of an archive that 7-Zip reads (RAR, 7z, tar, ISO, and a tar inside gzip, bzip2, xz or Z) through the
 * optimized 7-Zip-JBinding 26.03 build (github.com/korvin2000/sevenzipjbinding). Opened on first use; the signature is
 * the archive's stat, taken before anything is read. Random-access archives (non-solid 7z/RAR, tar, ISO) extract per
 * file and stop 7-Zip after the bytes asked for; a solid archive serves {@link #heads} with one decompression pass;
 * a wrapped tar has no index, so listing it decompresses it once and keeps the heads of its files, while reading a
 * named file streams to it without a listing. 7-Zip may call the output streams from its own threads (multi-threaded
 * LZMA2/xz decoding), so callbacks touch no thread-confined state.
 */
final class SevenZipContainer implements AlbumContainer {

    static final Set<String> EXTENSIONS = Set.of("rar", "7z", "tar", "iso", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz", "z", "taz");
    private static final Set<ArchiveFormat> STREAMS = Set.of(ArchiveFormat.GZIP, ArchiveFormat.BZIP2, ArchiveFormat.XZ, ArchiveFormat.Z);
    private static final Pattern TARRED = Pattern.compile("(?i).*\\.(tar\\.[^.]+|tgz|tbz2?|txz|taz)");
    /** Methods that store bytes as they are (7z Copy, RAR m0): reading them costs disk time, not CPU. */
    private static final Pattern STORED = Pattern.compile("(?i)(copy|store|m0)\\b.*|.*:m0\\b.*");
    /** Head kept per wrapped-tar file, and the memory those heads may hold. */
    private static final int KEEP = 128 << 10, BUDGET = 64 << 20;

    private record Ref(int index, long size) {}

    private final Path file;
    private final String name, prefix;
    private final Exclusions exclusions;
    private String signature;
    private RandomAccessFile raf;
    private IInArchive arc;
    private boolean solid, wrapped;
    private List<Item> items;
    private final Map<String, Ref> refs = new HashMap<>(); // wrapped: index = position in the tar
    private final Map<String, byte[]> kept = new HashMap<>();
    private long keptBytes;

    SevenZipContainer(Path file, Exclusions exclusions) {
        this.file = file;
        name = file.getFileName().toString().replaceFirst("(?i)(\\.tar)?\\.[^.]+$", "");
        prefix = Containers.normalize(file) + "/";
        this.exclusions = exclusions;
    }

    @Override public String name() { return name; }

    /** Memoized: an archive replaced while it is read must not lend its new stat to the old content. */
    @Override
    public String signature() {
        if (signature == null) signature = AlbumContainer.archiveSignature(file, exclusions);
        return signature;
    }

    @Override
    public List<Item> items() {
        if (items != null) return items;
        signature();
        var list = new ArrayList<Item>();
        try {
            var a = arc();
            if (wrapped) {
                tar(new TarStream.Visitor() {
                    byte[] head;
                    int at;

                    @Override public boolean entry(String entry, long size, long mtime) {
                        if (exclusions.excludes(prefix + entry)) return false;
                        refs.put(entry, new Ref(list.size(), size));
                        list.add(new Item(entry, size, mtime));
                        int n = (int) Math.min(size, KEEP);
                        head = keptBytes + n <= BUDGET ? new byte[n] : null;
                        at = 0;
                        return head != null;
                    }

                    @Override public void data(byte[] b, int off, int len) { at += copy(b, off, len, head, at); }

                    @Override public void end() {
                        kept.put(list.getLast().name(), head);
                        keptBytes += head.length;
                    }
                }, null);
            } else {
                boolean backslashes = File.separatorChar == '\\'; // 7-Zip reports native separators
                for (int i = 0, n = a.getNumberOfItems(); i < n; i++) {
                    if (Boolean.TRUE.equals(a.getProperty(i, PropID.IS_FOLDER))) continue;
                    var path = a.getStringProperty(i, PropID.PATH);
                    if (backslashes) path = path.replace('\\', '/');
                    if (exclusions.excludes(prefix + path)) continue;
                    long size = a.getProperty(i, PropID.SIZE) instanceof Long l ? l : 0;
                    refs.put(path, new Ref(i, size));
                    list.add(new Item(path, size, a.getProperty(i, PropID.LAST_MODIFICATION_TIME) instanceof Date d ? d.getTime() : 0));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        list.sort(BY_NAME);
        return items = List.copyOf(list);
    }

    @Override public InputStream open(String entry) throws IOException { return new ByteArrayInputStream(head(entry, Integer.MAX_VALUE)); }

    @Override
    public byte[] head(String entry, int limit) throws IOException {
        var k = kept.get(entry);
        if (k != null && (k.length >= limit || k.length == refs.get(entry).size)) return k.length > limit ? Arrays.copyOf(k, limit) : k;
        var out = new Object() {
            byte[] buf;
            int at;
        };
        extract(entry, limit, n -> {
            out.buf = new byte[n];
            return (b, off, len) -> out.at += copy(b, off, len, out.buf, out.at);
        });
        return out.at == out.buf.length ? out.buf : Arrays.copyOf(out.buf, out.at);
    }

    /**
     * One copy, from 7-Zip's buffers into native memory. 7-Zip may write from its own threads, so the memory comes
     * from a shared arena, handed to the caller's arena: freed when that one closes.
     */
    @Override
    public MemorySegment read(String entry, Arena arena) throws IOException {
        var shared = Arena.ofShared();
        var out = new Object() {
            MemorySegment seg;
            long at;
        };
        try {
            extract(entry, Integer.MAX_VALUE, n -> {
                out.seg = shared.allocate(Math.max(n, 1));
                return (b, off, len) -> {
                    MemorySegment.copy(b, off, out.seg, JAVA_BYTE, out.at, len);
                    out.at += len;
                };
            });
        } catch (IOException | RuntimeException e) {
            shared.close();
            throw e;
        }
        return out.seg.asSlice(0, out.at).reinterpret(arena, s -> shared.close());
    }

    /** Known from the archive header: no listing pass for a wrapped tar. */
    @Override
    public boolean sequential() {
        try {
            arc();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return solid || wrapped;
    }

    /** A compressed archive read in one pass; tar, ISO and stored (Copy, m0) archives cost disk time instead. */
    @Override
    public boolean cpuBound() {
        if (!sequential()) return false;
        try {
            return !STORED.matcher(String.valueOf(arc.getProperty(0, PropID.METHOD))).matches();
        } catch (SevenZipException e) {
            return false;
        }
    }

    /**
     * Random-access archives read head by head, in archive order. Solid and wrapped ones deliver every head from inside
     * one decompression pass, possibly on a 7-Zip thread: such a sink must not read this container (see the
     * interface); heads up to {@link #KEEP} stay for later {@link #head} calls while the budget lasts.
     */
    @Override
    public void heads(Collection<String> names, int limit, HeadSink sink) throws IOException {
        items();
        var packed = names.stream().filter(refs::containsKey).sorted(Comparator.comparingInt(n -> refs.get(n).index)).toList();
        if (!solid && !wrapped) {
            AlbumContainer.super.heads(packed, limit, sink);
            return;
        }
        var wanted = new HashMap<Integer, String>();
        for (var n : packed) {
            var r = refs.get(n);
            var k = kept.get(n);
            if (k != null && (k.length >= limit || k.length == r.size)) sink.accept(n, k.length > limit ? Arrays.copyOf(k, limit) : k);
            else wanted.put(r.index, n);
        }
        if (wanted.isEmpty()) return;
        var pass = new Pass();
        IntFunction<Chunks> collect = i -> new Chunks() {
            final byte[] buf = new byte[(int) Math.min(limit, refs.get(wanted.get(i)).size)];
            int at;

            @Override public void accept(byte[] b, int off, int len) { at += copy(b, off, len, buf, at); }

            @Override
            public void end(boolean ok) throws SevenZipException {
                if (!ok || at < buf.length) return;
                if (buf.length <= KEEP && keptBytes + buf.length <= BUDGET && kept.putIfAbsent(wanted.get(i), buf) == null)
                    keptBytes += buf.length; // read again by the metadata extractors without another pass
                try {
                    sink.accept(wanted.get(i), buf);
                } catch (IOException e) {
                    pass.fail(e);
                }
            }
        };
        if (wrapped) {
            int last = wanted.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            tar(new TarStream.Visitor() {
                Chunks out;

                @Override public boolean entry(String entry, long size, long mtime) throws SevenZipException {
                    var r = refs.get(entry);
                    if (r != null && r.index > last) pass.stop();
                    out = r != null && wanted.containsKey(r.index) ? collect.apply(r.index) : null;
                    return out != null;
                }

                @Override public void data(byte[] b, int off, int len) throws SevenZipException { out.accept(b, off, len); }

                @Override public void end() throws SevenZipException { out.end(true); }
            }, pass);
        } else {
            run(wanted.keySet().stream().mapToInt(Integer::intValue).sorted().toArray(), collect, pass);
        }
    }

    @Override
    public void close() throws IOException {
        try (var r = raf) {
            if (arc != null) arc.close();
        }
    }

    private static int copy(byte[] b, int off, int len, byte[] to, int at) {
        int k = Math.min(len, to.length - at);
        System.arraycopy(b, off, to, at, k);
        return k;
    }

    /** One file's bytes as 7-Zip writes them; {@code end} once it is complete (ok) or failed. */
    interface Chunks {
        void accept(byte[] b, int off, int len) throws SevenZipException;

        default void end(boolean ok) throws SevenZipException {}
    }

    /**
     * One extraction: ends early through {@link #stop} (7-Zip-JBinding wraps what a callback throws, possibly in a
     * list of causes, so the flag, not the exception, says it was ours), carries a sink's failure out, and stops when
     * the thread that started it is interrupted.
     */
    private static final class Pass {
        private static final SevenZipException STOP = new SevenZipException("enough") {
            @Override public synchronized Throwable fillInStackTrace() { return this; }
        };
        final Thread owner = Thread.currentThread();
        volatile boolean stopped;
        volatile IOException failure;

        void stop() throws SevenZipException {
            stopped = true;
            throw STOP;
        }

        void fail(IOException e) throws SevenZipException {
            failure = e;
            stop();
        }

        void check() throws SevenZipException {
            if (owner.isInterrupted()) fail(new InterruptedIOException());
        }
    }

    /** Streams {@code entry}'s first {@code limit} bytes into the sink {@code open} makes for their count, then stops. */
    private void extract(String entry, int limit, IntFunction<Chunks> open) throws IOException {
        var pass = new Pass();
        var found = new boolean[1];
        IntFunction<Chunks> capped = size -> {
            int n = Math.min(limit, size);
            var out = open.apply(n);
            found[0] = true;
            return new Chunks() {
                int seen;

                @Override
                public void accept(byte[] b, int off, int len) throws SevenZipException {
                    int k = Math.min(len, n - seen);
                    out.accept(b, off, k);
                    if ((seen += k) >= n) pass.stop();
                }
            };
        };
        arc();
        if (wrapped) { // no listing needed: the tar header names the file and its size
            tar(new TarStream.Visitor() {
                Chunks out;

                @Override public boolean entry(String e, long size, long mtime) throws SevenZipException {
                    if (!e.equals(entry) || exclusions.excludes(prefix + e)) return false;
                    out = capped.apply((int) Math.min(size, Integer.MAX_VALUE));
                    if (size == 0) pass.stop();
                    return true;
                }

                @Override public void data(byte[] b, int off, int len) throws SevenZipException { out.accept(b, off, len); }

                @Override public void end() throws SevenZipException { pass.stop(); }
            }, pass);
        } else {
            items();
            var r = refs.get(entry);
            if (r == null) throw new FileNotFoundException(entry);
            if (r.size == 0 || limit == 0) {
                capped.apply(0);
                return;
            }
            run(new int[] {r.index}, i -> capped.apply((int) Math.min(r.size, Integer.MAX_VALUE)), pass);
            if (!pass.stopped) throw new IOException("Damaged or encrypted entry " + entry + " in " + file.getFileName());
        }
        if (!found[0]) throw new FileNotFoundException(entry);
    }

    /** Decompresses the wrapped tar once, or until a visitor stops the pass. */
    private void tar(TarStream.Visitor v, Pass pass) throws IOException {
        var tar = new TarStream(v);
        Chunks all = (b, off, len) -> tar.write(b);
        run(new int[] {0}, i -> all, pass == null ? new Pass() : pass);
    }

    /** One native extract over sorted {@code indices}; a stop is success, a sink failure is rethrown. */
    private void run(int[] indices, IntFunction<Chunks> target, Pass pass) throws IOException {
        var a = arc();
        try {
            a.extract(indices, false, new IArchiveExtractCallback() {
                Chunks current;

                @Override
                public ISequentialOutStream getStream(int i, ExtractAskMode mode) {
                    current = mode == ExtractAskMode.EXTRACT ? target.apply(i) : null;
                    var sink = current;
                    return sink == null ? null : data -> {
                        sink.accept(data, 0, data.length);
                        return data.length;
                    };
                }

                @Override public void prepareOperation(ExtractAskMode mode) {}

                @Override
                public void setOperationResult(ExtractOperationResult r) throws SevenZipException {
                    var c = current;
                    current = null;
                    if (c != null) c.end(r == ExtractOperationResult.OK);
                }

                @Override public void setTotal(long total) {}

                @Override public void setCompleted(long done) throws SevenZipException { pass.check(); }
            });
        } catch (SevenZipException e) {
            if (pass.failure != null) throw pass.failure;
            if (!pass.stopped) throw new IOException("Cannot extract from " + file.getFileName() + ": " + e.getMessage(), e);
        }
        if (pass.failure != null) throw pass.failure;
    }

    private IInArchive arc() throws IOException {
        if (arc == null) {
            signature();
            Native.init();
            raf = new RandomAccessFile(file.toFile(), "r");
            arc = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf));
            solid = Boolean.TRUE.equals(arc.getArchiveProperty(PropID.SOLID));
            var inner = String.valueOf(arc.getStringProperty(0, PropID.PATH)).toLowerCase(Locale.ROOT);
            wrapped = STREAMS.contains(arc.getArchiveFormat()) && arc.getNumberOfItems() == 1 // inner name, else ours
                    && (inner.endsWith(".tar") || TARRED.matcher(file.getFileName().toString()).matches());
        }
        return arc;
    }

    /** Loads the native library once through {@link LibraryLoader} and hands it to 7-Zip-JBinding. */
    static final class Native {
        static {
            LibraryLoader.load("7-Zip-JBinding");
            try {
                SevenZip.initLoadedLibraries();
            } catch (Exception e) {
                throw new IllegalStateException("7-Zip-JBinding did not initialize", e);
            }
        }

        static void init() {}
    }

    @Component
    static final class Factory implements ContainerFactory {
        @Override public Kind kind() { return Kind.ARCHIVE; }
        @Override public boolean accepts(Path file) { return EXTENSIONS.contains(AlbumContainer.extension(file.getFileName().toString())); }
        @Override public AlbumContainer open(Path path, Exclusions exclusions) { return new SevenZipContainer(path, exclusions); }
    }
}
