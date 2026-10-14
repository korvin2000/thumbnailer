# PLAN — state, next steps, decisions

[↑ INDEX](INDEX.md) · requirements: [SPEC.md](SPEC.md)

Living document: update **State** and tick items when work lands; add a **Decisions** row when a choice is costly to
undo. Reference SPEC IDs.

## State (2026-10-14, v0.1.0, single initial commit + uncommitted tree)

- 2026-10-14 native pipeline: archives RAR/7z/tar/ISO/tar.gz·bz2·xz·Z through 7-Zip-JBinding 26.03 (`Kind.ARCHIVE`,
  `SevenZipContainer`), JPEG through libjpeg-turbo 3.2.1 over FFM (DCT-scaled decode), WebP through webp4j's JNI library
  on libwebp 1.6.0 (AVX2 builds of the owner's forks, [docs/natives.md](docs/natives.md)), loaded by
  `natives.LibraryLoader` from a persistent cache (port of xview's); marker-walk JPEG probe instead of
  metadata-extractor + ImageIO; one `Pixels` layout from decode to encode; archives rescan by `stat`; new
  `Resampler` from a 4-model competition ([scalebench](scalebench/README.md)). Render per 24 MP JPEG 239 → 71 ms,
  probe 1.25 → 0.11 ms, 12-thread throughput 37 → ~120 covers/s ([results](scalebench/results.md)). Checks:
  `:backend:test` 54/54 (+1 env-gated bench), frontend `ng test` and `ng build`.

- Feature-complete against the brief except the ◐/○ rows in SPEC (C2, M5, Q5, N6).
- 2026-10-12 settings/main-screen redesign (next_goal.md): settings dialog with rail pages (Library · Folders · Thumbnails · Appearance · Keyboard · About) and a folder picker over `browse`; several libraries (`libraries.json`, `thumbnailer.library-dir`, in-process restart on switch/optimize); library stats + optimize; path map for moved libraries; covers/thumbs rendering in settings.json (WebP/JPEG, quality, size, threads); Explorer-like folder tree + location bar with per-folder rescan (scan scopes, tombstones so pruning never loses edits).- Checks green: backend `:backend:test` 47/47 (11 classes), full `./gradlew build`; frontend `ng test` 15/15 (3 specs), `ng build` clean (initial bundle ≈ 436 kB raw / 112 kB transfer, budget 600 kB); live smoke run of the packaged jar (`backend-demo` preview): tree/crumbs/rescan, settings pages, picker, library switch and optimize restarts, JPEG thumbs / WebP covers.
- Checks green: backend `:backend:test` 47/47 (11 classes), full `./gradlew build`; frontend `ng test` 15/15 (3 specs), `ng build` clean (initial bundle ≈ 436 kB raw / 112 kB transfer, budget 600 kB); live smoke run of the packaged jar (`backend-demo` preview): tree/crumbs/rescan, settings pages, picker, library switch and optimize restarts, JPEG thumbs / WebP covers.
- 2026-10-13 toolchain: Java 27 (toolchain + `devtools/jdk-27`), Gradle wrapper 9.8.1 (official Java 27 support);
  Tomcat replaced by Undertow 2.4.4 (`web/UndertowServer`). Checks: `:backend:test` 47/47, full `./gradlew build`,
  kvbench compiles; packaged-jar smoke run (UI, images, WS, 1001 on two optimize restarts, no JDK warnings).
- Thumbnail store evaluation done: [kvbench](kvbench/README.md) measured 14 stores at 100 k values (7.6 GiB) and the
  two finalists at 400 k values (30 GiB). The verdict is in [docs/kvstore-evaluation.md](docs/kvstore-evaluation.md):
  **TuplDB** replaces MVStore (owner decision 2026-10-12: plain TuplDB without page checksums; licence irrelevant).
- 2026-10-12 scale pass (target > 100 k albums): TuplDB is the default cover store (NO_REDO validated in kvbench); only
  covers are stored (512 px WebP q75 via webp4j, 33 → 12 KiB each on the sample library), entry thumbnails render per
  request; wyhash signatures; packed entries (one row per album); slim reconcile; virtual-thread indexing with
  semaphores; one index per sort order (a sorted page went from 8.2 s to a few ms at 100 k albums).

## Next (ordered)

1. **C2/N6 pluggable cover strategies** — `CoverSelector` takes ordered `List<CoverStrategy>` beans instead of the
   hardcoded `List.of(NAME_HINT, SMALLEST)`; keep "pinned first, then all entries" fallback.
2. **Test gaps (backend)** — `ZipContainer`/`Containers` (nested entries, exclusions), `AlbumDiscovery`
   (min-images, AccessDenied, incomplete walk), `ImageController` (ETag/404/original stream, WebP thumbs), `MvStoreBlobStore`, `SettingsStore` round trip, WS `update`/`facets`/`scan`/`reveal`.
3. **Test gaps (frontend)** — `AlbumStore` (generation guard, `loadMore` identity check, refresh max-wait),
   `MockGateway.matches/comparator` parity with server semantics, `Prefs` persistence.
4. **M5 editable shot date** — add `date` to `AlbumPatch` → `AlbumMeta` edited layer → details panel.
5. **Q5 contextual facets** — call `gw.facets(filters)` so suggestions narrow to the current query (protocol and
   `WsGateway` already support it; the store calls it unfiltered).
6. ~~More containers~~ — done 2026-10-14 through 7-Zip-JBinding (`SevenZipContainer`); open: multi-volume archives
   (`.part1.rar`, `.7z.001`), encrypted ones, keeping an opened archive across image requests (each `/img` request
   opens it again: cheap for zip/7z headers, a pass for a wrapped tar).

## Tech debt / hygiene

- `frontend/tsconfig.json` has no `"strict": true`; enabling it needs a pass over nullability.
- `.prettierrc` says `printWidth: 100` but the code is hand-formatted dense (lines up to ~200); decide one way before
  ever running Prettier on whole files.
- `frontend/.vscode/launch.json` "ng test" targets the Karma debug page (stale for Vitest).
- Scale follow-ups (2026-10-12 review): pass the discovery walk's file attributes to the containers instead of listing
  each album again; `count` runs on every page request (the wire could send it for the first page only); model/studio/
  photographer filters use `LOWER(col) =` and so scan (generated lower-case columns + indexes if they get slow).
- Tombstones (`removed`) are never purged; `removed_at` is there for an age limit if the table ever matters.
- Upgrade caveat: albums moved before the first scan of the new version are treated as new (their old content id is a
  SHA-1, the new one a wyhash), so their edits would be pruned with the old row.
- Entry `index` = position among *readable* images; an image that becomes unreadable shifts later indices (and their
  thumbnail URLs) on the next re-index. Acceptable now; revisit if entry ids must be stable.

## Open questions

- Should the dev UI fall back to the mock automatically when no backend answers? (Today: dev = mock unless `?live`;
  prod = live unless `?mock`.)

## Decisions (why things are the way they are)

| decision | reason |
|---|---|
| Hot path native: libjpeg-turbo over FFM, libwebp via webp4j JNI (its DLL exports only `Java_*`), 7-Zip-JBinding JNI; zip stays on the JDK | FFM where the library has a C API, JNI where it does not; the JDK's zip is as fast for stored JPEGs and keeps its tested path |
| Natives packed in the jar per platform, copied once into `~/.thumbnailer/natives/<platform>/<bundle>` (`LibraryLoader`) | no temp file per start (webp4j's extraction left undeletable DLLs on Windows), versions coexist, one `stat` per start |
| Archive signature = archive size + mtime + exclusion globs | a rescan of 100 k unchanged archives opens none; the first scan after the upgrade re-lists zip albums once (probes and covers are reused) |
| JPEG decoded at the smallest power-of-two DCT scale ≥ 1.5× the target, finished by a Lanczos3 (`boxed`) | 1/4 + Lanczos3 = 50.8 dB at 57 ms vs a full decode 49.3 dB at 77 ms; 3/8, 5/8 have no SIMD IDCT ([results](scalebench/results.md)) |
| Resampler: gpt-6-astra's Q7 SIMD kernels + Fable/Opus power-of-two box, SIMD and scalar paths with the same bits | best weighted speed of four candidates and the best scalar fallback; the app may run without `--add-modules jdk.incubator.vector` |
| Render epoch unchanged by the new decoder/resampler | same format, size and quality: stored covers stay valid instead of 100 k re-renders; browsers keep cached thumbs until the next revision |
| H2 (SQL) for metadata, a KV store for covers, both behind ports | embedded, file-based; ports keep engines swappable |
| Covers in TuplDB, plain, no page checksums, NO_REDO (1 s checkpoints), idle stepped compaction | NO_REDO: 386 MB/s load, no redo growth, crash runs clean (kvbench, [evaluation](docs/kvstore-evaluation.md)). | kvbench: 1.2 % space after load and about 2 % after compaction, against MVStore's 13–44 %; ≥ 250 MB/s writes. Thumbnails are a regenerable cache, so space and speed beat checksums. JE reads faster but keeps about 10 % dead space under churn. |
| WebSocket for data/events, HTTP for image bytes | async push + id-correlated requests; images stay cacheable by the browser |
| Embedded Undertow (Undertow EE 2.0.3 on undertow-core 2.4.4) instead of Tomcat, via our own `web/UndertowServer` | owner choice (2026-10-13: performance, WebSocket support). Boot 4 dropped Undertow (no Servlet 6.1 upstream) and the community starter is unreleased, so ~150 lines of factory cover what the app uses (no SSL, HTTP/2, compression, access log). `SHUTDOWN_TIMEOUT` 0, else a restart waits for open sockets, then `stop` awaits the interrupted workers ≤ 5 s (as Tomcat's executor) so no handler outlives the stores; a bounded shared direct buffer pool, since Undertow's default frees overflow buffers with `Unsafe.invokeCleaner` (JDK 27 warns; `deny` would fail each free) |
| Two metadata layers (`extracted`, `edited`), effective = edited over extracted | re-index refreshes facts without losing user edits; explicit empties stick |
| `revision` bumps on every save and is part of image URLs and ETags | no stale images in the browser |
| Only covers are stored, keyed by album id, value tagged with the album state (cover, signature, render epoch) | one value per album (no prefix scans, no copies on edits); a lost write, crash, late render or rolled-back save can only cause a miss, never a stale hit (Codex review) |
| Covers and thumbnails 512 px longer side, proportional, WebP q75 (webp4j), antialiased bicubic from a ≤ 2× subsampled decode | owner brief; −64 % bytes per cover vs 640 px JPEG; decode dominates the time, so subsampling matters more than the filter |
| Entry thumbnails rendered per request, never stored | the store holds one small value per album at any library size; browsers cache by revision |
| wyhash final4 (hash4j) for signatures, content ids and cover tags | owner brief; fast, 64-bit is ample for identity at 10^5–10^7 items |
| Entries packed into one value per album (`EntryCodec`) | 10 M images would be 10 M rows plus index; packed ≈ 26 B per image |
| Indexing: a virtual thread per album, `Semaphore(index-threads)` on the walk; renders bounded by two `Limiter`s in `AlbumService` (covers: indexing + HTTP cover misses; entry thumbnails), sized from `Settings.covers/thumbs.threads` | I/O-bound probing runs wide, CPU-bound decode/encode stays bounded; no list of 100 k futures |
| One H2 index per sort order (generated `sort_name`) and MERGE upserts | without them every page sorted all rows and spilled to disk (8.2 s at 100 k albums; now ≈ 2 ms) |
| H2 URL: `split:27:` (128 MiB files), `COMPRESS=TRUE`, `RETENTION_TIME=10000`, `MAX_COMPACT_TIME=5000`, `WRITE_DELAY=1200`, `CACHE_SIZE=131072`, `QUERY_CACHE_SIZE=64`, `MAX_LENGTH_INPLACE_LOB=4096`, traces off, `FILE_LOCK=FS`, `CACHE_TYPE=TQ` (owner request, no effect), `DB_CLOSE_DELAY=-1` + explicit `SHUTDOWN` | measured at 100 k albums (live data ≈ 200 MB): file 2.2 GB → 0.25 GB after a bulk index, text search 585 → 57 ms, tags 520 → 44 ms, facets 645 → 190 ms. `CACHE_TYPE` TQ/SOFT_LRU: no effect (MVStore has its own cache); `PAGE_SIZE` 8/16 KiB: neutral, superseded by COMPRESS's 64 KiB pages; `RETENTION_TIME=0` no smaller than 10 s and unsafe on power loss; VARCHAR instead of inline CLOB: no gain; `MVCC`, `EARLY_FILTER` are rejected by H2 2.x |
| `AlbumService` is the only writer, striped per-album locks, events after the lock | a scan cannot overwrite a concurrent user edit; no I/O under lock |
| Removal only after a complete walk, only below the walked scope; unreachable roots skipped | an unplugged disk, a cancelled scan or a one-folder rescan never wipes the rest of the index |
| A removed album leaves a tombstone by `contentId` (table `removed`); a new album with that content recalls it | scoped pruning and restores from backup never lose ratings and edits; the price: a copy of a deleted album inherits them |
| The `root` column is informational; scans, pruning, folders and filters select by path prefix (`album_path` index); `retainRoots` re-roots | widening or narrowing a root keeps its albums; the folder tree needs no table of its own |
| Libraries: `data-dir` = home with `libraries.json`, every store under `library-dir`, switch and optimize by in-process restart | every store, the H2 URL and the settings hang off one property, so no store has to be re-creatable; the UI's reconnect path already exists; H2 compacts fully only offline (`SHUTDOWN COMPACT`) |
| Identity: `signature` (all files + mtime) vs `contentId` (images, name + bytes) | sidecar edits trigger re-index; moves/renames keep id and edits |
| Roots must be disjoint | nested roots would index the same album twice under different ids |
| Mock gateway in the browser | UI work without a backend; also the demo mode |
| Custom layout engine (bricks/grid) as a pure function | testable, absolute placements enable virtual scrolling |
