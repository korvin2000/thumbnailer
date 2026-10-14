package thumbnailer.image;

import java.io.InterruptedIOException;
import java.util.concurrent.Semaphore;

/**
 * Bounds CPU-bound renders by a size that follows the settings: resized before each acquire, a shrink takes effect as
 * running renders release. Fair: a waiter may hold its disk's turn ({@code Disks.Lane}), so others must not overtake it.
 */
public final class Limiter extends Semaphore {

    private int size;

    public Limiter() { super(0, true); }

    public synchronized void resize(int permits) {
        if (permits > size) release(permits - size);
        else if (permits < size) reducePermits(size - permits);
        size = permits;
    }

    /** Resizes to {@code permits}, then takes one; pair with {@link #release()}. */
    public void enter(int permits) throws InterruptedIOException {
        resize(permits);
        try {
            acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("shutting down");
        }
    }
}
