package thumbnailer.image;

import scalebench.Scaler;

/** The app's own {@link Resampler}, compiled from backend/src/main/java (see build.gradle.kts), not a copy. */
public final class ProductionScaler implements Scaler {

    @Override public String name() { return "Production"; }

    @Override
    public int[] resize(int[] src, int sw, int sh, int dw, int dh) {
        int[] out = Resampler.resize(new Pixels(src, sw, sh), dw, dh, false).argb();
        return out == src ? src.clone() : out;
    }
}
