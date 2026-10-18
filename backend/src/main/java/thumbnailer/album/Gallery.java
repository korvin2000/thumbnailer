package thumbnailer.album;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import thumbnailer.image.Limiter;
import thumbnailer.image.Render;
import thumbnailer.image.Thumbnailer.Thumb;
import thumbnailer.image.Thumbnailer;
import thumbnailer.source.Disks;
import thumbnailer.spi.AlbumContainer;

/**
 * The entry thumbnails of an archive that reads only front to back (solid 7z/RAR, a compressed tar): one pass in
 * packing order renders them as the viewer asks, where a request per thumbnail would decompress from the block start
 * each time (Θ(N²) for the gallery). The pass is the reader of the render pipeline: it holds its disk's turn while it
 * reads, puts each file in the queue and hands it to a decoder, so thumbnails render in parallel. Paced by demand: it
 * runs while an asked entry is still unread and else stops {@link #LOOKAHEAD} thumbnails ahead of what was taken,
 * letting the disk go while it waits; on its way to an asked entry it renders no more than that lookahead and passes
 * over the rest, so it never holds more than {@link #LOOKAHEAD} thumbnails nobody asked for. A taken thumbnail is
 * forgotten (the browser caches it by revision); one passed over or taken before renders alone. Closes after a minute without
 * requests, when evicted, or at shutdown.
 */
final class Gallery {

    private static final Logger log = LoggerFactory.getLogger(Gallery.class);
    static final int LOOKAHEAD = 24;
    private static final long IDLE = TimeUnit.MINUTES.toNanos(1), WAIT_SECONDS = 300;

    /** Opens the album's container; the pass owns it. */
    interface Source { AlbumContainer open() throws IOException; }

    private final Map<String, CompletableFuture<Thumb>> thumbs = new ConcurrentHashMap<>();
    private final Set<String> unread = ConcurrentHashMap.newKeySet(), asked = ConcurrentHashMap.newKeySet(); // asked ⊆ unread
    private final Thread pass;
    private int ahead; // thumbnails handed to decoders and not taken yet (guarded by this)
    private long lastAsk = System.nanoTime();
    private boolean closed;

    /**
     * Ready to {@link #start}; {@code spec} must be the current thumbnail spec (its threads too); {@code ended} runs on
     * the pass once it has stopped and closed the container.
     */
    Gallery(Source source, List<String> names, Disks.Lane lane, Limiter queue, Limiter slots, Render spec, Thumbnailer thumbnailer,
            Runnable ended) {
        names.forEach(n -> thumbs.put(n, new CompletableFuture<>()));
        unread.addAll(names);
        pass = Thread.ofVirtual().name("gallery").unstarted(() -> {
            var turn = new AtomicReference<Disks.Hold>(); // set and cleared on 7-Zip's threads too
            try (var container = source.open()) {
                turn.set(lane.enter());
                container.heads(names, Integer.MAX_VALUE, (name, bytes) -> {
                    boolean render = pace(name, turn);
                    if (turn.get() == null) turn.set(lane.enter()); // back from a pause: wait for the disk again
                    var thumb = thumbs.get(name);
                    if (thumb == null) return; // closed meanwhile
                    if (!render) { // passed over on the way to an asked entry: whoever asks later renders it alone
                        unread.remove(name);
                        thumbs.remove(name, thumb);
                        thumb.completeExceptionally(new CancellationException("passed over"));
                        return;
                    }
                    queue.enter(2 * spec.permits()); // turn → queue → slot, as in AlbumService.render
                    unread.remove(name);
                    asked.remove(name);
                    Thread.ofVirtual().start(() -> {
                        try {
                            slots.enter(spec.permits());
                            try {
                                thumb.complete(thumbnailer.render(MemorySegment.ofArray(bytes), name, spec));
                            } finally {
                                slots.release();
                            }
                        } catch (Throwable e) {
                            thumb.completeExceptionally(e);
                        } finally {
                            queue.release();
                        }
                    });
                });
            } catch (IOException | RuntimeException e) {
                if (!closed()) log.warn("Gallery pass stopped: {}", e.toString());
            } finally {
                var t = turn.get();
                if (t != null) t.close();
                unread.forEach(n -> thumbs.getOrDefault(n, new CompletableFuture<>()).completeExceptionally(new CancellationException("not reached")));
                ended.run();
            }
        });
    }

    /** The thumbnail of {@code name}, waiting for the pass; null when this pass has none (taken before, or closed). */
    Thumb take(String name) throws IOException {
        var f = thumbs.get(name);
        if (f == null) return null;
        synchronized (this) {
            if (unread.contains(name)) asked.add(name);
            lastAsk = System.nanoTime();
            notifyAll();
        }
        try {
            var thumb = f.get(WAIT_SECONDS, TimeUnit.SECONDS);
            if (thumbs.remove(name, f)) synchronized (this) { ahead--; notifyAll(); }
            return thumb;
        } catch (CancellationException e) { // never reached: closed, or not in the archive
            return null;
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("Gallery pass too slow for " + name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    void start() { pass.start(); }

    /** Stops the pass and drops what nobody took; waiting requests render alone. */
    void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        pass.interrupt(); // the 7-Zip pass checks its owner's interrupt
        thumbs.values().forEach(f -> f.completeExceptionally(new CancellationException("closed")));
        thumbs.clear();
    }

    /** Closed passes are replaced on the next request; what they rendered and nobody took is lost. */
    synchronized boolean closed() { return closed; }

    /**
     * Waits while no asked entry is unread and {@link #LOOKAHEAD} thumbnails are ahead, without the disk's turn; then
     * whether {@code name} renders: asked, or within the lookahead. May run on a 7-Zip thread, which no interrupt
     * reaches, so closing notifies it.
     */
    private synchronized boolean pace(String name, AtomicReference<Disks.Hold> turn) throws IOException {
        asked.retainAll(unread); // an ask that raced with the read of its entry
        while (!closed && asked.isEmpty() && ahead >= LOOKAHEAD) {
            var t = turn.getAndSet(null);
            if (t != null) t.close();
            long idle = IDLE - (System.nanoTime() - lastAsk);
            if (idle <= 0) closed = true; // nobody has looked for a minute
            else try {
                wait(TimeUnit.NANOSECONDS.toMillis(idle) + 1);
            } catch (InterruptedException e) {
                closed = true;
            }
        }
        if (closed) throw new InterruptedIOException("gallery closed");
        boolean render = asked.contains(name) || ahead < LOOKAHEAD;
        if (render) ahead++;
        return render;
    }
}
