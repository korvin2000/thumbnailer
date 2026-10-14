package dev.matrixlab.webp4j.internal;

import dev.matrixlab.webp4j.model.WebPBitstreamFeatures;
import thumbnailer.natives.LibraryLoader;

/**
 * The JNI surface of webp4j 2.5.0 (MIT, github.com/MrNanko/webp4j) that thumbnailer calls; the library binds its
 * natives by this class name. Loaded through {@link LibraryLoader} (persistent cache) instead of webp4j's temp-file
 * extraction; the optimized libwebp 1.6.0 build comes from github.com/korvin2000/libwebp. Undeclared natives are
 * never bound.
 */
public final class NativeWebP {

    static { LibraryLoader.load("webp4j"); }

    private NativeWebP() {}

    /** {@code WebPGetFeatures}: width, height, hasAlpha, hasAnimation, format; a header-only prefix suffices. */
    public static native int getFeatures(byte[] data, int dataSize, WebPBitstreamFeatures features);

    /** {@code WebPDecodeBGRAInto} straight into packed ARGB ints (critical, zero copy); stride in bytes. */
    public static native boolean decodeInto(byte[] data, int[] output, int outputStride);

    /** Encodes packed (A)RGB ints, imported as BGRX/BGRA without a copy; preset DEFAULT (method 4). */
    public static native byte[] encode(int[] pixels, int width, int height, float quality, boolean lossless, boolean hasAlpha,
            boolean multiThreaded);
}
