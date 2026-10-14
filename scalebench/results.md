# scalebench results (2026-10-14, JDK 27, AMD Ryzen 5 5500U 6C/12T Zen 2, AVX2, Windows 10)

[↑ README](README.md)

## Quality

PSNR/SSIM on luma vs the float64 Lanczos3; zone plate MTF (1 = ideal) / alias (0 = ideal). `Production` = `thumbnailer.image.Resampler` compiled from the backend source.

| scaler | D1 6000x4000->512x341 PSNR/SSIM | D2 750x500->512x341 PSNR/SSIM | D3 1500x1000->512x341 PSNR/SSIM | D4 3000x2000->1600x1067 PSNR/SSIM | D5 4000x3000->512x384 PSNR/SSIM | D6 6001x4001->512x341 PSNR/SSIM | U1 400x300->1600x1200 PSNR/SSIM | U2 1000x667->1500x1000 PSNR/SSIM | zone 4096->512 MTF/alias | zone 1024->700 MTF/alias |
|---|---|---|---|---|---|---|---|---|---|---|
| Current | 43.46/0.9973 | 48.51/0.9967 | 52.32/0.9983 | 46.60/0.9975 | 45.48/0.9980 | 43.44/0.9973 | n/a | n/a | 0.984/0.028 | 0.984/0.250 |
| AwtBilinear | 23.51/0.8287 | 39.18/0.9735 | 35.55/0.9178 | 38.31/0.9596 | 26.90/0.8695 | 23.55/0.8288 | 30.12/0.7484 | 33.96/0.8415 | 0.998/0.549 | 0.954/0.558 |
| AwtBicubic | 23.21/0.8069 | 37.31/0.9561 | 33.90/0.8770 | 36.28/0.9319 | 26.37/0.8411 | 23.25/0.8069 | 30.26/0.7515 | 34.52/0.8567 | 1.000/0.710 | 0.994/0.731 |
| NobelMultistep | 34.44/0.9811 | 39.18/0.9735 | 42.45/0.9849 | 38.31/0.9596 | 34.87/0.9805 | 34.27/0.9798 | 30.12/0.7484 | 33.96/0.8415 | 0.950/0.084 | 0.954/0.558 |
| NobelLanczos3 | 44.76/0.9982 | 34.89/0.9417 | 43.20/0.9907 | 34.53/0.9730 | 43.96/0.9975 | 44.75/0.9982 | 28.93/0.7308 | 30.94/0.7680 | 1.008/0.025 | 1.009/0.087 |
| NobelBicubic | 41.27/0.9958 | 34.59/0.9381 | 43.13/0.9902 | 34.64/0.9731 | 41.89/0.9959 | 41.26/0.9958 | 28.99/0.7315 | 31.07/0.7704 | 0.983/0.028 | 0.983/0.224 |
| Fable | 43.69/0.9974 | 48.74/0.9970 | 52.61/0.9985 | 46.84/0.9979 | 45.86/0.9981 | 43.60/0.9973 | 30.27/0.7516 | 34.53/0.8570 | 0.985/0.048 | 0.984/0.250 |
| Opus | 53.59/0.9995 | 48.74/0.9970 | 52.61/0.9985 | 46.84/0.9979 | 58.43/0.9996 | 53.32/0.9995 | 30.27/0.7516 | 34.53/0.8570 | 0.997/0.046 | 0.984/0.250 |
| Astra | 51.12/0.9993 | 49.17/0.9971 | 52.70/0.9984 | 46.93/0.9978 | 46.11/0.9982 | 44.03/0.9977 | 30.27/0.7515 | 34.55/0.8573 | 1.010/0.048 | 0.990/0.247 |
| Sol | 43.31/0.9973 | 48.39/0.9967 | 52.24/0.9983 | 46.58/0.9975 | 45.37/0.9980 | 43.29/0.9973 | 30.27/0.7513 | 34.52/0.8568 | 0.984/0.028 | 0.984/0.250 |
| Production | 51.12/0.9993 | 49.17/0.9971 | 52.70/0.9984 | 46.93/0.9978 | 48.13/0.9983 | 43.80/0.9976 | 30.27/0.7515 | 34.55/0.8573 | 1.010/0.048 | 0.990/0.247 |

## Speed (ms, median/best; one JVM per scaler, vector path)

| scaler | D1 6000x4000->512x341 ms med/best | D2 750x500->512x341 ms med/best | D3 1500x1000->512x341 ms med/best | D4 3000x2000->1600x1067 ms med/best | D5 4000x3000->512x384 ms med/best | D6 6001x4001->512x341 ms med/best | U1 400x300->1600x1200 ms med/best | U2 1000x667->1500x1000 ms med/best |
|---|---|---|---|---|---|---|---|---|
| Current | 88.39/85.04 | 6.11/6.06 | 13.48/13.36 | 81.48/81.05 | 55.57/55.30 | 85.07/84.70 | n/a | n/a |
| AwtBilinear | 2.33/2.26 | 1.84/1.78 | 1.96/1.90 | 18.00/17.78 | 2.42/2.28 | 2.37/2.24 | 19.86/19.72 | 15.50/15.35 |
| AwtBicubic | 7.03/6.90 | 6.34/6.24 | 6.41/6.35 | 61.63/61.48 | 7.61/7.53 | 6.96/6.87 | 68.90/68.65 | 54.03/53.65 |
| NobelMultistep | 85.98/84.89 | 1.84/1.78 | 5.91/5.78 | 17.92/17.75 | 41.86/41.65 | 85.07/84.20 | 19.85/19.71 | 15.51/15.39 |
| NobelLanczos3 | 343.11/337.89 | 16.32/16.22 | 35.90/35.54 | 214.59/211.69 | 189.55/187.15 | 345.57/342.44 | 90.42/89.33 | 86.05/85.02 |
| Fable | 11.50/11.21 | 1.01/0.94 | 2.16/2.12 | 12.00/11.88 | 9.13/8.95 | 10.25/10.09 | 5.49/5.38 | 5.62/5.53 |
| Opus | 10.29/10.14 | 1.01/0.98 | 2.71/2.64 | 14.19/14.02 | 11.10/10.98 | 15.89/15.71 | 4.35/4.26 | 4.42/4.35 |
| Astra | 12.63/12.20 | 0.83/0.81 | 2.14/2.11 | 9.82/9.71 | 18.87/18.50 | 44.62/43.60 | 14.46/14.27 | 11.99/11.73 |
| Sol | 50.54/49.59 | 1.64/1.59 | 3.73/3.64 | 20.89/20.77 | 21.25/21.05 | 57.38/55.85 | 5.43/5.27 | 10.75/10.50 |
| Production | 12.44/12.18 | 0.85/0.83 | 2.13/2.10 | 10.05/9.98 | 6.49/6.35 | 12.15/11.97 | 5.06/4.95 | 4.64/4.56 |

Scalar paths (no `--add-modules jdk.incubator.vector`), ms median: Production D1 38.5, D2 3.06, D3 4.64, D4 35.4, D5 21.1, D6 38.9, U1 23.9, U2 19.9 (same bits as the vector path); Opus D1 49.9, D2 2.64, D3 5.83; Astra D1 27.7, D2 3.24, D3 4.80; Fable D1 54.3, D2 7.40, D3 17.0; Current D1 84.8, D2 6.12, D3 12.9.

## Render pipeline (backend `RenderBench`, synthetic camera JPEGs 24 MP / 12 MP, q90, 512 px)

| pipeline | probe ms/img | WebP cover ms/img | JPEG thumb ms/img | covers/s, 12 threads |
|---|---|---|---|---|
| before (ImageIO + metadata-extractor probe, subsampled ImageIO decode, old Resampler, webp4j stock, ImageIO JPEG) — directory | 1.25 | 239.4 | 228.2 | 36.8 |
| before — stored zip | 0.60 | 238.2 | 229.1 | 37.8 |
| after (marker-walk probe, TurboJPEG DCT 1/4, this Resampler, AVX2 libwebp / TurboJPEG encode) — directory | 0.11 | 71 | 57.5 | 119–122 |
| after — stored zip | 0.09 | 72 | 57 | 107–124 |

Directory reads went from `FileChannel.map` (76 covers/s: page faults scale badly in parallel on Windows) and zip
reads from exact-size heap arrays (81–92: G1 humongous allocations) to native arena memory (~105). Dropping the
`critical` TurboJPEG downcalls (pixels decoded into the heap array) for native buffers plus one bulk copy cost 5 ms
per render alone but raised the 12-thread rate to ~120: a critical decode holds off every other thread's safepoint.

## DCT scale of the JPEG decode (24 MP → 512 px, PSNR vs ImageIO full decode + float Lanczos3)

| decode | decoded | then | PSNR | decode ms | resize ms |
|---|---|---|---|---|---|
| 1/8 | 750x500 | cubic | 38.42 | 49.9 | 1.9 |
| 1/4 | 1500x1000 | cubic | 42.91 | 56.7 | 2.7 |
| **1/4** | 1500x1000 | **Lanczos3 (`boxed`)** | **50.78** | 56.7 | 3.8 |
| 1/2 | 3000x2000 | box 2 + Lanczos3 | 50.57 | 64.3 | 8.0 |
| 1/1 | 6000x4000 | box 4 + Lanczos3 | 49.30 | 76.7 | 13.2 |

12 MP (4000x3000): 1/4 + Lanczos3 48.66 dB in 32 + 2.2 ms; 1/2 48.43 dB in 36 + 4.2 ms. Entropy decoding dominates,
so smaller DCT scales save little; non-power-of-two scales (3/8 ...) have no SIMD IDCT and decode slower than 1/2.
`Thumbnailer.GAP` = 1.5 (decode to ≥ 1.5× the target) picks 1/4 for both.
