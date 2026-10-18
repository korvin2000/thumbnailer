# Thumbnailer

Album-centric organizer for studio photo galleries: albums are folders or archives of photos (zip, rar, 7z, tar, iso,
tar.gz/bz2/xz/Z); each one gets a
cover thumbnail, extracted metadata (model, studio, photographer, date, tags…), a rating and editable
notes. Search is live, incremental scanning keeps the index current, and the whole thing ships as one jar. Built for
libraries of hundreds of thousands of albums: only covers are stored (WebP, 512 px, about 12 KiB each); every other
image is read from the folders and archives while browsing.

- **Backend**: Java 27, Spring Boot 4.1 on Undertow 2.4 — WebSocket for data, HTTP for images, H2 (SQL) for metadata, TuplDB as the
  cover key-value store, native codecs on the hot path (libjpeg-turbo over FFM, libwebp through webp4j, 7-Zip through
  7-Zip-JBinding: AVX2 builds, see [docs/natives.md](docs/natives.md)), hash4j wyhash for signatures. Architecture and wire contract: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- **Frontend**: Angular 22, signals, zoneless, SCSS. Virtualized brick/grid layout, slide-out filter panel, floating
  details panel, album viewer with lightbox and cover selection, light/dark themes, keyboard driven.

## Build and run

Requires JDK 27 on `JAVA_HOME` (ships under `devtools/` in this workspace; Gradle 9.8.1 via the wrapper) and Node.js 22+ with npm installed globally
(OS level, on the system `PATH`; `node`, `npm`, `npx` are called directly by the build).

```bash
./gradlew build                      # UI (npm ci + ng build) + backend + tests → backend/build/libs/thumbnailer-*.jar
java --add-modules jdk.incubator.vector -jar backend/build/libs/thumbnailer-0.1.0.jar      # http://localhost:8080
java --add-modules jdk.incubator.vector -jar backend/build/libs/thumbnailer-0.1.0.jar --thumbnailer.demo=true   # sample albums
```

Useful switches (`application.yml` keys, overridable as `--key=value` or environment variables):

| key | default | meaning |
|---|---|---|
| `thumbnailer.data-dir` | `~/.thumbnailer` | home: `libraries.json` (known libraries, the active one) and the default library |
| `thumbnailer.library-dir` | active library of `libraries.json`, else the data dir | the library in use: settings.json, H2 database, cover store (`covers/`); giving it pins that library |
| `thumbnailer.index-threads` | `0` (= CPU count) | albums indexed at once per disk (roots on different disks are scanned at once) |
| `thumbnailer.hdd-readers` | `1` | reads at once on a disk that pays for seeks (HDD, network or unknown drive; detected per folder, overridable in Settings → Folders); an SSD is read without limit; on a hard disk a scan pauses while the UI reads it |
| `thumbnailer.store.blobs` | `tupl` | cover store: `tupl` or `mvstore` |
| `thumbnailer.store.tupl.cache-mb` | `128` | TuplDB page cache (off-heap, allocated at start) |
| `thumbnailer.min-images` | `2` | images a folder needs to count as an album |
| `thumbnailer.demo` | `false` | generate sample albums under the library dir |
| `thumbnailer.genres` | (none) | comma-separated genre words the file-name parser takes from an album name when they stand as whole words |
| `server.port` / `server.address` | `8080` / `127.0.0.1` | bind address |

Folders to scan (picked in a folder browser), exclusions (`*` globs), scan-on-start, path mapping for a moved library and
cover/thumbnail rendering are configured in the UI (Settings → Folders, Settings → Thumbnails) and stored in
`settings.json` in the library dir. The library itself (metadata database, cover store, settings.json) is chosen,
inspected and optimized in Settings → Library; several libraries can be kept and switched.

**Encrypted images.** xanderView's `.cry` files (AES-encrypted images, also inside archives) are read when their
password (the contents of xanderView's key file) is entered in Settings → Folders → Encrypted images. Only the key
derived from it is kept, in memory only: it is forgotten on restart or 10 s after the last browser tab closes, never
written to `settings.json`. Albums holding `.cry` files appear or change on the next scan after the password is set or
changed; without it new `.cry` files are ignored, while albums already indexed stay, their images unreadable until the
password is entered again.

**Libraries.** A library is a folder holding settings.json, the database and the cover store. `libraries.json` in the
data dir remembers the known libraries and the active one. Switching a library, or Optimize (cover store compaction,
then a full database compaction while it closes), restarts the application inside the same process: open windows
reconnect by themselves after a few seconds. Neither runs while a scan is running. A `--thumbnailer.library-dir=…`
argument (or environment variable) pins the library; a switch made in the UI then takes effect only once it is removed.

Native code (TuplDB, libjpeg-turbo, libwebp, 7-Zip) needs `--enable-native-access=ALL-UNNAMED`; the jar manifest,
`bootRun` and the tests set it. `--add-modules jdk.incubator.vector` turns on the SIMD resampler (a jar manifest cannot;
without it the scalar path runs: same pixels, 2-3x slower resizing). SIMD also needs the C2 JIT: under `-XX:TieredStopAtLevel=1` or `-Xint` the Vector API boxes every vector (100x slower), so the scalar path runs there too; `bootRun` therefore turns off Spring Boot's optimized launch, which adds that flag. The natives ship inside the jar and are unpacked once per version into
`~/.thumbnailer/natives` (`-Dthumbnailer.natives=<dir>` moves that cache); x86_64 needs an AVX2 CPU. Archives rescan by
file size and date: the first scan after upgrading re-lists every zip album once (photos are not decoded again). Upgrading from a version with `thumbs.mv`: that file is no longer used and can be deleted, and the first
scan re-indexes every album once (new signature hash; stored photo facts are migrated, so photos are not re-probed);
ratings and edits are kept, but do not move albums before that scan has run, or they are treated as new.

## Operations

Move, copy, delete, pack/unpack/repack, encrypt/decrypt `.cry`, copy photos and the folder operations change files in
the library, one operation at a time (never during a scan), each after a confirm dialog that lists every item and what happens to it.
In the UI they sit in the right-click menus: an album tile (or the selection, also from the ▾ of the "N selected" chip), a folder
of the Browse tree (its own rename/move/copy/delete and "Albums below ▸"), an entity row ("Its albums ▸"); the same menus assign
models, studios, photographers, genres, tags, ratings, shot dates and favorites lists in bulk, and hide/unhide albums. The dialog
shows the preflight (what runs, what is skipped and why, bytes and free space, consequences) and the conflict policy (rename or
skip); encryption asks for the password twice unless the session key is set under Settings → Folders. The status bar shows the
running operation with Cancel; a result with skipped or failed items opens as a dialog. Copy targets and the default pack
format live under Settings → Operations. A favorites list's photos open as their own grid ("Show photos" on the list).
A source is deleted only after its result was written beside it (`<name>.part`), forced to disk and read back equal
byte for byte; only Delete and Delete folder remove files on purpose, and they say so in their dialog. There is no
Recycle Bin: deletion is final (hiding an album, *safe-delete*, touches no file). Every step is journaled in the
library folder (`ops.journal`, manifests under `ops/`) before it runs; after a crash the next start finishes or rolls
back each unfinished item before any scan, so a source or its verified result always survives. Albums keep their
identity (id, rating, edits, pinned cover, photo marks) through every operation.

## Development

```bash
./gradlew -PskipFrontend :backend:bootRun     # backend only, no UI build
npm --prefix frontend start                    # Angular dev server on :4200, proxies /ws and /img to :8080
```

The dev server defaults to an **in-browser mock dataset** (`?mock`), so the UI can be developed without the backend;
open `http://localhost:4200/?live` to talk to a running backend. Frontend unit tests: `npm --prefix frontend test`.

## Keyboard

`/` search · `E` folders tab · `F` refine tab · `I` details panel · `T` theme · `[` `]` albums per row · arrows move
(also in the folder tree: `→` expand, `←` collapse) · `Shift`+arrows / `Shift`+click extend the selection, `Ctrl`+click or
`Ctrl+Space` toggle, `Ctrl+A` select all (≤ 500) · `Shift+F10` or right-click album menu (also on folders and entity rows) · `H` hide · `Delete` delete… · `Enter` open ·
`C` use photo as cover · `Esc` clear the selection, close

Viewer: `F11` full screen · `Space` slideshow · `S` scale mode · `R` reshuffle · `1`–`9` `0` copy to target · `Ctrl+1…0` rate · `F` favorites ·
`M` metadata · `B` bio · `C` cover · `Ctrl`/`Shift`+click, `Ctrl+A` select photos · `Esc` close
