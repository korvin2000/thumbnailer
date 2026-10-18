package thumbnailer.ops;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import com.dynatrace.hash4j.hashing.Hashing;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The file steps of operations, never following a link: copies that hash what they read, read-back hashes, forced
 * writes, deletes and renames that wait for another process to let go of a file (5 × 200 ms), and the naming rules.
 * Hash maps are keyed by the path relative to the tree, {@code ""} for a single file.
 */
final class Trees {

    static final long MARGIN = 64L << 20; // free space kept on every volume

    private Trees() {}

    interface Visit { void file(String name, Path file, BasicFileAttributes a) throws IOException; }

    /** Every regular file below {@code root} (or {@code root} itself); a link or other special file fails. */
    static void walk(Path root, Visit visit) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (!a.isRegularFile()) throw new IOException("A link inside: " + f);
                visit.file(root.relativize(f).toString().replace('\\', '/'), f, a);
                return CONTINUE;
            }
        });
    }

    /** Copies file or directory {@code src} to {@code dst} (which must not exist) with its mtimes; returns what it read. */
    static Map<String, Long> copy(Path src, Path dst) throws IOException {
        var hashes = new TreeMap<String, Long>();
        if (Files.isDirectory(src, NOFOLLOW_LINKS)) try (var dirs = Files.walk(src)) { // empty folders too
            for (var d : (Iterable<Path>) dirs.filter(d -> Files.isDirectory(d, NOFOLLOW_LINKS))::iterator)
                Files.createDirectories(dst.resolve(src.relativize(d).toString()));
        }
        walk(src, (name, f, a) -> {
            var to = dst.resolve(name);
            try (var in = Files.newInputStream(f); var out = Files.newOutputStream(to, CREATE_NEW)) {
                hashes.put(name, Hashes.copy(in, out));
            }
            Files.setLastModifiedTime(to, a.lastModifiedTime());
        });
        return hashes;
    }

    /** The hashes of every file below {@code root}, read back. */
    static Map<String, Long> hashes(Path root) throws IOException {
        var hashes = new TreeMap<String, Long>();
        walk(root, (name, f, a) -> {
            try (var in = Files.newInputStream(f)) {
                hashes.put(name, Hashes.of(in));
            }
        });
        return hashes;
    }

    /** Forces every file below {@code root} to the disk: a source is deleted only after its copy is durable. */
    static void sync(Path root) throws IOException {
        walk(root, (name, f, a) -> {
            try (var ch = FileChannel.open(f, WRITE)) {
                ch.force(true);
            }
        });
    }

    /**
     * The names, sizes and mtimes of the files below {@code root} (only {@code names} when given), hashed: a source is
     * deleted only while it still reads as when its copy began.
     */
    static long stamp(Path root, Collection<String> names) throws IOException {
        var files = new TreeMap<String, BasicFileAttributes>();
        if (names == null) walk(root, (name, f, a) -> files.put(name, a));
        else for (var n : names) files.put(n, Files.readAttributes(root.resolve(n), BasicFileAttributes.class, NOFOLLOW_LINKS));
        var h = Hashing.wyhashFinal4().hashStream();
        files.forEach((n, a) -> h.putString(n).putLong(a.size()).putLong(a.lastModifiedTime().toMillis()));
        return h.getAsLong();
    }

    static long size(Path root) throws IOException {
        long[] size = {0};
        walk(root, (name, f, a) -> size[0] += a.size());
        return size[0];
    }

    /** Deletes a file or a whole tree; links inside are deleted, never followed. */
    static void delete(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                retry(() -> Files.deleteIfExists(f));
                return CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                retry(() -> Files.deleteIfExists(d));
                return CONTINUE;
            }
        });
    }

    /**
     * Renames {@code from} to {@code to} on one volume, refusing an existing {@code to} (an atomic move would replace it
     * silently); a case-only rename goes through {@link #part} (a direct one is a no-op on Windows).
     */
    static void move(Path from, Path to) throws IOException {
        if (present(to)) throw new FileAlreadyExistsException(to.toString());
        if (from.toString().equalsIgnoreCase(to.toString())) {
            var via = part(to);
            retry(() -> Files.move(from, via, ATOMIC_MOVE));
            retry(() -> Files.move(via, to, ATOMIC_MOVE));
        } else retry(() -> Files.move(from, to, ATOMIC_MOVE));
    }

    /** Whether {@code p} exists under exactly this name (case included: after a case-only rename the old one is absent). */
    static boolean present(Path p) throws IOException {
        return Files.exists(p, NOFOLLOW_LINKS)
                && p.toRealPath(NOFOLLOW_LINKS).getFileName().toString().equals(p.getFileName().toString());
    }

    /** Where a result is written before it is verified and renamed: a sibling ending in {@code .part}. */
    static Path part(Path p) { return p.resolveSibling(p.getFileName() + ".part"); }

    static String part(String canonical) { return canonical + ".part"; }

    /** What a 7z pack into {@code tmp} spilled beside it: {@code <tmp>.<n>.spill}. */
    static List<Path> spills(Path tmp) throws IOException {
        var prefix = tmp.getFileName() + ".";
        var out = new ArrayList<Path>();
        try (var all = Files.newDirectoryStream(tmp.getParent(), f -> // no glob: names hold [ ]
                f.getFileName().toString().startsWith(prefix) && f.getFileName().toString().endsWith(".spill"))) {
            all.forEach(out::add);
        }
        return out;
    }

    interface Io { void run() throws IOException; }

    /** Runs a rename or delete again while another process holds the file: 5 more tries 200 ms apart. */
    static void retry(Io io) throws IOException {
        for (int i = 0; ; i++) {
            try {
                io.run();
                return;
            } catch (FileSystemException e) {
                if (i == 5 || !inUse(e)) throw e;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted");
                }
            }
        }
    }

    /** Windows reports a file another process holds as a bare {@link FileSystemException} or as access denied. */
    static boolean inUse(Throwable e) { return e.getClass() == FileSystemException.class || e instanceof AccessDeniedException; }

    /** The reason a failure gives the user. */
    static String reason(Throwable e) {
        if (inUse(e)) return "in use";
        if (e instanceof FileAlreadyExistsException) return "exists";
        if (e instanceof NoSuchFileException) return "gone: " + e.getMessage();
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** {@code name (n).ext}: the {@code n}-th free name, the extension (two for {@code .tar.gz}) kept; folders have none. */
    static String numbered(String name, int n, boolean file) {
        int dot = file ? extension(name) : name.length();
        return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }

    /** An archive's name without its extension(s): {@code X.tar.gz}, {@code X.zip} → {@code X}. */
    static String stem(String name) { return name.substring(0, extension(name)); }

    private static int extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name.length();
        int tar = name.lastIndexOf('.', dot - 1);
        return tar > 0 && name.substring(tar, dot).equalsIgnoreCase(".tar") ? tar : dot;
    }

    /** An archive entry name that stays inside the folder it is extracted to. */
    static boolean safe(String name) {
        return !name.isEmpty() && name.charAt(0) != '/' && name.charAt(0) != '\\' && name.indexOf(':') < 0
                && java.util.Arrays.stream(name.split("[/\\\\]")).noneMatch(".."::equals);
    }
}
