package scalebench.candidates;

import scalebench.Scaler;
import scalebench.candidates.fable.Fable;

/** Candidate "Fable": box pre-shrink + Catmull-Rom, Vector API with a scalar fallback (see {@link Fable}). */
public final class FableScaler implements Scaler {

    @Override public String name() { return "Fable"; }

    @Override public boolean supports(int sw, int sh, int dw, int dh) { return true; }

    @Override public int[] resize(int[] src, int sw, int sh, int dw, int dh) { return Fable.resize(src, sw, sh, dw, dh); }
}
