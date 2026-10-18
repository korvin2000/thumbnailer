package thumbnailer.ops;

import java.time.Instant;

/** Published while an operation runs (throttled to 4/s) and once when it ends; {@code current}: the item in hand. */
public record OpProgress(String id, Op.Kind kind, boolean running, int total, int done, String current, int skipped,
                         int failed, String message, Instant startedAt, Instant finishedAt) {}
