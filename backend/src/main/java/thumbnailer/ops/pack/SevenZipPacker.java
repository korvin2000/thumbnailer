package thumbnailer.ops.pack;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.sf.sevenzipjbinding.IOutCreateCallback;
import net.sf.sevenzipjbinding.IOutItem7z;
import net.sf.sevenzipjbinding.ISequentialInStream;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.OutItemFactory;
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.AlbumContainer.Item;
import thumbnailer.source.SevenZipContainer;

/**
 * Writes a 7z through 7-Zip-JBinding: method Copy (level 0), not solid, so the result reads at random like a stored
 * zip (catalog_plan.md §4.5). Level 0 is the only setting: JBinding hands {@code setSolid}/{@code setThreadCount} to
 * 7-Zip as separate property calls, each of which resets the others (the level with them: LZMA2 came back), while
 * level 0 alone writes Copy non-solid (asserted by {@code PackersTest}), and Copy has no encoder threads. 7-Zip pulls each entry's bytes when it wants them while
 * {@link AlbumContainer#extract} pushes them in packing order on a {@code pack-read} thread; a {@link Relay} hands one
 * to the other, spilling to a file beside the target any entry the pass delivers before 7-Zip asks for it.
 */
final class SevenZipPacker {

    /**
     * The order 7-Zip asks for new entries in (its file-name order: the separator first, case ignored on Windows):
     * the pass follows it, so nothing spills.
     */
    static final Comparator<String> ORDER = Comparator.comparing((String n) -> n.replace('/', '\0'),
            File.separatorChar == '\\' ? String.CASE_INSENSITIVE_ORDER : Comparator.naturalOrder());

    private SevenZipPacker() {}

    static Map<String, Long> pack(AlbumContainer raw, Collection<String> names, Path target) throws IOException {
        SevenZipContainer.loadNative();
        var byName = new HashMap<String, Item>();
        raw.items().forEach(i -> byName.put(i.name(), i));
        var items = names.stream().sorted(ORDER).map(n -> byName.get(n)).toList();
        int missing = items.indexOf(null);
        if (missing >= 0) throw new FileNotFoundException(names.stream().sorted(ORDER).toList().get(missing));
        Files.createFile(target); // a new file: never one that was there, nor what a link there points to
        var relay = new Relay(target);
        var reader = Thread.ofPlatform().daemon().name("pack-read").start(() -> relay.produce(raw, items));
        try (var raf = new RandomAccessFile(target.toFile(), "rw"); var out = SevenZip.openOutArchive7z()) {
            out.setLevel(0);
            out.createArchive(new RandomAccessFileOutStream(raf), items.size(), new IOutCreateCallback<IOutItem7z>() {
                @Override public void setOperationResult(boolean ok) {}
                @Override public void setTotal(long total) {}
                @Override public void setCompleted(long done) {}

                @Override
                public IOutItem7z getItemInformation(int i, OutItemFactory<IOutItem7z> factory) {
                    var item = items.get(i);
                    var o = factory.createOutItem();
                    o.setDataSize(item.bytes());
                    o.setPropertyPath(item.name());
                    if (item.mtime() > 0) o.setPropertyLastModificationTime(new Date(item.mtime()));
                    return o;
                }

                @Override public ISequentialInStream getStream(int i) throws SevenZipException { return relay.take(items.get(i).name()); }
            });
        } catch (SevenZipException e) {
            relay.end(new IOException("7-Zip could not write " + target.getFileName() + ": " + e.getMessage(), e));
        } finally {
            relay.finish(); // 7-Zip is done: its last stream closes
            relay.end(null);
            try {
                join(reader, relay);
            } finally {
                relay.clean();
            }
        }
        if (relay.failure != null) throw relay.failure; // the pass's own failure first: it stopped 7-Zip
        if (relay.hashes.size() < items.size()) throw new IOException("Not every entry reached " + target.getFileName());
        return relay.hashes;
    }

    /** Waits for the pass; interrupted, it stops the pass and still waits: nothing reads {@code raw} once pack returns. */
    private static void join(Thread t, Relay relay) throws InterruptedIOException {
        boolean interrupted = false;
        while (t.isAlive()) {
            try {
                t.join();
            } catch (InterruptedException e) {
                interrupted = true;
                relay.end(new InterruptedIOException());
                t.interrupt();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    /**
     * The meeting point of the pass (producer) and 7-Zip (consumer): the entry 7-Zip {@code wanted} streams to it
     * directly while the pass waits; any other is spilled, then read from its file when asked for.
     */
    static final class Relay {
        final Map<String, Long> hashes = new ConcurrentHashMap<>();
        final Map<String, Path> spilled = new HashMap<>();
        final Path target;
        String wanted;
        Hashed offered;
        InputStream current; // the spill file 7-Zip reads
        boolean ended;
        volatile IOException failure;

        Relay(Path target) { this.target = target; }

        /** The pass; however it ends, the relay ends with it, or 7-Zip would wait for its next entry forever. */
        void produce(AlbumContainer raw, Collection<Item> items) {
            try {
                raw.extract(items.stream().map(Item::name).toList(), this::accept);
                end(null);
            } catch (IOException e) {
                end(e);
            } catch (Throwable e) {
                end(new IOException(e));
                if (e instanceof Error error) throw error;
            }
        }

        /**
         * One entry of the pass, possibly on a 7-Zip thread of the source. Once 7-Zip is done, an entry it never asked
         * for (an empty file, maybe) is only hashed: the verification finds anything missing.
         */
        void accept(Item item, InputStream in) throws IOException {
            var h = new Hashed(in);
            boolean direct, late;
            synchronized (this) {
                while (wanted == null && !ended) await();
                if (failure != null) throw new InterruptedIOException("The 7z writer stopped");
                late = ended;
                direct = !late && item.name().equals(wanted);
                if (direct) {
                    offered = h;
                    notifyAll();
                    while (offered == h && !ended) await();
                }
            }
            if (direct || late) h.copyTo(OutputStream.nullOutputStream()); // what 7-Zip left unread: nothing, normally
            else {
                var f = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName() + ".", ".spill");
                synchronized (this) {
                    spilled.put(item.name(), f); // deleted by clean() from here on, even half written
                }
                try (var out = Files.newOutputStream(f)) {
                    h.copyTo(out);
                }
            }
            h.expect(item.name(), item.bytes()); // 7-Zip stores what it got: a short source would verify
            synchronized (this) {
                hashes.put(item.name(), h.hash()); // a spilled entry is complete from here on
                notifyAll();
            }
        }

        /** 7-Zip asks for {@code name}'s bytes: the previous entry is done. */
        synchronized ISequentialInStream take(String name) throws SevenZipException {
            finish();
            wanted = name;
            notifyAll();
            while (true) {
                if (offered != null) {
                    wanted = null;
                    return stream(offered);
                }
                var f = hashes.containsKey(name) ? spilled.get(name) : null;
                if (f != null) {
                    wanted = null;
                    try {
                        return stream(current = Files.newInputStream(f));
                    } catch (IOException e) {
                        throw new SevenZipException(e);
                    }
                }
                if (ended) throw new SevenZipException("No bytes for " + name, failure);
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    end(new InterruptedIOException());
                    throw new SevenZipException("Interrupted");
                }
            }
        }

        /** The entry 7-Zip read last is done (only 7-Zip's side knows): the pass goes on, a spill file closes. */
        private synchronized void finish() {
            offered = null;
            try (var c = current) {
                current = null;
            } catch (IOException ignored) {
                // a read-only file
            }
            notifyAll();
        }

        /** Either side is done (null) or failed; 7-Zip may still be reading its stream, which stays open. */
        synchronized void end(IOException e) {
            if (failure == null) failure = e;
            ended = true;
            notifyAll();
        }

        /** Spill files go once 7-Zip and the pass are done. */
        void clean() throws IOException {
            for (var f : spilled.values()) Files.deleteIfExists(f);
        }

        private void await() throws InterruptedIOException {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }

        private ISequentialInStream stream(InputStream in) {
            return new ISequentialInStream() {
                @Override
                public int read(byte[] data) throws SevenZipException {
                    try {
                        int n = in.read(data);
                        if (n > 0) return n;
                        finish();
                        return 0;
                    } catch (IOException e) {
                        throw new SevenZipException(e);
                    }
                }

                @Override public void close() {}
            };
        }
    }
}
