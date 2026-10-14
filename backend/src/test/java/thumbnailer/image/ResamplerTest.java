package thumbnailer.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.SplittableRandom;
import java.util.function.IntBinaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ResamplerTest {

    /** Down without a box, with 2/4/8 boxes and cropped edges, long Q22 kernels, enlargements, degenerate sizes. */
    private static final int[][] SHAPES = {{900, 600, 512, 341}, {1501, 1003, 512, 341}, {3000, 2001, 512, 341},
            {4000, 3000, 512, 384}, {4097, 203, 512, 25}, {1999, 1333, 256, 171}, {2000, 1500, 1600, 1067},
            {300, 200, 1200, 800}, {7, 5, 199, 133}, {1, 1, 3, 3}, {3, 1, 1, 1}, {1000, 7, 999, 7}, {640, 480, 45, 3}};

    @Test
    void simdAndScalarComputeTheSameBits() {
        assumeTrue(Resampler.SIMD, "needs --add-modules jdk.incubator.vector and 256-bit vectors");
        var rnd = new SplittableRandom(5);
        for (int[] s : SHAPES) {
            var src = image(s[0], s[1], (x, y) -> rnd.nextInt()); // garbage top byte included
            for (boolean boxed : new boolean[] {false, true})
                assertThat(Resampler.resize(src, s[2], s[3], boxed, true).argb()).as("%dx%d -> %dx%d %s", s[0], s[1], s[2], s[3], boxed)
                        .isEqualTo(Resampler.resize(src, s[2], s[3], boxed, false).argb());
        }
    }

    @Test
    void constantColourStaysExactOnBothPathsAndBothDirections() {
        for (int[] s : SHAPES)
            for (boolean simd : new boolean[] {false, Resampler.SIMD}) {
                var out = Resampler.resize(image(s[0], s[1], (x, y) -> (x * 31 + y) << 24 | 0x25C863), s[2], s[3], s[0] > 1000, simd).argb();
                assertThat(IntStream.of(out).map(p -> p & 0xFFFFFF).distinct().toArray()).as("%s %dx%d", simd, s[0], s[1]).containsExactly(0x25C863);
            }
    }

    @Test
    void checkerboardDownscaled4xIsMidGrey() {
        int[] out = Resampler.resize(image(256, 256, (x, y) -> (x + y) % 2 == 0 ? 0 : 0xFFFFFF), 64, 64, false).argb();
        for (int p : out)
            for (int shift : new int[] {16, 8, 0})
                assertThat(p >>> shift & 255).as("channel %d of %06x", shift, p).isBetween(118, 138);
    }

    @Test
    void sizesAndPreconditions() {
        var src = image(640, 480, (x, y) -> 0);
        var out = Resampler.resize(src, 512, 288, false);
        assertThat(out.width() + "x" + out.height()).isEqualTo("512x288");
        assertThat(out.argb()).hasSize(512 * 288);
        assertThat(Resampler.resize(src, 640, 480, false)).isSameAs(src);
        assertThat(Resampler.resize(src, 1280, 960, true).argb()).as("enlarging ignores boxed").hasSize(1280 * 960);
        assertThatThrownBy(() -> Resampler.resize(src, 0, 1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Resampler.box(6000, 4000, 512, 341)).isEqualTo(4);
        assertThat(Resampler.box(1500, 1000, 512, 341)).as("DCT-scaled input: kernel only").isEqualTo(1);
        assertThat(Resampler.box(16000, 23, 512, 1)).as("cropping 7 of 23 rows would lose content").isEqualTo(1);
        var strip = Resampler.resize(image(16000, 23, (x, y) -> y < 16 ? 0 : 0xFFFFFF), 512, 1, false);
        assertThat(strip.argb()[256] & 255).as("the white bottom rows still count").isGreaterThan(40);
    }

    @Test
    void horizontalGradientStaysMonotonic() {
        int[] out = Resampler.resize(image(1024, 16, (x, y) -> (x >> 2) * 0x010101), 512, 8, false).argb();
        for (int y = 0; y < 8; y++) {
            int row = y * 512;
            assertThat(IntStream.range(0, 512).map(x -> out[row + x] & 255).boxed().toList()).isSorted();
        }
        assertThat(out[0] & 255).isLessThan(8);
        assertThat(out[511] & 255).isGreaterThan(247);
    }

    private static Pixels image(int w, int h, IntBinaryOperator rgb) {
        int[] data = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) data[y * w + x] = rgb.applyAsInt(x, y);
        return new Pixels(data, w, h);
    }
}
