package thumbnailer.index;

import java.time.Instant;

/** Published while a scan runs (throttled to 4/s) and once when it ends. */
public record ScanProgress(boolean running, String root, int discovered, int indexed, int changed, int removed,
                           String message, Instant startedAt, Instant finishedAt) {}
