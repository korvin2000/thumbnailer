# Thumbnailer — architecture and wire contract

Album-centric organizer for studio photo galleries. One self-contained Spring Boot jar (Java 27, embedded Undertow) serves the compiled
Angular 22 UI, a WebSocket data channel and HTTP image endpoints. Albums live on disk as directories or archives;
metadata goes to an embedded SQL database (H2 by default), cover thumbnails (only those) to an embedded key-value store
(TuplDB by default). Both stores sit behind small domain ports and are swappable by configuration.

```
thumbnailer/
  backend/    Spring Boot app, package `thumbnailer`          (gradle :backend)
  frontend/   Angular 22 + SCSS, signals, zoneless            (gradle :frontend → dist copied into the jar)
  docs/       this file; map/ = file-level source maps (see INDEX.md)
```

`gradlew build` builds the UI (`npm ci && npm run build`), packages it under `static/` in the jar, runs tests.
`gradlew :backend:bootRun` starts the app at http://localhost:8080. `--thumbnailer.demo=true` generates sample albums.

## 1. Domain model

Primary entity is the **album** (a container of images), never a single photo.

| Album field | Type | Notes |
|---|---|---|
| `id` | string | random UUID, stable for the album's lifetime (survives moves and re-index) |
| `root` | string | scan root that owns it (absolute, normalized with `/`); informational: scopes, folders and the root filter select by `path` prefix |
| `path` | string | absolute path of the directory or archive file |
| `kind` | `DIRECTORY` \| `ZIP` \| `ARCHIVE` | container kind (`ARCHIVE` = any other archive 7-Zip reads: rar, 7z, tar, iso, tar.gz/bz2/xz/Z) |
| `name` | string | directory / archive base name (without `.tar.gz`-style double extensions), overridable by sidecar metadata |
| `cover` | string | entry name of the effective cover image |
| `coverPinned` | boolean | true when the user chose the cover (strategy chain no longer applies) |
| `revision` | long | incremented on every save; image URLs carry it as `?v=` so browser caches never serve a stale thumb |
| `coverPortrait` | boolean | cover is taller than wide (it fills a 9:16 tile); otherwise a 16:9 tile |
| `imageCount` | int | image entries in the container |
| `bytes` | long | sum of image entry sizes |
| `avgPixels` | long | mean `width*height` of the entries (quality measure, UI shows megapixels) |
| `maxWidth`, `maxHeight` | int | largest dimensions seen |
| `shotDate` | ISO date \| null | earliest EXIF `DateTimeOriginal`, else sidecar `date`, else earliest entry mtime |
| `modifiedAt` | ISO instant | newest entry mtime |
| `indexedAt` | ISO instant | last (re)index time |
| `model`, `studio`, `photographer`, `description` | string \| null | effective metadata (user override, else extracted) |
| `tags` | string[] | lower-case hashtags without `#` ("wedding", "outdoor", "sport", "advertising") |
| `rating` | int | 0 = unrated, 1..10 (user-only) |

Metadata has two layers stored side by side: `extracted` (rebuilt on every index) and `edited` (the user's overrides,
a partial `AlbumMeta` where `""` / `[]` are explicit empties). Effective = `edited` over `extracted`, field by field;
the effective values are what the columns, the search and the wire carry. Re-indexing therefore refreshes extracted
values without touching user edits, and a tag the user removed does not come back.

Entry (an image inside an album): `{ index, name, bytes, width, height, shotAt: ISO local datetime | null, flags }`.
`index` is the position in the container's sorted (by name, case-insensitive) image entry list; `flags` bit 0
`META_KNOWN`, bit 1 `META` (the file carries EXIF/XMP; 0 = not yet known, older stores).

### 1.1 Catalog (in progress, [catalog_plan.md](../catalog_plan.md) phase 0)

Models, studios, photographers, genres, hashtags and favorites lists are **entities** of one table, kind codes
`1 MODEL · 2 STUDIO · 3 PHOTOGRAPHER · 4 GENRE · 5 TAG · 6 LIST` (wire: the names). An entity: `{ id: int, kind, name,
rating 0..10, auto: boolean (created from text, unconfirmed), albums: int (visible linked albums, stored), profile:
JSON | null (card-only fields), photos?: int (lists only, computed) }`; every name it is known by is an `alias (kind,
norm → entity)` row, `norm` = trimmed, lower-case, whitespace collapsed, ≤ 256 chars (tags: `AlbumMeta.tag`). An
album's text fields (`model`, `studio`, `photographer`, `tags`, `genres`) stay the source of truth; on every save they
are resolved into `album_link (album seq, role = kind, entity, alias?)` rows (`Catalog.resolve`, roles 1–5
replaced, 6 untouched), which the wire carries as `links: [{ entity, role, name, alias? }]`; favorites links (6) come
only from `link/unlink`. Curated entity-to-entity facts are `entity_link (from, to, alias?, roles?)`. Photo marks:
`photo (id, album seq, name, rating)` + `photo_link (photo, entity)`. Albums carry an internal surrogate `seq`
(INT; link and mark tables key on it; a tombstone keeps it) and new fields `hidden` (safe-delete: excluded from
every default query and count, never from reconciliation), `bio` (entry name of a `*bio*.txt`/`*model*.txt`),
`genres: string[]`, `stamp` (= `contentId`), `copies` (albums sharing the stamp, itself included; per result page).
`shotDate` is the effective date: `extracted.date` (what the indexer computed) overridden by `edited.date`.
Tombstones (`removed`) are one per `(content_id, id)` and keep `seq` and `hidden`; `recall` returns the newest.
Counts: `entity.albums` is adjusted inside every album save's transaction (`JdbcAlbumRepository.save`: over all
roles, `(visibleAfter ? newLinks : ∅) − (visibleBefore ? oldLinks : ∅)`, `visibleBefore = false` for a new or
revived album; the resolution runs and the save commits under the catalog read lock, `Catalog.resolving`), by
`delete` (−1 per link of a visible album) and by favorites link changes that actually happened; suspended while a
scan holds the gate and rebuilt by `Catalog.recount()` at its end and on optimize.

Identity across scans (internal, not on the wire), both 64-bit wyhash (final 4) as 16 hex digits: `contentId` = hash of the
sorted image `(name, bytes)` list; `signature` = hash of `(name, bytes, mtime)` over **all** container entries, sidecar text files included, so a
metadata-only edit triggers a re-index. The stored signature ends in the key state of the album's encrypted `.cry` members (`+locked`,
or `+` and 8 hex digits of SHA-256 of the key; `+` alone when it has none, `Cry.signature`): a password change re-indexes exactly the
albums it affects, a missing key (`+locked` now, a key tag stored) leaves them untouched (`Cry.matches`), an archive without `.cry` files still rescans by `stat`, and a signature stored before `.cry` support re-indexes once. Identical copies present at two paths are two albums with their own edits. A
removed album leaves a tombstone under its `contentId`; the next new album with that content takes over its id, rating,
edits and pinned cover (revision + 1): an album moved out of a rescanned folder, restored from a backup or deleted and
copied back keeps its user state, and so does a copy of a deleted album.

## 2. Backend (package `thumbnailer`)

Framework-free domain in the middle, adapters at the edge. Package = feature, one owner per rule.

| Package | Responsibility |
|---|---|
| `album` | `Album`, `Entry`, `AlbumQuery` (with nested `Sort`), `AlbumPatch`, ports `AlbumRepository` (search/find/save/facets; `byRoot`/`stamps`/`folders` by path prefix; `delete` leaves a `Tombstone`, `recall(contentId)` peeks at it, `save` consumes the tombstones of the saved id in its transaction; `reroot`; `stats`/`compact`) and `BlobStore` (put/get/remove bytes by key, `stats`, `compact`). Pure records + interfaces. |
| `source` | Where albums live. `AlbumContainer` (list sorted image entries, open an entry stream, `head` = leading bytes, `read` = the whole entry in native arena memory (neither mapped nor a heap array: both lost throughput under parallel renders), `heads` = leading bytes of many entries in the order they lie on disk or in the archive (zip central directory, 7-Zip item index, a directory by name), `sequential()` = one entry costs a pass over the entries packed before it (solid 7z/RAR, a wrapped tar: read several only through `heads`), close) with `DirectoryContainer` (listed by a one-level walk, which takes size and mtime from the directory listing instead of one query per file on Windows), `ZipContainer` (JDK zip) and `SevenZipContainer` (7-Zip-JBinding: RAR, 7z, tar, ISO, wrapped tars; per-file extraction stopped after the asked bytes, one pass for the heads of a solid archive with each head probed inside it and heads ≤ 128 KiB kept for the metadata extractors, one listing pass with kept heads for a wrapped tar, a named file of a wrapped tar streamed to without a listing; 7-Zip writes from its own threads); archives' signature = their own size + mtime + exclusion globs, so an unchanged archive is never opened by a rescan; `ContainerFactory` SPI (`@Component`s, nested `*.Factory`) + registry `Containers` (`archive(path)` recognises archive files during discovery, `open(kind, path, exclusions)` returns every container wrapped by `Cry`); `Cry`: xanderView's encrypted `.cry` images (`int32 size | alg | AES-128/ECB(int16 name length − 32768 | zero-padded name | image)`, key = PBKDF2-SHA1 of the password with salt `z!a3`, 1024 rounds) decrypt transparently in `open/head/heads/read` with the key captured when the container opened (ECB: a head decrypts alone, no full read; whole files in 64 KiB chunks back into their native segment; never in place in arrays the inner container owns); without a key `.cry` files are not images; a wrong key fails the header check (`name length == ciphertext − 2 − image size`) and the file is skipped like an unreadable one; `keyTag()` feeds the stored signature; image extension policy; `Exclusions` (glob patterns with `*`, matched against the normalized absolute canonical path, e.g. `*/raw/*`, `*_tmp*`; `rebased` lets a root walked at a mapped location test its paths in canonical form); path helpers `Containers.normalize/prefix/within/rebase`. `Disks`: the physical disk behind a root (Windows: kernel32 over FFM, no admin rights — drive type, `StorageDeviceSeekPenaltyProperty`, device number; Linux: sysfs `queue/rotational`; macOS: `diskutil info` "Solid State"; network, unknown and failed queries = HDD; a root's `storage` setting overrides) and two FIFO lanes per disk, `SCAN` and `VIEW`: on an HDD `hdd-readers` (1) readers at once in arrival order and the scan gives way to the UI (it reads only after the UI has left the disk alone for 300 ms), on an SSD no gate. `cpuBound()` marks a compressed archive read in one pass: decompression, not the disk, limits it. |
| `image` | `ImageProbe` (width/height/orientation/EXIF date from a 128 KiB head without decoding: `Header` walks a JPEG's markers, a TIFF's IFD0, a PNG's IHDR + `eXIf`, a GIF's or BMP's header; WebP by its header; anything else, or facts past the head, through metadata-extractor + an ImageIO header read), `Thumbnailer.render(data, name, Render)` (from the entry read once into arena memory by the caller → JPEG: `TurboJpeg` (libjpeg-turbo over FFM) at the smallest DCT scale covering 1.5× the target, WebP: libwebp, else ImageIO subsampled to ≤ 2× → `Resampler` so the longer side = `size`, proportions kept, never upscaled → EXIF orientation by index remapping → libwebp or TurboJPEG encode; all stages share one `Pixels` layout, packed `0x??RRGGBB`) → `Thumb(bytes, format, epoch, portrait)`. Natives load through `natives.LibraryLoader` ([docs/natives.md](natives.md)). `Render(format, quality, size, threads)` is the spec (covers default WebP/75/512/4, thumbnails JPEG/85/512/0, 0 = CPU count); `epoch()` hashes format, size and quality (threads never change bytes) and keeps the pre-settings value for WebP/75/512 (`LEGACY_EPOCH`). `Limiter` is a fair semaphore resized from the settings before each acquire. |
| `metadata` | `MetadataExtractor` SPI (`extract(Extraction)`, `order()`) returning a partial `AlbumMeta`. `Extraction` is the context `AlbumIndexer` builds once per index and both chains share ([catalog_plan.md](../catalog_plan.md) §5): `name path kind images items longestDir` (an archive's longest inner directory name) `sampleImage` (median image name) `texts container`; `texts` = the `*.txt` members (`album.txt` first, then by name; ≤ 8 files, ≤ 64 KiB each, ≤ 256 KiB total), read in the same `heads` pass as the image heads and decoded by BOM, else strict UTF-8, else CP1251; `Extraction.of(entries)` (no container) outside indexing. `Metadata` runs the Spring beans and the `ServiceLoader` providers of the class path (`META-INF/services`; a public class with a no-argument constructor, not already a bean) sorted by `order()`; a provider that cannot load, construct or extract is skipped with a warning. Ordered composite where an earlier extractor's non-blank field wins and tags and genres are unioned. Built-ins (beans and providers): (1) sidecar text (`album.txt` or a single `*.txt` from `texts`, `album.json` through the container: `key: value` lines for name/model/studio/photographer/date/description, `#tag` tokens anywhere → tags); (2) **file name** — the real data looks like `CLS-2019-01-14 - EL-Studio - Ellena - New Model - (vol-1) 1800px (x133) 52.3Mb`: first `yyyy-MM-dd` → date; noise `\d+px`, `(x\d+)`, `[\d.]+ ?[MG]b` → dropped; `(vol-N)` / `VOL-N` → description "vol-N"; a leading code like `CLS` before the date → tag (lower-case); the remaining ` - ` separated parts: a part containing `studio`/`photo`/`lab` (case-insensitive) → studio, the first other part → model, the rest → description; `name` = base name without the noise; genres only from the words configured in `thumbnailer.genres` (comma-separated, none by default) that stand as whole words in the name; (3) EXIF (`DateTimeOriginal` → date, `Artist` → photographer). |
| `cover` | `CoverStrategy` SPI (`Optional<Entry> pick(Extraction)`, `order()`), collected like the extractors (beans + `ServiceLoader`, by order; one that throws is skipped): `NameHint` (1: `cover`, `poster`, `logo`, `_cv` suffix), `Smallest` (2: by bytes). `CoverSelector` yields candidates lazily — pinned cover (if it still exists), each strategy's pick, then every other entry — and the indexer renders the first readable one; `candidates(entries, pinned)` (saves and patches) wraps `Extraction.of`, so a strategy needing the container checks for null. |
| `index` | `AlbumDiscovery` walks a root (a directory holding ≥ `minImages` image files directly is an album; nested dirs are their own albums; a zip holding images is an album), skips exclusions. `AlbumIndexer` builds an `Album` from a container (probe entries with their `flags` from `Header.meta` — `META_KNOWN` once the head parsed, `META` with a JPEG APP1, TIFF IFDs or a PNG `eXIf`; the `Extraction` context; metadata; `bio` = the first `*.txt` by name whose file name contains `bio` or `model`; cover; entry list); when re-indexing it reuses the stored probe of every entry whose `(name, bytes, mtime)` is unchanged, so adding one photo to a 500-photo album probes one file, and the stored cover when its file is unchanged; cover candidates render through `AlbumService.renderCover` (bounded there). Scopes whose roots lie on different disks are walked at once (one walker per disk), scopes on one disk one after another. An album's reads happen in one turn on its disk's `SCAN` lane, from the listing through the probes and metadata to the first cover read (a `cpuBound()` archive gives the turn back after its listing, so others read while it decompresses; between head reads the scan gives way to the UI); the decode, later candidates (a new turn) and the commit come after, so on an HDD the next album is read while this one decodes. The probes the head cannot answer run in one more `heads` pass for a `sequential()` archive, per file otherwise. `Reconciler` compares the known state with the discovered one: unchanged (same path, same signature) → skip; changed (same path, new signature) → re-index keeping id and edits; moved or renamed (new path, `contentId` equals an album, in any root, whose old path no longer exists) → keep id and edits, re-extract metadata from the new name, bump revision; otherwise new (taking over a tombstone with the same `contentId`, see §1); known albums at or below the scope not seen → removed. **Removal happens only after a walk of that scope completed without cancellation or walk errors; an unreachable root (unplugged disk) is skipped entirely; an unreadable subfolder (access denied) is skipped without spoiling the walk, since it can never have been indexed.** A scan covers **scopes**: every root, or `scan(paths)` where each path is a root or a folder below one (else `error`); nested or repeated paths collapse to the outermost; a scope is spelled like its root and, below it, as the file system spells it when it exists (`toRealPath`, so `…/album` typed on a case-insensitive disk is the stored `…/Album`; a gone folder keeps the typed spelling); a scope = (owning root: exclusions and the albums' `root`; prefix); its walk starts at `locate(prefix)`, its stamps and its pruning select `path = prefix` or below it; a folder below a reachable root that is gone or excluded is an empty, complete walk (its albums go); `lastScanAt` is recorded only for whole roots. Every scan first runs `AlbumService.retainRoots`, so an interrupted settings save converges. `ScanService` runs scans asynchronously (discovery on one thread, one virtual thread per album, the walk waits while `index-threads` albums are in flight), publishes `ScanProgress`, supports cancel (cancelled workers publish nothing), one scan at a time. A scan takes one settings snapshot and binds each root to (canonical root, physical root = `locate(root)`): the walk and the containers use physical paths, a found album is stored at `canonicalRoot + physical.substring(physicalRoot.length)`, exclusions are matched in canonical space. |
| `album` (service) | `AlbumService` is the single owner of album mutation: commit of an indexed album (merges edits over extracted, bumps `revision`), user patches, removal, and event publishing after the store write. Per-album mutations are serialized so a scan cannot overwrite a concurrent edit. Covers are a regenerable cache: each stored value is tagged with the album state it shows (cover entry + signature + rendering), so a value of any other state is a miss and is re-rendered on request; entry thumbnails are rendered per request and never stored. Admission control lives here, as a producer-consumer pipeline per render kind (covers: indexing and HTTP cover misses share it; entry thumbnails): a file is read during a turn on its disk (the caller's `SCAN` turn, or a `VIEW` turn for HTTP) into a queue of `2 × threads` read files (`Limiter`), and decoded, resized and encoded by up to `threads` at once (`Limiter`), sized from `Settings.covers/thumbs.threads`; the turn ends with the read. Acquired only in the order turn → queue place → decode slot, so no holder waits for an earlier one. Entry thumbnails of an archive that reads only front to back (`sequential()`) come from one `Gallery` pass per album in packing order instead of a pass from the block start per thumbnail (Θ(N²) for a gallery): the pass is the reader of the same pipeline, runs while an asked entry is unread, otherwise stops 24 thumbnails ahead and frees its disk, and on its way to an asked entry renders no more than those 24 unasked ones (memory stays bounded whatever is asked first); a delivered thumbnail is forgotten (the browser caches it by revision), a later request for it renders alone. `commit` takes its prior state (id, revision, rating, edits, pin) from the stored album it matched or, for a new one, from a recalled tombstone; adoption is atomic with the save (the save consumes the tombstone, so a failed save keeps it), and a tombstone whose id is live again (a copy adopted it first) is ignored for a fresh id. `retainRoots(roots)`: a stored root that is no longer configured has its albums re-rooted to the configured root they lie in (widening `A:/pics/x` → `A:/pics` or narrowing keeps them, one `UPDATE`), the rest are removed. The tag is seeded by the epoch carried by the bytes (rendered or reused), never the spec current at commit time; reads validate against the current covers spec. |
| `store` | Adapters: `jdbc/JdbcAlbumRepository` (Spring `JdbcClient`, H2 SQL, dynamic WHERE from `AlbumQuery`, `schema.sql`; one index per sort order over a generated `sort_name`, so a page is an index scan; `MERGE` upserts; entries packed one value per album; tuned URL in `application.yml`: 128 MiB split files, LZF-compressed 64 KiB pages, 128 MB cache, 10 s chunk retention, 1.2 s write delay, close-time compaction; table `removed` = tombstones by `content_id`; index `album_path` serves the prefix selections `path = ? OR path LIKE 'dir/%' ESCAPE '!'`; `stats` = one aggregate query + `DISK_SPACE_USED` of the three tables (live, written pages only) + sizes of `albums.mv.db*`; `compact()` makes `close()` run `SHUTDOWN COMPACT` instead of `SHUTDOWN`), `tupl/TuplBlobStore` (default cover store: no redo log, checkpoint every second, idle stepped compaction, also on demand through `compact()`, `stats` = count, used pages, `tupl.db` size, self-heal), `mvstore/MvStoreBlobStore` (alternative; `stats` from fill rate, `compact` = `compactFile`). Selected via `thumbnailer.store.*` properties; another database = another adapter + JDBC URL, no domain change. |
| `settings` | **Libraries**: `thumbnailer.data-dir` is the *home*; it holds `libraries.json` `{active, known[]}` (`Libraries`: `switchTo` requires an absolute, writable dir whose `settings.json`, if present, parses as `Settings`; `forget` refuses the active one; `pinned` = `thumbnailer.library-pinned`, which `LibraryLocator` sets to whether `library-dir` came from the command line or environment; when pinned, `switchTo` and `forget` throw `Library is fixed by --thumbnailer.library-dir`). Every store (settings, H2 URL, covers) lives in `${thumbnailer.library-dir:${thumbnailer.data-dir}}`; `LibraryLocator` (an `ApplicationEnvironmentPreparedEvent` listener added by `ThumbnailerApplication.app()`) sets `library-dir` to the active library unless the command line or environment set it, and `library-pinned` either way. A switch or an optimize takes effect by an in-process restart: `ThumbnailerApplication.restart()` closes the context on a non-daemon `restart` thread (scans stop, stores close: H2 `SHUTDOWN [COMPACT]`, TuplDB `shutdown()`) and runs the application again with the same arguments; a restart asked for during start-up waits for it. If the new context fails to start, the previous library is written back as active and started once more (a second failure is logged and the process stays down). Once a switch or an optimize is accepted, `ScanService.freeze()` refuses every scan until the restart, and further switches/optimizes get `A restart is pending`. `Settings` record persisted as `settings.json` in the library dir; roots with per-root exclusions, global exclusions, scan-on-start, `pathMap` and the `covers`/`thumbs` render specs (absent fields of an older file → defaults). `locate(canonical)` is a pure function of the snapshot (longest `from` prefix, case-insensitive, stored case kept) and the only canonical → physical mapping: stored paths never change, every disk access (walk, render, original, reveal, moved/exists checks) goes through it. Validation: roots disjoint, no mapping `from` strictly inside a root. Root status (location, exists, album count, last scan) is derived at request time. |
| `web` | `ws/` protocol records (sealed `Request`/`Event`, Jackson `@JsonTypeInfo(use = NAME, property = "type")` with the lower-case names below), `AlbumSocket` handler + session hub (broadcast; every session wrapped in `ConcurrentWebSocketSessionDecorator` with a buffer limit so a slow client cannot block a scan), `ImageController` (HTTP image bytes; content type from the `Thumb`, ETag = `revision-epoch`), `SpaFallback` (SPA forward), `UndertowServer` (the embedded server: Spring Boot 4 ships only Tomcat/Jetty, so this `ServletWebServerFactory` runs Undertow EE — Servlet 6.1, Jakarta WebSocket 2.2 — on undertow-core 2.4; WS messages dispatch to worker threads; `AlbumSocket` sends 1001 on context close, since Undertow does not track sockets Spring upgraded), `ws/Browse` (one directory of the local file system for the folder picker: drives, visible readable subfolders), `ws/Library` (library info, switch, forget, optimize; refused while the gate (`index/Gate`: SCAN, OP, RESTART, SETTINGS) is held; a switch or optimize replies `ok`, then restarts after 300 ms), reveal folder (`AlbumSocket.reveal`: `explorer /select,` on Windows, else `java.awt.Desktop.open` of the parent). WS origins are restricted to `localhost`/`127.0.0.1`. The server binds to `127.0.0.1` by default (`server.address`); no authentication. |
| `demo` | `--thumbnailer.demo=true`: generates ~40 sample albums (dirs and zips, landscape/portrait covers, real-data-style file names plus a few sidecar files) under `<library-dir>/demo` and registers that root when none is configured. Used by the automated checks; real data lives in `C:/work/albums`. |

Configuration (`application.yml`, overridable by CLI/env):

```yaml
thumbnailer:
  data-dir: ${user.home}/.thumbnailer   # home: libraries.json + the default library
  # library-dir: <dir>                  # library in use: settings.json, albums.mv.db[.N.part] (H2), covers/ (TuplDB);
                                        # unset = active library of libraries.json, else data-dir; given = pinned
  min-images: 2                         # directory needs this many images to count as an album
  index-threads: 0                      # albums indexed at once per disk; 0 = available processors
  hdd-readers: 1                        # reads at once on a disk that pays for seeks (HDD, network, unknown)
  demo: false
server.port: 8080
```

Rendering and the path map are user settings in `settings.json` (Settings UI), not properties: `covers` and `thumbs`
(`Render {format, quality, size, threads}`, defaults WebP/75/512/4 and JPEG/85/512/0) and `pathMap` (`[{from, to}]`).

Concurrency: scans on threads owned by `ScanService`; WebSocket sends are serialized per session;
`BlobStore` and `AlbumRepository` implementations are thread-safe. Blob key: the album id; value: an 8-byte tag of
the album state (wyhash seeded by the render epoch of the bytes, over cover name and signature), then `[len 4][bytes]`;
the legacy spec (WebP/75/512) keeps the older `[tag 8][webp]` whose RIFF size plays the length, so stores written before
rendering was configurable stay valid. A late render, a lost write after a crash, a rolled-back save or a changed render
spec therefore can only leave a value whose tag no longer matches (a miss), never a stale hit; removal deletes the key.
Events are published after the store write, outside the per-album lock, and broadcast from a single sender thread.
TuplDB writes no redo log: writes become durable at the next checkpoint (every second) and `shutdown()` checkpoints;
freed pages are reused, and after 60 s without writes (or on `optimize`) a stepped `compactFile` runs when more than 5 % is free;
H2 rewrites its file only at close after `optimize` (`SHUTDOWN COMPACT` takes the database offline). Image decoding isolates corrupt entries (skipped
with a warning), applies EXIF orientation (including mirrored variants), decodes JPEG with libjpeg-turbo and WebP with libwebp, and
relies on TwelveMonkeys `imageio-jpeg` for what libjpeg-turbo refuses (CMYK/YCCK, 12-bit) and on ImageIO for the other
formats; `ImageIO.scanForPlugins()` runs at startup so plugins load from the fat jar.

## 3. Transport

**WebSocket `/ws`** carries JSON text frames. Every message has `type`. Requests carry a client-chosen `id`;
the response echoes it; broadcasts have no `id`.

Client → server:

| type | payload | reply |
|---|---|---|
| `search` | `query: AlbumQuery` | `results` |
| `album` | `albumId` | `albumDetails` |
| `update` | `albumId, patch: AlbumPatch` | `albumChanged` (also broadcast) |
| `scan` | `paths: string[] \| null` (null = all roots; each entry a root or a folder below one, nested entries fold into the outer) | `ok`, then `scanProgress` broadcasts (`root` = the scope being walked); `error` with the gate's blocker while anything else holds it (`A scan is running`, `An operation is running`, `A restart is pending`, `Settings are being saved`) or for a path outside every root (`Not inside an indexed folder: …`). Only albums at or below a completely walked path are pruned |
| `cancelScan` | – | `ok` |
| `getSettings` | – | `settings` |
| `setSettings` | `settings: Settings` | `settings`; `error` when two roots overlap, a `pathMap` `from` lies inside a root or a `Render` value is out of range, and the gate's blocker (`A scan is running`, …) when the root paths or the `pathMap` differ while the gate is held (such a save holds it as `SETTINGS`; nothing saved; exclusions, scan-on-start, render specs and the `.cry` password still save during a scan: a container decrypts with the key current when it was opened). Albums of roots that were removed move to a configured root that covers them, else are deleted (`albumRemoved` each) |
| `facets` | `query: AlbumQuery \| null` | `facets` |
| `reveal` | `albumId` | `ok` / `error` (opens the containing folder in the OS file manager, selecting the album) |
| `folders` | `path` | `folders` (child folders of a stored album path: the library tree, derived from album paths, not the disk) |
| `browse` | `path: string \| null` (null = user home) | `listing` (the local file system, for the folder picker) |
| `library` | – | `libraryInfo` |
| `switchLibrary` | `dir` (absolute; created if missing; must be writable; its `settings.json`, if any, must parse) | `ok`, then the server restarts into that library (sockets close; the client reconnects; if it cannot start, the previous library starts again); `error` while the gate is held (`A scan is running`, `An operation is running`, `A restart is pending`; the switch holds it as `SETTINGS` and hands it to `RESTART`), when pinned (`Library is fixed by --thumbnailer.library-dir`) or `Cannot read <dir>/settings.json: …` |
| `forgetLibrary` | `dir` | `libraryInfo`; `error` for the active library, while the gate is held (its blocker text) or when pinned (files stay on disk) |
| `optimize` | – | `ok` after the cover store compacted (and tombstones older than 365 days purged), then the server restarts and H2 runs `SHUTDOWN COMPACT` at close; `error` while a scan runs or when a restart is already pending (`A restart is pending`) |

Catalog, operations and photos (phase 0 of [catalog_plan.md](../catalog_plan.md): records, dispatch and the mock
exist; the server answers `error: not implemented` where the plan's track has not landed — noted per row):

| type | payload | reply |
|---|---|---|
| `entities` | `kind, text?: prefix over the normalized name or any alias, under?: int (entities linked to visible albums that entity is linked to; their `albums` = the albums shared with it), sort: 'NAME' \| 'COUNT' (albums desc, then name), offset, limit ≤ 500` (numbers are always sent) | `entityList { items: Entity[], total }` (counts exclude hidden) |
| `entity` | `entityId` (never `id`: that is the envelope's correlation id) | `entityCard { entity, aliases: string[] (the other normalized names, primary excluded), links: [{ to: Entity, alias?, roles? }], derived?: { albums, models?: Entity[], studios?: [{ entity, aliases: string[], genres: string[], albums }], photographers?: Entity[], genres?: Entity[], tags?: string[] } }` (`derived`: read only, from visible albums, the sections the kind shows: model → `studios` (per studio the aliases her links there carry, the other genres of those albums, their count), `photographers`, `genres`, `tags`; studio → `models`, `photographers`, `genres`; photographer → `models`, `studios`, `genres`; genre and tag → `models`, `studios`; list → `albums` only; related entities carry the albums they share with it as `albums`, most first, ≤ 100) |
| `saveEntity` | `entity: { id?, kind, name, rating, profile, aliases?: string[] (null keeps), links?: [{ to, alias?, roles? }] (null keeps) }` | `entityCard` (+ `catalogChanged` after every successful save, a rating-only one included; a rename keeps the old name as an alias; checked first, then written in one transaction: a refused or failed save changes nothing); `error` `"<name>" is <owner>; merge instead?` when the name or an alias belongs to another entity of the kind; self-links, unknown targets and a changed kind are refused |
| `deleteEntities` | `ids: int[]` | `ok` + `albumChanged` per album whose text named it (first every name resolving to it leaves the album's text, a patch each; lists: no text) + `catalogChanged` |
| `mergeEntities` | `from: int[], into: int` (same kind) | `entityCard` (into) + `catalogChanged` (one transaction: links that would collide and the links between them go, the rest, the names and the curated links move to `into`; album links keep a merged name as their `alias`; `into`'s profile wins and the merged ones fill its empty fields, rating = the highest, `auto` only if all were; texts untouched; `error` for another kind or nothing to merge) |
| `confirmEntities` | `ids: int[]` | `ok` + `catalogChanged` |
| `creditAs` | `modelId, studioId, alias, roles?` | `entityCard` (the model) + `albumChanged` per rewritten album (first the curated link model → studio gets `alias` and `roles` (absent keeps them) and the model the alias — `error` `"<alias>" is <owner>; merge instead?` when another model has it; then each album of hers at that studio has the names resolving to her rewritten to the alias, a patch each) |
| `bulkUpdate` | `albumIds: string[] \| query: AlbumQuery` (`albumIds` win; more than 500 matches → `error` `N albums match, at most 500 at once`, an unknown id → `No album <id>`, neither → `albumIds or query is required`), `patch: AlbumPatch` | `ok { count }` (every targeted album), then `albumChanged` each |
| `link` / `unlink` | `albumIds \| query, entityId` | `ok { count }` (albums actually changed); roles 1–5: the entity's name is added to the effective list / every name resolving to it removed from it (an empty list is the explicit `""`; tags through `addTags`/`removeTags`), a patch per album that changes, and the save resolves it; role 6: a plain link row, no save, no `revision` bump, one `catalogChanged { kinds: ['LIST'] }` when anything changed |
| `markPhoto` | `albumId, name, rating?, lists?: int[]` | `photoMarks { albumId, marks: { [name]: { rating, lists: int[] } } }` |
| `photos` | `listId, offset, limit` | `photoList { items: [{ albumId, name, index, rating, album: { id, name, revision } }], total }`: visible albums only, by album path then name, `limit` ≤ 500, `index` −1 for a marked file the album no longer lists |
| `photoMeta` | `albumId, index` | `photoMeta { rows: [{ dir, tag, value }] }`: metadata-extractor's directories and tags (`dir` e.g. `Exif IFD0`, `tag` e.g. `Artist`, `value` its description, ≤ 4096 chars) over the 128 KiB head, else the whole file; `error` when neither parses. What the read learned is stored as the entry's `flags` (`META_KNOWN`, plus `META` with an Exif or XMP block; `META_KNOWN` alone when neither parses) without a revision bump: a later `album`/`entries` reply carries them |
| `albumText` | `albumId, name` | `text { name, text }`: a `*.txt` member (else `error`), ≤ 256 KiB, decoded by BOM, else UTF-8, else CP1251 |
| `copies` | `albumId` | `copyList { albums: Album[] }` (every album with the same stamp, this one included) |
| `compareCopies` | `a, b` (album ids) | `comparison { identical, differing: string[] }` (every file of both albums as it is on disk, `.cry` as ciphertext: names in one only, other sizes or another SHA-256 of the streamed bytes differ; `error` `busy` while an operation holds either) |
| `preflight` | `op: Op` (`target` = destination parent folder for MOVE/COPY/FOLDER_MOVE/FOLDER_COPY, the new base name for FOLDER_RENAME; FOLDER_* act on `paths`; COPY_PHOTOS on `albumIds[0]` + `names`) | `preflight { items: [{ path, skip, reason?, note? }], bytes, free (-1 without a target), warnings: string[], consequences: string[] }`; items: one per target album, one per name for COPY_PHOTOS, one per folder for FOLDER_* (`note`: `N albums`); skip `reason`s: `inside another selected item`, `is a link`, `reached through a link`, `the target is reached through a link` (a symlink or junction between the root and the path), `a root folder`, `not in the library`, `the target is inside it` (by name or by real path), `already there`, `staging file exists: …` (a `.part` or 7z `.spill` where the item would write; never written over or deleted), `duplicate names in archive`, `exists at the target` (SKIP), `the archive exists`, `the folder exists`, `already packed`, `already unpacked`, `not an archive`, `already a zip`/`7z`, `would not be an album`, `already encrypted`, `not encrypted`, `needs the password`, `wrong password`, `unsafe entry name: …`, `not in the album`, `identical file exists`, `No space` (the need per volume + 64 MiB); `note`s: `contains N nested albums`, `folder keeps N sub-folders`, `becomes N albums`, `leaves the library`, `the result is a zip`; RENAME picks `name (n)`; `bytes` = what the items copy or move (REPACK and an archive's CRYPT: the members' listed sizes, what the output holds); `free` at the target's volume, else the first item's, -1 for renames and deletes; `consequences` in words (key fingerprint for CRYPT) |
| `op` | `op: Op` (same selection rules and errors as `bulkUpdate`; nothing to act on → `Nothing to do`) | `opAccepted { opId }`, then `opProgress` broadcasts (`id` = the operation id, a UUID; `done` counts successes; a cancel ends with `running: false, message: 'Cancelled'`) and one `opResult`; `error` from the gate (`A scan is running`, `An operation is running`, `A restart is pending`). Items run one at a time, planned again first (a reason → `skipped`); `skipped` also holds `in use` (5 × 200 ms retries, source untouched); `failed` holds errors, `…; finished on the next start` while the item stays journaled (recovered before the next operation or at start), `changed during the operation` (another program wrote to the source after it was copied: it is kept, the result removed), `The operations journal cannot be read` (every item, nothing touched, the journal left as it is); the final `opProgress` + `opResult` also follow an operation an `Error` stopped. Index effects: moves keep ids (`relocate`; outside every root → removed with a tombstone), a renamed last segment and every container change re-index with the asserted identity, copies are scanned as new albums, deletes leave tombstones (catalog_plan.md §4.5–§4.7) |
| `cancelOp` | – | `ok` |

Broadcasts: `catalogChanged { kinds: EntityKind[] }` (grid, viewer, trees, facets and open cards reload),
`opProgress { id, kind, running, total, done, current, skipped, failed, message, startedAt, finishedAt }` (≤ 4/s),
`opResult { id, done, skipped: [{ item, reason }], failed: [{ item, reason }] }`.

```ts
Op          { kind: 'MOVE'|'COPY'|'DELETE'|'PACK'|'UNPACK'|'REPACK'|'CRYPT'|'COPY_PHOTOS'|'FOLDER_RENAME'|'FOLDER_MOVE'|'FOLDER_COPY'|'FOLDER_DELETE';
              albumIds?: string[]; query?: AlbumQuery; paths?: string[]; target?: string; format?: 'AUTO'|'ZIP'|'SEVEN_ZIP';
              encrypt?: boolean; names?: string[]; conflict: 'SKIP'|'RENAME'; password?: string }
Entity      { id; kind: EntityKind; name; rating; auto; albums; profile?: string; photos?: number }   // profile absent when null; photos: lists only
EntityKind  'MODEL'|'STUDIO'|'PHOTOGRAPHER'|'GENRE'|'TAG'|'LIST'
Link        { entity: number; role: EntityKind; name: string; alias?: string }   // Album.links
```

Changed records: `Album` + `hidden, bio, stamp, genres, links, copies`; `albumDetails` + `marks` (as `photoMarks`);
`AlbumQuery` + `entities: int[]` (all linked), `missing: EntityKind` (no link of that role), `hidden: boolean` (null/
false = not hidden; true = only hidden), `duplicates: boolean`, `genres: string[]`; `AlbumPatch` + `date` (`YYYY-MM-DD`,
`YYYY-MM` or `YYYY` → the first day; `""` removes the override; else `error` `Date must be YYYY-MM-DD, YYYY-MM or YYYY: …`),
`genres`, `hidden`, `addTags`, `removeTags`; `Facets` + `genres` (all five counted from the text links of the matching albums: primary names, top 50 each; the
`model`/`studio`/`photographer`/`tags`/`genres` filters match any name of an entity, whole and normalized, a name no entity has matches nothing); `Settings` + `copyTargets:
string[]` (≤ 10, normalized, duplicates dropped) and `ops: { packFormat }` (default `AUTO`); `Entry` + `flags`;
`Album.links` includes favorites-list links (role `LIST`, `name` = the list), ordered by role code then name; every
album read carries `links` and `copies`. `markPhoto`: `lists` replaces the set, an absent `rating`/`lists` is
unchanged, rating 0 with no lists deletes the row; an unknown entry name or a non-list id is an `error`.
HTTP: `/img/{id}/…` answers `503 Retry-After: 2` while an operation holds the album.

Server → client:

| type | payload |
|---|---|
| `results` | `albums: Album[], total: int` (total = matches ignoring offset/limit) |
| `albumDetails` | `album: Album, entries: Entry[]` (entries capped at 5000) |
| `albumChanged` | `album: Album` |
| `albumRemoved` | `albumId` |
| `scanProgress` | `running, root, discovered, indexed, changed, removed, message, startedAt, finishedAt` (throttled, ≤ 4/s) |
| `settings` | `settings: Settings, dataDir, roots: [{ path, location, exists, albumCount, lastScanAt, storage }], renderEpoch` (`location` = where the root is after `pathMap`, `exists` tests it; `storage` = `SSD` or `HDD`, how its disk is read (detected, or the root's setting); `renderEpoch` = hex hash of the covers and thumbs epochs) |
| `facets` | `tags, models, studios, photographers: [{ value, count }]` (top 50 each, by count) |
| `folders` | `path, folders: Folder[]` |
| `listing` | `path, parent: string \| null, drives: string[], folders: [{ name, path }], error: string \| null` |
| `libraryInfo` | `home, dir, known: string[], pinned, stats: LibraryStats, about: { version, java, os }` |
| `ok` | – |
| `error` | `message` |

Live updates: the client re-runs its current search (trailing 600 ms, max wait 1.5 s, re-querying every loaded page
up to 1000) on `albumChanged` / `albumRemoved` / `scanProgress`, so results appear while a scan runs; responses of
superseded searches are dropped (generation counter in `AlbumStore`; the message `id` only correlates request and
reply); on reconnect it reloads settings and facets and re-runs the search. No server-side subscriptions. The client
currently requests `facets` unfiltered (global suggestions).

```ts
AlbumQuery {
  text?: string            // matches name, model, studio, photographer, description, tags (case-insensitive substring)
  tags?: string[]          // every tag must be present
  model?, studio?, photographer?: string   // exact, case-insensitive
  roots?: string[]         // limit to these scan roots (or path prefixes)
  kind?: 'DIRECTORY' | 'ZIP' | 'ARCHIVE'
  minBytes?, maxBytes?: number
  minCount?, maxCount?: number
  minMegapixels?, maxMegapixels?: number   // compared with avgPixels / 1e6
  dateFrom?, dateTo?: string               // ISO dates, inclusive, on shotDate
  minRating?, maxRating?: number
  sort: 'NEWEST' | 'OLDEST' | 'LARGEST' | 'SMALLEST' | 'MOST_PHOTOS' | 'FEWEST_PHOTOS' | 'BEST_QUALITY' | 'TOP_RATED' | 'NAME' | 'RECENTLY_INDEXED'
        | 'WORST_QUALITY' | 'LOWEST_RATED' | 'NAME_DESC' | 'FIRST_INDEXED'   // every field in both directions
  offset: number, limit: number            // limit ≤ 1000
}
AlbumPatch { name?, model?, studio?, photographer?, description?: string; tags?: string[]; rating?: number; cover?: string }
           // null/absent = unchanged; "" / [] = explicit empty override; cover: entry name pins it, "" unpins (strategies pick again)
Settings   { roots: [{ path: string, exclusions: string[], storage?: 'AUTO'|'SSD'|'HDD' }], exclusions: string[], scanOnStart: boolean;
             pathMap: Mapping[]; covers: Render; thumbs: Render;   // absent → [], {WEBP,75,512,4}, {JPEG,85,512,0}
             cryKey?: string | null; cryPassword?: string }        // the AES key (16 bytes hex) of xanderView's .cry images; the server derives it
                                                                   // from a cryPassword sent with setSettings and never stores or echoes the password;
                                                                   // the key lives in server memory only, never in settings.json: forgotten on
                                                                   // restart or 10 s after the last client disconnects; on the wire cryKey is
                                                                   // never the key but its 8-hex fingerprint (sent back, it keeps the session
                                                                   // key; a stale one is dropped); null = none
Mapping    { from: string; to: string }   // normalized dirs ending in '/'; stored paths under `from` are on disk under `to`
Render     { format: 'WEBP' | 'JPEG'; quality: number /*1..100*/; size: number /*64..4096, longer side*/; threads: number /*0..64, 0 = CPUs*/ }
Folder     { name; path; albums: number /*at or below*/; nested: boolean /*some strictly below*/ }
           // an archive album (ZIP, ARCHIVE) directly under the parent is not a folder; a DIRECTORY album at parent/name counts 1 + what lies below
Listing    // path = the directory shown (a file → its folder; a path that cannot be opened → nearest existing ancestor
           // or home, with error "Cannot open <path>"); folders: readable, not hidden, no dot names, sorted ignoring case, ≤ 2000;
           // drives = file system roots ('C:/'); parent of a drive = null
LibraryInfo  { home /*data-dir*/; dir /*library in use*/; known: string[] /*home, used ones, active*/; pinned /*dir given from outside: switch and forget are refused*/;
               stats: LibraryStats; about: { version /*jar Implementation-Version or 'dev'*/; java; os } }
LibraryStats { albums; images; imageBytes; lastIndexedAt: string | null; coversCount; coversBytes /*live*/; coversFile;
               metadataBytes /*H2 live, tables only*/; metadataFile /*albums.mv.db*/; settingsBytes }   // estimates
```

Absent/null fields in `AlbumQuery` mean "no filter"; absent/null fields in `AlbumPatch` mean "unchanged".
Secondary sort for every `Sort` is `name` ascending (`NAME_DESC`: name descending); `NEWEST`/`OLDEST` put albums without `shotDate` last.

**HTTP** (binary, cacheable: `Cache-Control: max-age=86400`, `ETag`; `r` is ignored by the server and only changes the
URL, so a changed render spec bypasses the browser cache):

| route | body |
|---|---|
| `GET /img/{albumId}/cover?v={revision}&r={renderEpoch}` | cover thumbnail, content type by the covers format (stored; re-rendered when its tag no longer matches); ETag `revision-epoch` |
| `GET /img/{albumId}/{index}/thumb?v={revision}&r={renderEpoch}` | entry thumbnail, content type by the thumbs format (rendered per request from the album, never stored); ETag `revision-epoch` |
| `GET /img/{albumId}/{index}?v={revision}` | original bytes, content type by extension |
| `GET /ws` | WebSocket |
| `GET /**` (no dot in last segment) | SPA `index.html` |

## 4. Frontend (Angular 22, standalone, signals, zoneless, SCSS)

```
src/app/
  app.ts  shell (topbar, sidebar, location bar + grid, details, statusbar with scan/operation progress, lazily loaded settings,
          dialogs mounted once: context menu, confirm, entity picker, folder picker, copies popover; result dialog; global keyboard)
  core/   model.ts (contract types above), gateway.ts (abstract Gateway: search/album/update/scan/settings/facets/reveal/
          folders/browse/library/switchLibrary/forgetLibrary/optimize, image URLs, `events`), ws-gateway.ts (reconnecting
          WebSocket, id correlation, `&r=renderEpoch` on image URLs), mock-gateway.ts (in-browser dummy albums with
          canvas-drawn covers and a fake disk for the picker; used with `?mock`, or in a dev build unless `?live`),
          store.ts (query/results/selection/settings/library signals), prefs.ts (persisted UI prefs incl. theme),
          catalog-store.ts / ops-store.ts (catalog and operation state beside the album store), selection.ts,
          folder-picker.ts (promise-style `pick()` service behind the folder dialog), toasts.ts, format.ts (pipes),
          layout.ts (bricks/grid layout engine), search-syntax.ts (`#tag`, `model:`/`studio:`/`by:`/`kind:` tokens)
  ui/     topbar, sidebar (left, overlay below 1100px; tabs Folders = folder-tree, Refine = filters-panel),
          folder-tree (lazy Explorer-like tree over `folders`), location-bar (above the grid: ↑, crumbs with
          children popovers, editable path, count, rescan of the shown folder), tile-grid (generic virtualized grid, also
          used by the viewer), album-card, details-panel (right: meta + edit; floating, inline ≥1400px, bottom sheet
          ≤760px), album-viewer (/album/:id, lazy: entries grid, lightbox, marks, copy to), photo-list (/list/:id, lazy: a
          favorites list's photos), actions (every menu/key action: bulk edits, preflight → Confirm → run), menus (pure menu
          builders per subject), op-confirm (pure operation → dialog mapping), shared (icon, rating, range, context-menu,
          confirm, entity-picker)
  ui/settings/  settings-dialog (native <dialog>, page rail, one draft Settings + Revert/Save footer), pages
          library (known libraries, switch/forget/add, composition donut + stats, optimize), folders (roots with
          Locate… → path-map rule, nested-root validation, exclusions, path mapping, scan), thumbnails (cover and
          thumbnail Render specs), appearance, keyboard, about; folder-picker (modal folder browser over `browse`)
styles/   _tokens (CSS variables, light/dark), _base (reset + shared classes .btn .input .chip .segmented .panel,
          settings page classes .card .hint .field .check)
```

File-level map: [docs/map/frontend.md](map/frontend.md); backend: [docs/map/backend.md](map/backend.md).

Layout engine (`layout.ts`): pure function `layout(tiles, { width, columns, gap, mode }) → { placements[], height }`.
Portrait and landscape tiles always have the same area (a portrait tile is the landscape tile rotated); empty space is
preferred over small thumbnails. Mode `bricks` fills landscape rows of `columns` tiles and collects portrait tiles into
their own rows holding as many as fit (scaled ≤ 15% to close the row), so the row kinds interlock without cropping and
the order shifts by at most one row per kind. Mode `grid` keeps strict order: rows of square cells, each tile fitted and
centred in its cell, rows as tall as their tallest tile. Placements are absolute (`x, y, w, h`), so the grid renders
only the tiles intersecting the viewport (virtual scrolling for thousands of albums).

Folder navigation: the folder tree (sidebar tab Folders) and the location bar both set `filters.roots = [path]`, which
the server and the mock apply as a path-prefix filter (the album's path equals `path` or starts with `path/`), so one
selected folder shows every album at or below it; `AlbumStore.folder` reads it back, `rootOf()` finds the configured
root it lies under, and `treeVersion` makes the tree reload its loaded nodes after a scan, a reconnect or a settings save.
