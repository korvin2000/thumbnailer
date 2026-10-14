# How to use webp4j (WebP q75) and panamage-jxl (JPEG XL effort 3, q69) in a Java batch converter

This guide collects what the benchmarks in this project established, so you can build a reliable converter for more than 10,000 small images (covers and thumbnails, about 0.2 MP).

The code in section 5 was compiled and run against both libraries. It converted the test set with 0 failures and produced files byte-identical to the benchmark outputs.

Each statement carries one of three tags:

* **[verified]** was measured or tested here (Windows 10, JDK 26, Ryzen 5 5500U).
* **[source]** comes from reading the library source or README at the released tag.
* **[not tested]** is expected behaviour that was not checked here.

---

## 0. Quick reference

| | WebP: webp4j | JPEG XL: panamage-jxl |
|---|---|---|
| Gradle | `implementation("dev.matrixlab.webp4j:webp4j-core:2.5.0")` | `implementation("net.sourceforge.streamsupport:panamage-jxl-imageio:0.3.0")`<br>`runtimeOnly("net.sourceforge.streamsupport:panamage-jxl-natives-windows-x86_64:0.3.0")` |
| Native codec | libwebp 1.6 (JNI) | libjxl 0.12.0 (FFM / Panama) |
| Minimum Java | 8 | **22** (or the separate `panamage-jxl-jdk21` artifact) |
| JVM flag | `--enable-native-access=ALL-UNNAMED` | `--enable-native-access=ALL-UNNAMED` |
| Encode call | `WebPCodec.encodeImage(img, 75f, false, false)` → `byte[]` | Image I/O writer + `JxlImageWriteParam` with MODE_EXPLICIT, LOSSY, quality 0.69, effort 3, `JxlThreads.none()` |
| Result on the 17 test images (4,346 KB of JPEG input) | 400.5 KB, PSNR 34.04 dB, SSIMULACRA2 72.2 | 399.0 KB, PSNR 33.06 dB, SSIMULACRA2 73.2 |
| Encode speed, 1 thread | 61 img/s | 89 img/s |
| Full batch, 12 threads (read + decode JPEG + encode + write) | 303 img/s | 279 img/s |
| Java heap allocated per encode (0.2 MP) | 25 KB | 745 KB |
| Native memory, 12 parallel encodes | about +15 MB | about +220 MB |
| Temp-file leftovers | **1.2 MB DLL in `%TEMP%` per JVM start on Windows** | one persistent 4.8 MB cache directory, reused |
| Thread-safe | yes (static, stateless) | yes, with one `ImageWriter` per thread |
| Max image size | 16383 × 16383 | no practical limit (16384+ px wide verified) |
| Viewer support | universal | Chrome 155+ (Oct 2026), Safari 17+, Firefox pending; many tools can't open `.jxl` |

SSIMULACRA2 is a perceptual quality score: 100 = identical, about 90 = visually lossless, about 70 = medium quality. PSNR is a pixel-difference measure; higher is better.

---

## 1. Rules that apply to both libraries

### 1.1 Reading source images (`javax.imageio`)

* **`ImageIO.read(File)` returns `null`** when no reader recognises the file. Always null-check it. [verified by API contract]
* Corrupt files throw `IIOException`. CMYK/YCCK JPEGs are not supported by the JDK JPEG reader ("Unsupported Image Type"). [not tested]
* Typical decoded types [verified]:

  | source | decoded type |
  |---|---|
  | colour JPEG | `TYPE_3BYTE_BGR` |
  | grayscale JPEG | `TYPE_BYTE_GRAY` |
  | PNG with alpha | `TYPE_4BYTE_ABGR` |

* **EXIF orientation is NOT applied by `ImageIO.read`.** Phone photos tagged "rotate 90°" come out sideways, and neither encoder writes EXIF by default.
  * If your sources can carry an orientation tag, read it and rotate the pixels before encoding.
  * Or, for JXL only, carry the EXIF across (section 3.9). [not tested]
* Call **`ImageIO.setUseCache(false)`** once at startup. With caching on, stream-based Image I/O (`ImageIO.read(InputStream)`, `ImageIO.createImageOutputStream(OutputStream)`) creates temporary cache files in `java.io.tmpdir`. [source: JDK behaviour; the benchmark ran with caching off]
* JPEG decoding is a large share of the total time for small images. With 12 threads, webp4j encodes 435 img/s, but the full pipeline including JPEG decode runs at 303 img/s. [verified]

### 1.2 Threading

* Use **one fixed thread pool** with one image per task, sized `Runtime.getRuntime().availableProcessors()`.
* **Switch off each codec's internal threading** to avoid oversubscribing the CPU:
  * webp4j: `multiThreaded=false`
  * JXL: `JxlThreads.none()`
* Output is identical with internal threading on or off (17/17 files for both libraries), so this is purely a speed setting. [verified]
* JXL gains less from hyper-threading [verified]:

  | | 6 threads (= physical cores) | 12 threads |
  |---|---|---|
  | webp4j | 277 img/s | 435 img/s (+57 %) |
  | JXL effort 3 | 276 img/s | 350 img/s (+27 %) |

* Feed the pool **file by file**. Do not decode all images into memory first.
  * This is why webp4j's `WebPCodec.encodeImages(List<BufferedImage>, …)` / `BatchProcessor` is a bad fit for a 10k batch: it needs every image decoded up front.

### 1.3 Writing output files

* **Never write through `FileImageOutputStream` or `ImageIO.createImageOutputStream(File)` onto an existing file.** They do not truncate it, so if the old file was larger, its stale tail bytes remain and the file is corrupt or oversized.
  * Verified for the Image I/O writers of JXL, JDK JPEG and webp-imageio.
  * Note that panamage's own README example uses `ImageIO.createImageOutputStream(new File(...))`.
* Safe pattern used by the reference code [verified]:
  1. Encode to `byte[]`.
  2. `Files.write(tmp, bytes)` (this truncates).
  3. `Files.move(tmp, dst, REPLACE_EXISTING, ATOMIC_MOVE)`.

  A crash then never leaves a half-written `dst`, and re-runs can skip files that already exist.

### 1.4 Error handling

* Catch **`Exception`**, not just `IOException`, per file, and keep the batch going. Both libraries throw unchecked exceptions:
  * `IllegalArgumentException` for a null image
  * webp4j: `NativeLibraryNotFoundException`
  * panamage core API: `JxlException`
* Run a **startup self-test**: encode a 16×16 image. If the native library cannot load, you then fail once instead of 10,000 times.

### 1.5 JVM settings

* `--enable-native-access=ALL-UNNAMED` on the class path. On the module path, name the modules instead:
  * `dev.matrixlab.webp4j.core` for webp4j
  * `panamage.jxl` for JXL; the Image I/O plugin module is `panamage.jxl.imageio`

  Without the flag, JDK 22–26 prints a "restricted method" warning; a future JDK will block the call. [source]
* Heap: 256 MB is enough for 0.2 MP images with 12 threads, for both libraries. GC counts per 2,550-image batch:

  | | 1 GB heap | 256 MB heap |
  |---|---|---|
  | webp4j | 4 | 6 |
  | JXL effort 3 | 7 | 10 |

  [verified]
* For big photos, budget per worker thread:
  * Java heap: the decoded raster (W × H × 3 bytes; about 32 MB for 11 MP).
  * Native memory outside `-Xmx`: libwebp about 24 MB per 11 MP encode [verified]; libjxl considerably more (it works in float planes). [not measured at effort 3]

### 1.6 Colour and metadata

* Both encoders write **sRGB pixels and no EXIF/XMP** when used as shown.
* panamage keeps an ICC profile if the `BufferedImage` has an ICC colour space; see 3.6.
* Non-sRGB input, such as a linear-RGB `BufferedImage`, was converted correctly by both. [verified]

---

## 2. webp4j 2.5.0 (WebP, quality 75)

### 2.1 API

```java
byte[] webp = WebPCodec.encodeImage(image, 75f, false, false); // quality 0..100, lossless, multiThreaded
```

| overload | behaviour |
|---|---|
| `encodeImage(img, q)` / `encodeImage(img, q, lossless)` | **`multiThreaded = true`** (libwebp `thread_level=1`). Fine for one-off calls; **use the 4-argument form in a thread pool**. |
| `encodeImage(img, q, lossless, multiThreaded)` | full control |
| `encodeLosslessImage(img)` | lossless; the quality argument is ignored (it uses libwebp's lossless preset 70) [source] |
| `decodeImage(byte[])` | WebP → `BufferedImage` (useful for verification) |
| `getWebPInfo(byte[])` | `[width, height]`, a cheap validity check of the output [verified] |
| `isAvailable()` | whether the native library loaded on this platform [source] |
| `encodeImages(List, q, lossless)` / `BatchProcessor` | parallel bulk API that needs all images in memory; avoid for large batches |

### 2.2 Fixed encoder settings and what you cannot change

* The encoder is configured as `WebPConfigPreset(WEBP_PRESET_DEFAULT, quality)` [source]:
  * method 4
  * no sharp-YUV
  * no target size or target PSNR
  * `use_argb = 0` for lossy
* Its output was byte-identical to webp-imageio with libwebp's default config (`WebPConfigInit`, quality 75, method 4) on 17/17 images. [verified]
* **Not exposed:** method (effort 0–6), SNS, filter strength, sharp-YUV, segments, metadata (EXIF/ICC/XMP).
  * If you need those, webp-imageio exposes them, but it has serious bugs (appendix A).
* Output is deterministic: the same bytes every time, and the same with or without `multiThreaded`. [verified]

### 2.3 Input image types

| image | how it is passed to libwebp | verified result |
|---|---|---|
| `TYPE_3BYTE_BGR` (JPEG decode), standard raster | **zero-copy**: the backing `byte[]` goes straight into libwebp's BGR import | OK |
| `TYPE_INT_RGB` / `TYPE_INT_ARGB`, standard raster | **zero-copy** (BGRX/BGRA import) | OK |
| everything else: subimages (`getSubimage`), `INT_ARGB_PRE`, `4BYTE_ABGR`, gray, indexed, 565, custom colour spaces | normalised with `Graphics2D.drawImage` into a new `TYPE_INT_RGB`/`TYPE_INT_ARGB`; allocates W × H × 4 bytes per encode | OK; premultiplied, crops and linear-RGB all converted correctly |

* Alpha is detected with `ColorModel.hasAlpha()`.
  * Translucent pixels keep exact alpha (max alpha difference 0). [verified]
  * A fully opaque ARGB image produces the same size as an RGB one (libwebp drops the unused alpha). [verified]
* Dithered palette images (`TYPE_BYTE_INDEXED`) compress poorly in every lossy codec. This is a content property, not a bug.

### 2.4 Limits and errors [verified]

* **Maximum 16383 px per side.** Larger images throw `IOException("Lossy WebP encoding failed.")` with no reason given, so check the dimensions first and downscale or reject.
* `null` image: `IllegalArgumentException`.
* 1×1 and 48 MP (8000×6000, 2.4 s) both encode fine.
* Missing or unsupported platform: `NativeLibraryNotFoundException`, a `RuntimeException` thrown when the library is first used. Call `WebPCodec.isAvailable()` at startup.

### 2.5 Native library and **temporary-file leftovers** (important on Windows)

* On first use, the DLL is copied to `java.io.tmpdir` as `webp4j-<random>-webp4j-windows-x64.dll` (1.2 MB) and loaded from there. [source + verified]
* It is deleted by a JVM shutdown hook. **On Windows that deletion always fails**, because a loaded DLL is locked. **Every JVM start leaves one 1.2 MB DLL behind.** This benchmark accumulated 29 files, then 33, about 40 MB. [verified]
  * On Linux and macOS, deleting a loaded library normally works. [not tested]
* There is no property to relocate it other than `java.io.tmpdir`.
* Mitigations:
  * Run the whole batch in **one long-lived JVM**. This is also faster: the first call takes about 80 ms.
  * Start the JVM with `-Djava.io.tmpdir=<job-temp-dir>` and clean that directory at the start of the next run. Files from previous runs are no longer locked; the current run's file is.

    ```java
    try (var s = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "webp4j-*-webp4j-*")) {
        for (Path p : s) { try { Files.deleteIfExists(p); } catch (IOException locked) { /* in use by a running JVM */ } }
    }
    ```

### 2.6 Performance and memory (0.2 MP images) [verified]

* Speed:
  * encode only: 61 img/s on 1 thread, 435 img/s on 12 threads
  * full pipeline: 303 img/s on 12 threads
* Memory:
  * Heap: 25 KB per encode, plus the output `byte[]`.
  * Native: about 1 MB per concurrent encode.
  * No leak over 69,190 encodes; process memory stays flat.
* Interoperability: files decode identically with webp-imageio's decoder. They are standard WebP.

---

## 3. panamage-jxl 0.3.0 (JPEG XL, effort 3, quality 69)

### 3.1 Dependencies

* Add `panamage-jxl-imageio` plus the natives artifact for **each platform you deploy on**. Gradle then pulls in `panamage-jxl` (the core) and `panamage-jxl-spi`.
* The natives are found through a service interface. [source] Available platforms:

  | artifact | platform |
  |---|---|
  | `panamage-jxl-natives-windows-x86_64` | Windows x64 |
  | `panamage-jxl-natives-linux-x86_64` | Linux x64 (glibc) |
  | `panamage-jxl-natives-linux-aarch64` | Linux arm64 (glibc) |
  | `panamage-jxl-natives-linux-musl-x86_64` | Linux x64 (musl) |
  | `panamage-jxl-natives-linux-musl-aarch64` | Linux arm64 (musl) |
  | `panamage-jxl-natives-macos-aarch64` | macOS arm64 |

  There are **no Windows arm64 or macOS x64 natives**.
* On JDK 8–21, the plugin deregisters itself and `ImageIO.getImageWritersByFormatName("jxl")` returns nothing. [source]

### 3.2 Writer configuration: the order matters

```java
ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();     // one per thread
JxlImageWriteParam p = (JxlImageWriteParam) writer.getDefaultWriteParam();
p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);  // 1st: otherwise quality is ignored
p.setCompressionType(JxlImageWriteParam.LOSSY);
p.setCompressionQuality(0.69f);                      // 0..1 -> libjxl quality 69 -> Butteraugli distance 2.89
p.setEffort(3);                                      // 1..10, default 7
p.setThreads(JxlThreads.none());                     // default auto() = a native thread pool per image
```

* **Default trap.** A fresh param says quality 0.9 and effort 7, but its compression mode is `MODE_COPY_FROM_METADATA`, which encodes at **distance 1.0 (≈ q90), effort 7**. [source]
  * The same applies to `ImageIO.write(img, "jxl", file)`. Compared with effort 3 / q69, it produces files about 1.9× larger and runs about 7× slower. [verified]
* Calling `setCompressionQuality` before `setCompressionMode(MODE_EXPLICIT)` throws `IllegalStateException` (standard `ImageWriteParam` behaviour).
* Quality maps to Butteraugli distance through libjxl's `JxlEncoderDistanceFromQuality`. A higher distance means more compression. [verified]

  | quality | 66 | **69** | 73 | 75 | 90 |
  |---|---|---|---|---|---|
  | distance | 3.16 | **2.89** | 2.53 | 2.35 | 1.0 |

* Quality 1.0 (or `JxlImageWriteParam.LOSSLESS`) means lossless.
* Effort outside 1–10 throws `IllegalArgumentException`.
* `ImageWriter` instances are not thread-safe. Keep one per worker thread (`ThreadLocal`) and call `setOutput(null)` after each write.
  * 12 threads × 340 encodes with per-thread writers produced 0 mismatches. [verified]

### 3.3 Choosing the effort [verified on the test set, size-matched to WebP q75]

| effort | 1-thread img/s | quality at WebP's file size (SSIMULACRA2, WebP = 72.2) | notes |
|---|---|---|---|
| 1 / 2 | 97 / 101 | 71.6 (slightly *below* WebP) | identical files to each other; same pixels as effort 3 but about 5 % larger |
| **3** | **91** | **73.2** | best speed/quality point; ~4 % smaller than WebP at equal perceptual quality |
| 4 | 86 | 73.3 | ≈ effort 3 |
| 5 | 26 | 74.4 | perceptual tuning starts here; −8 % size at equal quality |
| 6 | 16 | – | |
| 7 (default) | 13 (21 with `JxlThreads.auto()`) | 74.8 | −10 % size at equal quality |
| 8 / 9 | about 3 / 2 | ≈ effort 7 | not worth it |

* At effort 3, PSNR is about 1 dB below WebP at the same size. JXL smooths fine noise, which PSNR penalises.
* JXL's real advantage appears at **high quality**. At WebP-q90-equivalent quality, JXL files are 22–25 % smaller.
* The q69 calibration matches WebP q75's size **on these 17 images**. Recheck on a sample of your own content: about 50 images, comparing the total size.

### 3.4 Core API alternative (no Image I/O)

```java
// pixels must be packed RGB (R,G,B order) - a TYPE_3BYTE_BGR raster is B,G,R in memory: swap bytes 0 and 2
byte[] jxl = JxlEncoder.encode(new JxlImage.Uint8(w, h, 3, rgb),
        JxlEncodeOptions.ofQuality(69).withEffort(3).withThreads(JxlThreads.none()));
```

* The output is byte-identical to the Image I/O path and about 3 % faster. [verified]
  * The plugin's own pixel conversion costs only about 0.4 ms per 0.2 MP image.
* There are streaming overloads that write to an `OutputStream` or `WritableByteChannel`, and `ofDistance(2.89f)` sets the distance directly. [source]
* Channel counts for `JxlImage.Uint8`: 1 = gray, 2 = gray + alpha, 3 = RGB, 4 = RGBA. [source]
* Errors throw `JxlException`, which is **unchecked**. The Image I/O writer wraps it in `IIOException`. [source]

### 3.5 Input image types [verified]

* Every type tested encoded correctly, alpha exact:
  * `3BYTE_BGR`, `INT_RGB`, `INT_BGR`, `INT_ARGB`, `INT_ARGB_PRE` (un-premultiplied correctly), `4BYTE_ABGR`
  * gray 8/16-bit, `BYTE_BINARY`, `USHORT_565`, linear RGB
  * `getSubimage` crops, 1×1, 16383×4, 16384×4, 48 MP

  (48 MP at effort 7 took 17 s, against 2.4 s for webp4j.)
* How the plugin converts pixels [source]:
  * sRGB, gray or ICC `ComponentColorModel` images at 8 bits, 16 bits or float are copied exactly. 16-bit precision is kept.
  * Everything else goes through `getRGB` and becomes 8-bit sRGB.
  * The pixels are then copied again into native memory. That is 2 copies, plus libjxl's float working buffers.

### 3.6 Colour [source]

* `BufferedImage`s with a non-sRGB ICC colour space keep their ICC profile in the `.jxl` file.
* The panamage reader converts back to sRGB by default (`setConvertToSrgb(false)` keeps the original space).
* Linear-RGB input round-tripped correctly. [verified]

### 3.7 Native libraries and temp/cache files [verified]

* On first use, 6 DLLs (`jxl`, `jxl_cms`, `jxl_threads`, `brotlicommon`, `brotlidec`, `brotlienc`; 4.8 MB in total) are extracted to `%TEMP%\panamage-jxl-<user>\windows-x86_64-0.12.0-<content-hash>\`.
* The directory is **reused** by every later run. After dozens of JVM runs there was still exactly one directory.
* It is **never deleted**: it is a persistent cache, and it does not grow.
* Properties [source]:
  * `-Dpanamage.jxl.cache.dir=<dir>` moves the cache.
  * `-Dpanamage.jxl.library.path=<dir>` loads libjxl from an existing directory instead of extracting. When set, it is the only location searched.
* First call (library load + first encode): about 270–390 ms. Use a long-lived JVM.

### 3.8 Performance and memory (0.2 MP images) [verified]

* Speed:
  * encode only: 89 img/s on 1 thread, 276 img/s on 6 threads, 350 img/s on 12 threads
  * full pipeline: 279 img/s on 12 threads
* Batch latency p50 / p95: 46 / 74 ms (webp4j: 42 / 62 ms).
* Memory:
  * Heap: about 745 KB per encode (a pixel copy plus output). GC impact is minor (7 GCs per 2,550 images).
  * Native: **about 18–20 MB per concurrent encode, even for 0.2 MP images** (+220 MB at 12 threads). Leave that headroom outside `-Xmx`.
* No native leak: process memory stays flat during batches and returns to baseline afterwards.

### 3.9 Extra features [source unless marked]

* **Metadata:** write the `IIOImage` from `jpegReader.readAll(0, null)` to carry EXIF/XMP across.
  * EXIF orientation stays consistent: viewers rotate correctly without the pixels being rotated. [not tested]
  * Remember that EXIF can contain GPS data. Strip it if privacy matters.
* **Lossless JPEG→JXL repacking:** `JxlTranscoder.fromJpeg(bytes, options)`, reversed by `JxlTranscoder.toJpeg`.
  * The original JPEG came back bit-exact on 17/17 files, but this saved only **9 %** on these JPEGs, at about 33 ms per image. [verified]
  * Useful for archiving, not for reaching WebP-like sizes.
* Also available: lossless mode, 16-bit and float HDR, animations (including GIF → JXL), `JxlLimits` decode limits (256 MP and 16 MiB of metadata by default), and an Image I/O reader (`ImageIO.read` works on `.jxl`).

### 3.10 Risks

* **Maturity:** version 0.3.0, single maintainer, repository created 2026-09-30, 0 stars at the time of testing. It worked flawlessly in every test here.
* **Viewer support:** JPEG XL is on by default only in Chrome 155+ (announced 2026-10-06) and Safari 17+; Firefox support is expected but unconfirmed. Many image viewers, CMSs and tools can't open `.jxl`. Keep a WebP or JPEG fallback if the files are served to browsers.
* **Verification gap:** only libjxl's own decoder was used for checking; independent decoders such as Chrome's jxl-rs were not tested.

---

## 4. Which one for this batch

**webp4j q75.**

* It is faster in the real 12-thread batch (303 vs 279 img/s).
* It uses about 15× less native memory, needs Java 8 instead of 22, every viewer supports its output, and the library is more mature.

JPEG XL effort 3 / q69 is a sound alternative only if your consumers support `.jxl`. It gives about 1 SSIMULACRA2 point better quality at the same size, but not a meaningful size saving at this quality level.

---

## 5. Reference converter (compiled and run against both libraries)

```java
import dev.matrixlab.webp4j.WebPCodec;
import panamage.jxl.JxlThreads;
import panamage.jxl.imageio.JxlImageWriteParam;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Converts every JPEG/PNG below a directory to WebP (webp4j, q75) or JPEG XL (panamage-jxl, effort 3, q69).
 * Usage: java --enable-native-access=ALL-UNNAMED -cp "lib/*" BatchConverter.java <inDir> <outDir> webp|jxl
 */
public final class BatchConverter {

    enum Format { WEBP, JXL }

    /** WebP's hard limit per side; libwebp fails with a generic error above it. */
    private static final int WEBP_MAX_DIMENSION = 16383;

    // ---- WebP (webp4j): static, stateless, thread-safe ------------------------------------------------------

    static byte[] encodeWebp(BufferedImage img) throws IOException {
        if (img.getWidth() > WEBP_MAX_DIMENSION || img.getHeight() > WEBP_MAX_DIMENSION) {
            throw new IOException("Too large for WebP: " + img.getWidth() + "x" + img.getHeight());
        }
        // quality 75, lossy, multiThreaded=false: the pool already keeps every core busy.
        return WebPCodec.encodeImage(img, 75f, false, false);
    }

    // ---- JPEG XL (panamage-jxl Image I/O plugin): one writer + param per thread --------------------------------

    private static final ThreadLocal<ImageWriter> JXL_WRITER =
            ThreadLocal.withInitial(() -> ImageIO.getImageWritersByFormatName("jxl").next());

    private static final ThreadLocal<JxlImageWriteParam> JXL_PARAM = ThreadLocal.withInitial(() -> {
        JxlImageWriteParam p = (JxlImageWriteParam) JXL_WRITER.get().getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); // must come first, otherwise defaults (q90/e7) apply
        p.setCompressionType(JxlImageWriteParam.LOSSY);
        p.setCompressionQuality(0.69f);                      // -> libjxl distance 2.89 (~ WebP q75 size)
        p.setEffort(3);                                      // default 7 is ~7x slower
        p.setThreads(JxlThreads.none());                     // encode on the calling (pool) thread
        return p;
    });

    static byte[] encodeJxl(BufferedImage img) throws IOException {
        ImageWriter writer = JXL_WRITER.get();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, null), JXL_PARAM.get());
        } finally {
            writer.setOutput(null);
        }
        return bytes.toByteArray();
    }

    // ---- pipeline ---------------------------------------------------------------------------------------------

    static void convert(Path src, Path dst, Format format) throws IOException {
        BufferedImage img = ImageIO.read(src.toFile());
        if (img == null) {
            throw new IOException("No Image I/O reader for this file");
        }
        byte[] data = format == Format.WEBP ? encodeWebp(img) : encodeJxl(img);
        Files.createDirectories(dst.getParent());
        Path part = dst.resolveSibling(dst.getFileName() + ".part");
        Files.write(part, data); // truncates, unlike FileImageOutputStream
        Files.move(part, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Fail fast if the native library cannot load on this machine, instead of failing 10,000 times. */
    static void selfTest(Format format) throws IOException {
        BufferedImage probe = new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR);
        if (format == Format.WEBP) {
            if (!WebPCodec.isAvailable()) {
                throw new IllegalStateException("webp4j native library not available on this platform");
            }
            int[] size = WebPCodec.getWebPInfo(encodeWebp(probe));
            if (size[0] != 16 || size[1] != 16) throw new IllegalStateException("WebP self-test failed");
        } else {
            if (!ImageIO.getImageWritersByFormatName("jxl").hasNext()) {
                throw new IllegalStateException("No JPEG XL Image I/O writer (panamage-jxl-imageio missing or JDK < 22)");
            }
            if (encodeJxl(probe).length == 0) throw new IllegalStateException("JPEG XL self-test failed");
        }
    }

    public static void main(String[] args) throws Exception {
        Path inDir = Path.of(args[0]).toAbsolutePath();
        Path outDir = Path.of(args[1]).toAbsolutePath();
        Format format = Format.valueOf(args[2].toUpperCase(Locale.ROOT));
        String ext = format == Format.WEBP ? ".webp" : ".jxl";

        ImageIO.setUseCache(false); // no temp cache files for stream-based Image I/O
        selfTest(format);

        List<Path> files;
        try (Stream<Path> s = Files.walk(inDir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png)"))
                    .toList();
        }

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger(), failed = new AtomicInteger();
        long start = System.nanoTime();
        for (Path src : files) {
            String name = inDir.relativize(src).toString().replaceFirst("\\.[^.\\\\/]+$", ext);
            Path dst = outDir.resolve(name);
            pool.execute(() -> {
                try {
                    convert(src, dst, format);
                    ok.incrementAndGet();
                } catch (Exception e) { // includes unchecked native/library errors; keep the batch going
                    failed.incrementAndGet();
                    System.err.println("FAILED " + src + ": " + e);
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(7, TimeUnit.DAYS);
        double secs = (System.nanoTime() - start) / 1e9;
        System.out.printf("%s: %d converted, %d failed, %.1f s (%.0f img/s)%n", format, ok.get(), failed.get(), secs,
                ok.get() / secs);
    }
}
```

Run it:

```bash
java --enable-native-access=ALL-UNNAMED -Djava.io.tmpdir=C:/jobs/webp-tmp -Xmx256m -cp "lib/*" BatchConverter.java A:/tests out/webp webp
```

* The `java.io.tmpdir` directory must already exist, because webp4j's `Files.createTempFile` fails otherwise.
* On the 17 test images it printed `WEBP: 17 converted, 0 failed` and `JXL: 17 converted, 0 failed`.
* The output was byte-identical to the benchmark files and left no `.part` files. [verified]

Production additions to consider:

* skip files whose `dst` already exists, so the batch can resume
* apply EXIF orientation (section 1.1)
* resize first if your targets are thumbnails
* an optional per-file check (`WebPCodec.getWebPInfo` or a decode)
* a bounded task queue if the file list is huge
* the webp4j temp-DLL cleanup from section 2.5

---

## 6. Pre-flight checklist

- [ ] One long-lived JVM; `--enable-native-access=ALL-UNNAMED`; `ImageIO.setUseCache(false)`.
- [ ] Startup self-test passes (native library loads).
- [ ] Fixed pool with `availableProcessors()` threads; webp4j `multiThreaded=false`; JXL `JxlThreads.none()`.
- [ ] webp4j: the 4-argument `encodeImage(img, 75f, false, false)`; images over 16383 px rejected or downscaled.
- [ ] JXL: `MODE_EXPLICIT` set before quality; `LOSSY`; 0.69f; effort 3; one writer per thread.
- [ ] `ImageIO.read` null-checked; catch `Exception` per file; failures logged and counted.
- [ ] Output written as `byte[]` → temp file → atomic move. Never `FileImageOutputStream` onto an existing file.
- [ ] EXIF orientation handled if the sources need it.
- [ ] Windows + webp4j: `-Djava.io.tmpdir=<job dir>` plus cleanup of `webp4j-*-webp4j-*.dll` leftovers.
- [ ] JXL: native memory headroom of about 20 MB per thread outside `-Xmx`; natives jar for every deployment platform; JDK 22+.
- [ ] Re-calibrate the quality value on a sample of your own images if a size target matters.

---

## Appendix A: why not webp-imageio 0.11.0

These were found in this project's first round of tests. [verified]

* **Wrong pixels, silently:**
  * images cropped with `getSubimage` (PSNR 20 dB instead of 40 dB)
  * `TYPE_INT_ARGB_PRE` images (colours wrong, PSNR 14 dB)
* **Poor failure behaviour:** a bare `NullPointerException` on encoder failure (for example over 16383 px), which a `catch (IOException)` misses; `TYPE_USHORT_565_RGB` is rejected.
* **Memory:** two full pixel copies per encode (1.3 MB of heap per 0.2 MP image). With 12 threads on 11 MP photos it ran out of heap at ≤ 768 MB, where webp4j was fine at 512 MB.
* **Plus side:** it exposes every libwebp option (method, sharp-YUV, target size, and so on), cleans up its temp DLLs, and its defaults equal q75 / method 4.

## Appendix B: measurement notes

* Machine: Ryzen 5 5500U (6 cores / 12 threads), Windows 10, Temurin JDK 26, G1 GC.
* Throughput numbers vary about ±10–15 % between runs.
* SSIMULACRA2 was computed by a Java port of libjxl's reference implementation (`src/main/java/bench/Ssimulacra2.java`), with a Gaussian-blur approximation. Scores may deviate slightly from the reference tool, equally for all codecs.
* Full data: `REPORT.md`, `REPORT-JXL.md`, `results/*.csv`. Converted samples: `out/quality/`.
