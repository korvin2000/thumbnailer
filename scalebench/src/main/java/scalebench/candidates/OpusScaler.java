package scalebench.candidates;

import scalebench.Scaler;
import scalebench.candidates.opus.Resampler;

/** Candidate "Opus": see {@link Resampler}. */
public final class OpusScaler implements Scaler {

    @Override public String name() { return "Opus"; }

    @Override public int[] resize(int[] src, int sw, int sh, int dw, int dh) { return Resampler.resize(src, sw, sh, dw, dh); }
}
