package thumbnailer.ops;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import com.dynatrace.hash4j.hashing.Hashing;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
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
import java.util.Objects;
import java.util.TreeMap;

/**
 * The file steps of operations, never following a link: copies that hash what they read, read-back hashes, forced
 * writes, deletes and renames that wait for another process to let go of a file (5 × 200 ms), and the naming rules.
 * Hash maps are keyed by the path relative to the tree, {@code ""} for a single file.
 */
final class Trees {

    static final long MARGIN = 64L << 20; // free space kept on every volume
    private static final boolean WINDOWS = File.separatorChar == '\\';

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
        if (Files.isDirectory(src, NOFOLLOW_LINKS)) {
            Files.createDirectory(dst); // an existing directory .part is someone else's: never adopted
            try (var dirs = Files.walk(src)) { // empty folders too
                for (var d : (Iterable<Path>) dirs.filter(d -> Files.isDirectory(d, NOFOLLOW_LINKS))::iterator)
                    Files.createDirectories(dst.resolve(src.relativize(d).toString()));
            }
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
     * Each file below {@code root} (only those of {@code names} when given) that exists, name → its size and mtime hashed;
     * none for a missing root: a source file is deleted only while it still reads as when its copy began.
     */
    static Map<String, Long> stamps(Path root, Collection<String> names) throws IOException {
        var stamps = new TreeMap<String, Long>();
        Visit put = (n, f, a) -> {
            var t = a.lastModifiedTime().toInstant();
            stamps.put(n, Hashing.wyhashFinal4().hashStream().putLong(a.size()).putLong(t.getEpochSecond()).putInt(t.getNano()).getAsLong());
        };
        if (names == null) {
            if (Files.exists(root, NOFOLLOW_LINKS)) walk(root, put);
        } else for (var n : names) {
            var f = root.resolve(n);
            if (Files.exists(f, NOFOLLOW_LINKS)) put.file(n, f, Files.readAttributes(f, BasicFileAttributes.class, NOFOLLOW_LINKS));
        }
        return stamps;
    }

    static long size(Path root) throws IOException {
        long[] size = {0};
        walk(root, (name, f, a) -> size[0] += a.size());
        return size[0];
    }

    /** Deletes a file or a whole tree; links inside are deleted, never followed. */
    static void delete(Path root) throws IOException { delete(root, true); }

    /** Deletes the folders of a tree bottom-up, each once empty: one still holding a file fails ({@link java.nio.file.DirectoryNotEmptyException}). */
    static void prune(Path root) throws IOException { delete(root, false); }

    private static void delete(Path root, boolean files) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (files) retry(() -> Files.deleteIfExists(f));
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
     * Renames {@code from} to {@code to} on one volume, never over an existing {@code to}, not even one spelled in another
     * case (on a case-insensitive volume the same file); a case-only rename of one file goes through {@link #part} (a
     * direct one is a no-op on Windows). Another volume → {@link AtomicMoveNotSupportedException}.
     */
    static void move(Path from, Path to) throws IOException {
        if (taken(from, to)) throw new FileAlreadyExistsException(to.toString());
        if (from.toString().equalsIgnoreCase(to.toString())) {
            var via = part(to);
            rename(from, via);
            rename(via, to);
        } else rename(from, to);
    }

    /**
     * Whether {@code to} is occupied by anything but {@code from} itself, in any case where the volume ignores case; a link
     * there is an occupant, whatever it points at.
     */
    static boolean taken(Path from, Path to) throws IOException {
        return Files.exists(to, NOFOLLOW_LINKS) && (Files.isSymbolicLink(to) || !Files.isSameFile(from, to));
    }

    /**
     * One rename that never replaces. Windows: {@code Files.move} without options is MoveFileEx without
     * MOVEFILE_REPLACE_EXISTING, failing on an existing target (it would copy across volumes: refused first). Elsewhere a
     * rename replaces: a file is hard-linked to its new name, then unlinked (link(2) fails on an existing name); a directory,
     * or a file where links fail (FAT, another device), is renamed after a look. Residual window between look and rename:
     * an empty directory, or a file on a volume without hard links, created there in between is replaced.
     */
    private static void rename(Path from, Path to) throws IOException {
        if (WINDOWS) {
            if (!from.getParent().equals(to.getParent()) && !Files.getFileStore(from).equals(Files.getFileStore(to.getParent())))
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "another volume");
            retry(() -> Files.move(from, to));
            if (Files.exists(from, NOFOLLOW_LINKS)) throw new IOException(from + " is still there after its move: " + to + " is a hard link of it");
        } else if (!Files.isDirectory(from, NOFOLLOW_LINKS) && link(from, to)) {
            if (!same(from, to)) throw new IOException(from + " was replaced while it was being moved: both stay"); // the link holds what was read
            retry(() -> Files.delete(from));
        } else {
            if (Files.exists(to, NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(to.toString());
            retry(() -> Files.move(from, to, ATOMIC_MOVE));
        }
    }

    /** Makes {@code to} a hard link of file {@code from} (or finds it one, linked before a crash); false where links fail. */
    private static boolean link(Path from, Path to) throws IOException {
        try {
            Files.createLink(to, from);
        } catch (FileAlreadyExistsException e) {
            if (!same(from, to)) throw e;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
        return true;
    }

    /** The same inode under both names (links not followed: a symlink is its own file). */
    private static boolean same(Path a, Path b) throws IOException {
        return Objects.equals(Files.readAttributes(a, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey(),
                Files.readAttributes(b, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey());
    }

    /**
     * Whether {@code p} exists under exactly this name (case included: after a case-only rename the old one is absent); for
     * recognizing an item's own names only, never whether a target is free ({@link #taken}).
     */
    static boolean present(Path p) throws IOException {
        return Files.exists(p, NOFOLLOW_LINKS)
                && p.toRealPath(NOFOLLOW_LINKS).getFileName().toString().equals(p.getFileName().toString());
    }

    /** Where a result is written before it is verified and renamed: a sibling ending in {@code .part}. */
    static Path part(Path p) { return p.resolveSibling(p.getFileName() + ".part"); }

    static String part(String canonical) { return canonical + ".part"; }

    /** What a pack into {@code tmp} spilled beside it: {@code <tmp>.<n>.spill} (both packers). */
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
