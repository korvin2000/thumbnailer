package thumbnailer.image;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import thumbnailer.natives.LibraryLoader;

/**
 * libjpeg-turbo 3 (the AVX2 build of github.com/korvin2000/libjpeg-turbo) through its C API over FFM: no JNI glue.
 * Decoding scales in the DCT domain (M/8: the IDCT computes only the needed coefficients, 1/8 is the 8x8 block mean)
 * into {@code TJPF_BGRX} = {@code 0xFFRRGGBB} on little-endian, the layout of {@link Pixels}. The downcalls are ordinary,
 * not {@linkplain Linker.Option#critical critical}: a 10-50 ms decode must not hold off safepoints of the other render
 * threads, so the codec works in native memory and the pixels cross into the heap with one bulk copy (about 1 % of a
 * decode).
 */
final class TurboJpeg {

    private static final int COMPRESS = 0, DECOMPRESS = 1, PF_BGRX = 3, SAMP_420 = 2, CS_CMYK = 3, CS_YCCK = 4, WARNING = 0;
    private static final int NOREALLOC = 2, QUALITY = 3, SUBSAMP = 4, WIDTH = 5, HEIGHT = 6, PRECISION = 7, COLORSPACE = 8,
            FAST_UPSAMPLE = 9, FAST_DCT = 10, LOSSLESS = 15;
    private static final MethodHandle INIT, DESTROY, ERROR_STR, ERROR_CODE, SET, GET, BUF_SIZE, HEADER, SCALE, DECODE, ENCODE, ALLOC, FREE;

    static {
        var linker = Linker.nativeLinker();
        var lib = SymbolLookup.libraryLookup(LibraryLoader.path("turbojpeg"), Arena.global());
        INIT = bind(linker, lib, "tj3Init", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        DESTROY = bind(linker, lib, "tj3Destroy", FunctionDescriptor.ofVoid(ADDRESS));
        ERROR_STR = bind(linker, lib, "tj3GetErrorStr", FunctionDescriptor.of(ADDRESS, ADDRESS));
        ERROR_CODE = bind(linker, lib, "tj3GetErrorCode", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        SET = bind(linker, lib, "tj3Set", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        GET = bind(linker, lib, "tj3Get", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        BUF_SIZE = bind(linker, lib, "tj3JPEGBufSize", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT));
        ALLOC = bind(linker, lib, "tj3Alloc", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
        FREE = bind(linker, lib, "tj3Free", FunctionDescriptor.ofVoid(ADDRESS));
        HEADER = bind(linker, lib, "tj3DecompressHeader", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        SCALE = bind(linker, lib, "tj3SetScalingFactor", FunctionDescriptor.of(JAVA_INT, ADDRESS, MemoryLayout.structLayout(JAVA_INT, JAVA_INT)));
        DECODE = bind(linker, lib, "tj3Decompress8",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));
        ENCODE = bind(linker, lib, "tj3Compress8",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    }

    private TurboJpeg() {}

    static boolean isJpeg(MemorySegment data) {
        return data.byteSize() > 3 && data.get(JAVA_BYTE, 0) == (byte) 0xFF && data.get(JAVA_BYTE, 1) == (byte) 0xD8;
    }

    /**
     * Decodes at the smallest DCT scale (1/8, 1/4, 1/2, 1) whose longer side still reaches {@code minLonger}, so the
     * resampler only ever shrinks; fast upsampling and IDCT, since the result is shrunk anyway. CMYK/YCCK and 12-bit
     * images throw (ImageIO handles them); corrupt-but-decodable data (a libjpeg warning) is accepted. Lossless JPEGs
     * ignore scaling, so they decode at full size.
     */
    static Pixels decode(MemorySegment jpeg, int minLonger) throws IOException {
        try (var arena = Arena.ofConfined()) {
            if (!jpeg.isNative()) jpeg = arena.allocate(jpeg.byteSize()).copyFrom(jpeg);
            var h = handle(DECOMPRESS);
            try {
                check(h, (int) HEADER.invokeExact(h, jpeg, jpeg.byteSize()));
                int w = (int) GET.invokeExact(h, WIDTH), ht = (int) GET.invokeExact(h, HEIGHT), cs = (int) GET.invokeExact(h, COLORSPACE);
                if (cs == CS_CMYK || cs == CS_YCCK || (int) GET.invokeExact(h, PRECISION) != 8)
                    throw new IOException("TurboJPEG: CMYK or 12-bit image");
                // eighths; 3/8, 5/8 ... have no SIMD IDCT and decode slower than 1/2; lossless ignores scaling: full size
                int longer = Math.max(w, ht), m = (int) GET.invokeExact(h, LOSSLESS) == 1 ? 8 : 1;
                while (m < 8 && Math.ceilDiv(longer * m, 8) < minLonger) m *= 2;
                if (m < 8) {
                    var factor = arena.allocate(8);
                    factor.set(JAVA_INT, 0, 1);
                    factor.set(JAVA_INT, 4, 8 / m); // reduced, as libjpeg-turbo lists them
                    check(h, (int) SCALE.invokeExact(h, factor));
                }
                check(h, (int) SET.invokeExact(h, FAST_UPSAMPLE, 1));
                check(h, (int) SET.invokeExact(h, FAST_DCT, 1));
                int sw = Math.ceilDiv(w * m, 8), sh = Math.ceilDiv(ht * m, 8);
                var px = new int[sw * sh];
                var out = alloc((long) px.length * 4); // uninitialized: an arena would zero megabytes first
                try {
                    check(h, (int) DECODE.invokeExact(h, jpeg, jpeg.byteSize(), out, sw * 4, PF_BGRX));
                    MemorySegment.copy(out, JAVA_INT, 0, px, 0, px.length);
                } finally {
                    FREE.invokeExact(out);
                }
                return new Pixels(px, sw, sh);
            } finally {
                DESTROY.invokeExact(h);
            }
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    /** Baseline 4:2:0 JPEG at {@code quality}, standard Huffman tables, written into a worst-case buffer (no realloc). */
    static byte[] encode(Pixels p, int quality) throws IOException {
        try (var arena = Arena.ofConfined()) {
            var h = handle(COMPRESS);
            try {
                check(h, (int) SET.invokeExact(h, QUALITY, quality));
                check(h, (int) SET.invokeExact(h, SUBSAMP, SAMP_420));
                check(h, (int) SET.invokeExact(h, NOREALLOC, 1));
                long cap = (long) BUF_SIZE.invokeExact(p.width(), p.height(), SAMP_420);
                var buf = alloc(cap);
                try {
                    var bufRef = arena.allocateFrom(ADDRESS, buf);
                    var size = arena.allocateFrom(JAVA_LONG, cap);
                    var src = arena.allocateFrom(JAVA_INT, p.argb());
                    check(h, (int) ENCODE.invokeExact(h, src, p.width(), p.width() * 4, p.height(), PF_BGRX, bufRef, size));
                    return buf.asSlice(0, size.get(JAVA_LONG, 0)).toArray(JAVA_BYTE);
                } finally {
                    FREE.invokeExact(buf);
                }
            } finally {
                DESTROY.invokeExact(h);
            }
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t);
        }
    }

    private static MemorySegment alloc(long bytes) throws Throwable {
        var m = (MemorySegment) ALLOC.invokeExact(bytes);
        if (m.equals(MemorySegment.NULL)) throw new OutOfMemoryError("tj3Alloc " + bytes);
        return m.reinterpret(bytes);
    }

    private static MemorySegment handle(int type) throws Throwable {
        var h = (MemorySegment) INIT.invokeExact(type);
        if (h.equals(MemorySegment.NULL)) throw new IOException("tj3Init failed");
        return h;
    }

    private static void check(MemorySegment h, int rc) throws Throwable {
        if (rc < 0 && (int) ERROR_CODE.invokeExact(h) != WARNING)
            throw new IOException("TurboJPEG: " + ((MemorySegment) ERROR_STR.invokeExact(h)).reinterpret(200).getString(0));
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lib, String name, FunctionDescriptor d, Linker.Option... options) {
        return linker.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("turbojpeg: no " + name)), d, options);
    }
}
