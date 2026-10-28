package thumbnailer.image;

import java.io.IOException;

/**
 * Per-item caps on the hot path (not a global memory budget): work beyond them fails that item with an error naming
 * it, before anything is allocated for it.
 */
public final class Limits {

    /** Bytes of one file (archive member, image) read whole into memory for a render or a probe. */
    public static final int MAX_BYTES = 512 << 20;
    /** Bytes of one archive member buffered for an operation ({@code extract}): the array maximum, as it always was. */
    public static final int MAX_MEMBER = Integer.MAX_VALUE - 8;
    /** Pixels of one decode: above every single-shot camera sensor (151 MP), so no photo is refused even at full size. */
    static final int MAX_PIXELS = 200_000_000;

    private Limits() {}

    /** The refusal of {@code name}'s {@code size} bytes, beyond {@link #MAX_BYTES}. */
    public static IOException tooLarge(String name, long size) { return tooLarge(name, size, MAX_BYTES); }

    public static IOException tooLarge(String name, long size, int limit) {
        return new IOException(name + ": " + size + " bytes exceeds " + limit + " bytes");
    }

    /** {@code width × height} (exact for any two ints), or an IllegalArgumentException beyond {@link #MAX_PIXELS}. */
    static int pixels(String what, int width, int height) {
        long n = (long) width * height;
        if (n > MAX_PIXELS) throw new IllegalArgumentException(what + ": " + width + "x" + height + " exceeds " + MAX_PIXELS + " pixels");
        return (int) n;
    }
}
