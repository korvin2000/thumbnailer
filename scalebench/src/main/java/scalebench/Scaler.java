package scalebench;

/**
 * The contract every resampler meets: packed XRGB pixels ({@code 0x??RRGGBB}, row-major, no padding; the top byte of
 * the input is garbage and must be ignored), any ratio in either direction, single-threaded per call (throughput comes
 * from parallel renders), no shared mutable state. The output's top byte is unspecified.
 */
public interface Scaler {

    String name();

    int[] resize(int[] src, int sw, int sh, int dw, int dh);

    /** Whether this scaler handles {@code sw x sh -> dw x dh} (the frozen thumbnailer baseline never upscales). */
    default boolean supports(int sw, int sh, int dw, int dh) { return true; }
}
