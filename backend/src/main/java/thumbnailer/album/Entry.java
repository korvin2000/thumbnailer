package thumbnailer.album;

import java.time.LocalDateTime;

/** An image inside an album; {@code index} is its position in the name-sorted image list, {@code mtime} is internal. */
public record Entry(int index, String name, long bytes, long mtime, int width, int height, LocalDateTime shotAt) {}
