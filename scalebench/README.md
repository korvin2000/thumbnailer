# scalebench — resampler quality and speed

Standalone Gradle build (not part of the app) that scores image resamplers on the same deterministic synthetic
inputs. It decided which resampler `thumbnailer.image` uses ([PLAN.md](../PLAN.md) decisions).

```
./gradlew -p scalebench run --args="[quality|speed|all] [--only=A,B,...] [--quick] [--time=ms]"
```

Needs `JAVA_HOME` = JDK 27 (`devtools/jdk-27`); runs with `--add-modules jdk.incubator.vector`. Speed runs one JVM per
scaler: Vector API kernels share the JDK's JIT profiles, and a candidate measured after another ran up to 2x slower.
`Production` is the app's `thumbnailer.image.Resampler`, compiled from `backend/src/main/java` (no copy).
Results: [results.md](results.md).

## Contract ([Scaler](src/main/java/scalebench/Scaler.java))

`int[] resize(int[] src, int sw, int sh, int dw, int dh)`: packed XRGB (`0x??RRGGBB`, row-major, no padding, input top
byte is garbage), any ratio in either direction, single-threaded per call (thumbnailer parallelizes across renders),
re-entrant (no shared mutable state), top byte of the output unspecified. The same `int[]` layout is what TurboJPEG
decodes into (`TJPF_BGRX` on little-endian) and what webp4j / TurboJPEG encode from, so the app never converts.

## Scoring

- **Cases**: D1 6000x4000→512x341 (full-size decode), D2 750x500→512x341 (after a 1/8 DCT-scaled decode),
  D3 1500x1000→512x341 (after 1/4 — the app's thumbnail path), D4 3000x2000→1600x1067, D5 4000x3000→512x384 (12 MP,
  ratio 7.8), D6 6001x4001→512x341 (odd size), U1 400x300→1600x1200, U2 1000x667→1500x1000.
- **Quality**: PSNR / SSIM on luma against the float64 Lanczos3 resample ([Reference](src/main/java/scalebench/Reference.java),
  Pillow geometry: pixel centres at `i + 0.5`, support widened by the scale when shrinking) of a photo-like image
  ([Images.photo](src/main/java/scalebench/Images.java): 1/f colour noise, hard edges, text, hair-thin curves, grain).
  Upscales are scored as reconstruction: the original is shrunk with the reference, then enlarged back and compared
  with the original. Zone plate: MTF = contrast kept below 0.25 cycles/px (1 = ideal), alias = residual above 0.6
  cycles/px, beyond the output Nyquist limit (0 = ideal). A half-pixel misalignment costs PSNR at small ratios (the
  NOBEL_* scalers show it on D2/D4), sharpness alone does not win.
- **Speed**: median / best ms per call after warm-up, one thread.

## Scalers

Baselines ([Baselines](src/main/java/scalebench/Baselines.java)): `Current` (thumbnailer's Resampler as of
2026-10-13: antialiased Catmull-Rom, 22-bit fixed point; downscale only), `AwtBilinear`, `AwtBicubic` (Java2D
`drawImage`), `NobelMultistep`, `NobelLanczos3`, `NobelBicubic` (java-image-scaling 0.8.6, xview `ScaleFactory`).
Candidates live in [candidates/](src/main/java/scalebench/candidates/).

## The 2026-10-13 competition and the outcome

Four models built a candidate each in a private copy of this build (`lab/<name>/`, git-ignored) under the same brief:
quality gates relative to `Current`, faster than `NobelMultistep` on every case, any Java 27 trick, a scalar path when
the Vector API module is absent.

| candidate | approach | vector speed | scalar fallback |
|---|---|---|---|
| **Astra** (Codex gpt-6-astra, xhigh) | Q7 fixed point, R and B sharing one multiply in 16-bit fields, one row buffer, shuffle/blend plans instead of gathers, 4x box + Lanczos3 for ratio ≥ 8 | best D2, D4; odd sizes and ratio 4–8 slow | best on D1/D3 |
| Fable (Fable 5.1, max) | float lanes, two output rows per vertical pass interleaved for a fold-free horizontal pass, 2/4/8 boxes + sharpened cubic | best D3, D5, D6 | slower than `Current` |
| Opus (Opus 5.5, max) | float lanes, vertical-first ring, 8-lane FMA over tap pairs, 2/4/8 boxes + Lanczos3, plan cache | best D1, upscales | best on D2 |
| Sol (Codex gpt-6.1-sol, xhigh) | int Q14 cubic + transposes; its run stalled before tuning | behind | — |

`Production` = Astra's kernels (their exact shape: C2 keeps them in registers only so) + the power-of-two box of
Fable/Opus with cropping to whole boxes and a 1.9x residual, shuffle plans up to ratio 4, and a `boxed` flag that
finishes DCT-scaled JPEG decodes with the Lanczos3. Against the old resampler: 6.6–8.6x faster on D1–D6 with the
vector path (D3 12.8 → 2.1 ms), 2–3x with the scalar one, PSNR equal or higher everywhere (D1 +7.7 dB).
