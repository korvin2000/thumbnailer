package thumbnailer.index;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Admission: one activity at a time among scans, file operations, a pending restart and root settings saves, taken by
 * compare-and-set (two {@code running()} checks would not exclude each other). A restart is never released: the next
 * context has a gate of its own.
 */
@Component
public class Gate {

    public enum Activity {
        SCAN("A scan is running"), OP("An operation is running"), RESTART("A restart is pending"), SETTINGS("Settings are being saved");

        private final String blocker;

        Activity(String blocker) { this.blocker = blocker; }
    }

    private final AtomicReference<Activity> current = new AtomicReference<>();

    public Optional<Hold> tryEnter(Activity activity) {
        return current.compareAndSet(null, activity) ? Optional.of(new Hold(activity)) : Optional.empty();
    }

    /** null = idle. */
    public Activity current() { return current.get(); }

    /** Why an activity is refused now, for the user. */
    public String blocker() {
        var a = current.get();
        return a == null ? "Busy, try again" : a.blocker;
    }

    /** A held gate; closing it again is harmless. */
    public final class Hold implements AutoCloseable {
        private final Activity activity;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Hold(Activity activity) { this.activity = activity; }

        @Override
        public void close() {
            if (activity != Activity.RESTART && open.getAndSet(false)) current.compareAndSet(activity, null);
        }

        /** Hands the held gate over to a restart, which keeps it; false if this hold was already closed. */
        public boolean restart() { return open.getAndSet(false) && current.compareAndSet(activity, Activity.RESTART); }
    }
}
