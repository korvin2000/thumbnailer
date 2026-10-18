package thumbnailer.album;

import java.time.LocalDateTime;

/**
 * An image inside an album; {@code index} is its position in the name-sorted image list, {@code mtime} is internal.
 * {@code flags}: {@link #META_KNOWN} once its metadata was looked for, {@link #META} if it carries some (0 = unknown).
 */
public record Entry(int index, String name, long bytes, long mtime, int width, int height, LocalDateTime shotAt, int flags) {

    public static final int META_KNOWN = 1, META = 2;
}
