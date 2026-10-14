package thumbnailer.source;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which physical disk a root lives on and how it is read. A disk that pays for seeks serves its readers one by one,
 * first come first served ({@code hdd-readers} at once), so one album is read front to back while others decode; an SSD
 * is not gated. Anything not known to be an SSD (network shares, USB bridges that do not say, virtual drives, a failed
 * query) counts as a disk that pays for seeks. Scans and HTTP renders queue separately, so browsing never waits for
 * the scan's queue, and on such a disk the scan gives way: it reads only after the UI has not read for {@link #QUIET}.
 * Detected once per reachable physical root; a root's {@link Storage} overrides the verdict.
 */
@Component
public class Disks {

    private static final Logger log = LoggerFactory.getLogger(Disks.class);
    /** How long the UI must leave a disk alone before a scan reads it again: a burst of thumbnail requests stays whole. */
    static final long QUIET = 300_000_000L;
    private static final Pattern REMOTE = Pattern.compile("(?i)nfs\\d*|cifs|smb\\w*|afpfs|webdav|davfs|9p|ceph|glusterfs|sshfs|fuse(\\..*)?");

    /** How a root's disk is read: detected, or forced. */
    public enum Storage { AUTO, SSD, HDD }

    /** {@code key} names the physical disk where known (two partitions share it), else the volume. */
    public record Disk(String key, boolean ssd) {}

    /** Who reads: a scan, or a render for the UI. */
    public enum Use { SCAN, VIEW }

    private final int hddReaders;
    private final Map<String, Disk> detected = new ConcurrentHashMap<>(); // physical root -> disk
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();

    public Disks(@Value("${thumbnailer.hdd-readers:1}") int hddReaders) { this.hddReaders = Math.max(1, hddReaders); }

    public Disk disk(Path physicalRoot, Storage storage) {
        var key = physicalRoot.toAbsolutePath().normalize().toString();
        var d = detected.get(key);
        if (d == null) {
            d = detect(physicalRoot);
            if (Files.isDirectory(physicalRoot)) { // an unplugged disk is asked again later
                detected.put(key, d);
                log.info("Storage of {}: {} ({})", physicalRoot, d.ssd() ? "SSD" : "HDD", d.key());
            }
        }
        return storage == null || storage == Storage.AUTO ? d : new Disk(d.key(), storage == Storage.SSD);
    }

    /** On a disk that pays for seeks, a scan's lane gives way to the UI's lane of the same disk. */
    public Lane lane(Disk disk, Use use) {
        var first = use == Use.SCAN && !disk.ssd() ? lane(disk, Use.VIEW) : null;
        return lanes.computeIfAbsent(disk.key() + '|' + disk.ssd() + '|' + use, k -> new Lane(disk.ssd() ? 0 : hddReaders, first));
    }

    /** A disk's turn: {@code permits} readers at once in arrival order, or any number when 0. */
    public static final class Lane {
        private final Semaphore gate;
        private final Lane first; // whose readers go first: the UI's lane, for a scan's lane on an HDD
        private int readers; // holding or waiting (guarded by this)
        private long quietSince = System.nanoTime() - QUIET;

        Lane(int permits, Lane first) {
            gate = permits > 0 ? new Semaphore(permits, true) : null;
            this.first = first;
        }

        /** Waits for a turn; never hold one while entering another lane or before a render slot (lane → slot). */
        public Hold enter() throws InterruptedIOException {
            if (gate == null) return Hold.NONE;
            giveWay();
            synchronized (this) { readers++; }
            try {
                gate.acquire();
            } catch (InterruptedException e) {
                left();
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("shutting down");
            }
            var held = new AtomicBoolean(true);
            return () -> {
                if (held.getAndSet(false)) {
                    gate.release();
                    left();
                }
            };
        }

        /** Between a scan's reads: returns once the UI has left the disk alone for {@link #QUIET}; at once elsewhere. */
        public void giveWay() throws InterruptedIOException {
            if (first != null) first.awaitQuiet();
        }

        private synchronized void left() {
            if (--readers == 0) quietSince = System.nanoTime();
        }

        private synchronized void awaitQuiet() throws InterruptedIOException {
            try {
                for (long left; (left = readers > 0 ? QUIET : QUIET - (System.nanoTime() - quietSince)) > 0; )
                    wait(left / 1_000_000 + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("shutting down");
            }
        }
    }

    /** A turn on a disk; closing it again is harmless, so it can be handed on and closed by both sides. */
    public interface Hold extends AutoCloseable {
        /** No turn: a disk that is not gated, or reads that need none. */
        Hold NONE = () -> {};

        @Override void close();
    }

    static Disk detect(Path path) {
        var os = System.getProperty("os.name");
        try {
            return os.startsWith("Windows") ? Windows.detect(path) : os.startsWith("Mac") ? mac(path) : linux(path);
        } catch (Throwable e) {
            log.debug("Storage of {} is unknown, read as HDD: {}", path, e.toString());
            return new Disk(String.valueOf(path.toAbsolutePath().getRoot()), false);
        }
    }

    /** sysfs: the queue of the mounted device, or of its disk for a partition ({@code /dev/mapper/x} resolves to dm-N). */
    private static Disk linux(Path path) throws IOException {
        var store = Files.getFileStore(path);
        if (REMOTE.matcher(store.type()).matches()) return new Disk(store.name(), false);
        var sys = Path.of("/sys/class/block").resolve(Path.of(store.name()).toRealPath().getFileName()).toRealPath();
        if (Files.notExists(sys.resolve("queue"))) sys = sys.getParent();
        return new Disk(sys.toString(), Files.readString(sys.resolve("queue/rotational")).strip().equals("0"));
    }

    /** {@code diskutil info}: "Solid State" and the whole disk; APFS alone says nothing, it also runs on hard disks. */
    private static Disk mac(Path path) throws IOException, InterruptedException {
        var store = Files.getFileStore(path);
        if (REMOTE.matcher(store.type()).matches()) return new Disk(store.name(), false);
        var p = new ProcessBuilder("diskutil", "info", store.name()).redirectErrorStream(true).start();
        var info = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        var whole = Pattern.compile("Part of Whole:\\s*(\\S+)").matcher(info);
        return new Disk(whole.find() ? whole.group(1) : store.name(), Pattern.compile("Solid State:\\s*Yes").matcher(info).find());
    }

    /**
     * kernel32 over FFM, no administrator rights: a remote drive type, else the volume's seek penalty (no penalty = SSD)
     * and the number of the physical disk behind it. A bridge or virtual disk that does not answer reads as HDD.
     */
    private static final class Windows {
        static final int DRIVE_REMOTE = 4, QUERY_PROPERTY = 0x2D1400, SEEK_PENALTY = 7, DEVICE_NUMBER = 0x2D1080,
                SHARE_READ_WRITE = 3, OPEN_EXISTING = 3;
        static final MethodHandle VOLUME_PATH, DRIVE_TYPE, VOLUME_NAME, CREATE, IOCTL, CLOSE;

        static {
            var k = SymbolLookup.libraryLookup("kernel32", Arena.global());
            var l = Linker.nativeLinker();
            VOLUME_PATH = l.downcallHandle(k.find("GetVolumePathNameW").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            DRIVE_TYPE = l.downcallHandle(k.find("GetDriveTypeW").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
            VOLUME_NAME = l.downcallHandle(k.find("GetVolumeNameForVolumeMountPointW").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            CREATE = l.downcallHandle(k.find("CreateFileW").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
            IOCTL = l.downcallHandle(k.find("DeviceIoControl").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
            CLOSE = l.downcallHandle(k.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }

        static Disk detect(Path path) throws Throwable {
            try (var a = Arena.ofConfined()) {
                MemorySegment buf = a.allocate(1024), none = MemorySegment.NULL;
                if ((int) VOLUME_PATH.invokeExact(wide(a, path.toAbsolutePath().toString()), buf, 512) == 0)
                    throw new IOException("no volume");
                var root = buf.getString(0, UTF_16LE); // C:\, a mount folder, or \\server\share\
                if ((int) DRIVE_TYPE.invokeExact(wide(a, root)) == DRIVE_REMOTE) return new Disk(root, false);
                if ((int) VOLUME_NAME.invokeExact(wide(a, root), buf, 512) == 0) return new Disk(root, false);
                var volume = buf.getString(0, UTF_16LE); // \\?\Volume{guid}\ ; the device is named without the slash
                var h = (MemorySegment) CREATE.invokeExact(wide(a, volume.substring(0, volume.length() - 1)), 0,
                        SHARE_READ_WRITE, none, OPEN_EXISTING, 0, none);
                if (h.address() == -1L) return new Disk(volume, false);
                try {
                    MemorySegment query = a.allocate(12), out = a.allocate(12), n = a.allocate(JAVA_INT);
                    query.set(JAVA_INT, 0, SEEK_PENALTY); // standard query of StorageDeviceSeekPenaltyProperty
                    boolean ssd = (int) IOCTL.invokeExact(h, QUERY_PROPERTY, query, 12, out, 12, n, none) != 0
                            && out.get(JAVA_BYTE, 8) == 0;
                    boolean numbered = (int) IOCTL.invokeExact(h, DEVICE_NUMBER, none, 0, out, 12, n, none) != 0;
                    return new Disk(numbered ? "disk" + out.get(JAVA_INT, 4) : volume, ssd);
                } finally {
                    int _ = (int) CLOSE.invokeExact(h);
                }
            }
        }

        private static MemorySegment wide(Arena a, String s) { return a.allocateFrom(s, UTF_16LE); }
    }
}
