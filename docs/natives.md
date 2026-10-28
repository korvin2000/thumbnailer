# Native libraries

[↑ INDEX](../INDEX.md) · loader: [backend map → natives/](map/backend.md#natives-and-the-vendored-webp4j-surface)

Three natives do the hot work. They are packed in the jar under
[backend/src/main/resources/natives/](../backend/src/main/resources/natives/)`<os>-<arch>/` (file name =
`System.mapLibraryName(name)`) with a `natives.sha256` manifest, and copied once into a persistent cache by
`thumbnailer.natives.LibraryLoader` — no temp file per start.

| name | role | binding | source (owner's forks, AVX2 builds on x86_64) |
|---|---|---|---|
| `turbojpeg` | JPEG decode (DCT-scaled) and encode | FFM, the TurboJPEG 3 C API (`image/TurboJpeg`) | libjpeg-turbo 3.2.1, [korvin2000/libjpeg-turbo `Release-3.2.1-ci2`](https://github.com/korvin2000/libjpeg-turbo/releases/tag/Release-3.2.1-ci2) (Clang `-O3 -march=x86-64-v3 -flto` + PGO) |
| `webp4j` | WebP decode and encode | JNI, webp4j 2.5.0's library; its Java surface is vendored as `dev.matrixlab.webp4j` (the DLL exports only `Java_*` symbols, so FFM is not possible) | libwebp 1.6.0, [korvin2000/libwebp `Release-1.6.0-2.5.0-ci2`](https://github.com/korvin2000/libwebp/releases/tag/Release-1.6.0-2.5.0-ci2) |
| `7-Zip-JBinding` | RAR, 7z, tar, ISO, gzip/bzip2/xz/Z | JNI, 7-Zip-JBinding 26.03-2.3; Java API = [backend/libs/sevenzipjbinding-26.03-2.3.jar](../backend/libs/) (`lib/sevenzipjbinding.jar` of the release, sha256 `d544050a…b239a4`) | [korvin2000/sevenzipjbinding `Release-26.03-2.3-ci4`](https://github.com/korvin2000/sevenzipjbinding/releases/tag/Release-26.03-2.3-ci4) (7-Zip 26.03, Clang 23 avx2-lto) |

Platforms: `windows-x86_64`, `windows-aarch64`, `linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`
(about 29 MB together). The x86_64 builds need AVX2 (Haswell / Zen 1 or newer); Linux glibc ≥ 2.35. The files are the
ones verified in the owner's xview repository (`natives/<platform>.tsv`: release asset, member, SHA-256), renamed to
the load names; every manifest line was checked against those hashes when they were copied (2026-10-13).

## Loader

`LibraryLoader.path(name)` returns `<cache>/<os>-<arch>/<bundle>/<file>`, where `bundle` = the first 12 hex digits of
SHA-256 over the manifest text. A missing file is copied from the jar to a temp file, verified against the manifest
and moved into place atomically; a present one is hashed against the manifest on its first use per process (a damaged file is copied again, an unlisted one refused). Bundles never change, so versions
coexist and parallel processes never race. `load(name)` adds `System.load` (JNI); a file that fails to load and does
not match its hash is copied once more. Cache root: `-Dthumbnailer.natives=<dir>`, default `~/.thumbnailer/natives`;
the backend tests use `backend/build/natives`. [.gitattributes](../.gitattributes) keeps the manifests LF on every
checkout (their hash names the bundle) and the libraries binary.

## Updating a native

1. Put the new file into each `natives/<os>-<arch>/` under its load name.
2. Regenerate the manifest there: `sha256sum * > natives.sha256` minus the manifest's own line (Git Bash:
   `sha256sum 7-Zip-JBinding.dll turbojpeg.dll webp4j.dll > natives.sha256`).
3. For 7-Zip-JBinding also replace the Java jar in `backend/libs/` with the same release's `lib/sevenzipjbinding.jar`.
4. Run the backend tests (they load all three) and update the table above.
