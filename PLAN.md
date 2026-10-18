# PLAN — state, next steps, decisions

[↑ INDEX](INDEX.md) · requirements: [SPEC.md](SPEC.md)

Living document: update **State** and tick items when work lands; add a **Decisions** row when a choice is costly to
undo. Reference SPEC IDs.

## State (2026-10-18, v0.1.0)

- 2026-10-18 **catalog phase 0 (backbone) landed**, uncommitted: [catalog_plan.md](catalog_plan.md) §8 phase 0 —
  schema (`entity`, `alias`, `album_link`, `entity_link`, `photo`, `photo_link`, `meta`; `album.seq/hidden/bio`;
  `removed` PK `(content_id, id)` + `seq/hidden`, migrated on start), records (`AlbumMeta.genres`, `AlbumPatch`
  date/genres/hidden/addTags/removeTags, `AlbumQuery` entities/missing/hidden/duplicates/genres, `Album`
  hidden/bio/stamp/genres/links/copies + `Internal.seq`, `Entry.flags`, versioned `EntryCodec`), `catalog/`
  (`Catalog` with read-write lock and `resolving` boundary, `CatalogRepository` + JDBC), `index/Gate` (one
  admission gate: SCAN, OP, RESTART, SETTINGS), `index/Reindexer` (re-index with asserted identity + name map),
  `AlbumService` quiesce/busy/relocate/rebase/removeBelow and `commit(prior, …, nameMap)`, `Containers.openRaw`,
  `AlbumContainer.extract`, `Cry.encrypt/plainName`, `ops/` records + `FileOps` (admission only; every item "not
  implemented"), every WS message of ARCHITECTURE §3's catalog table through `CatalogApi`/`OpsApi`/`PhotosApi`,
  `/img` 503 for busy albums; frontend wire mirror, `MockGateway` split (`mock-catalog/ops/photos.ts`), `Selection`,
  `CatalogStore`/`OpsStore`, shells `ContextMenu`/`Confirm`/`EntityPicker`, card badges, Operations settings page,
  details-panel entity mode. Reviews: Opus parity pass (8 fixed), Codex gpt-6-astra (9 fixed: quiescence drain,
  gate before settings snapshot, serialized settings saves, photo renames and count deltas inside the save
  transaction, atomic entity save, favorites counts from actual changes, resolution boundary), Codex gpt-6.1-sol
  re-check (two more closed: `remove` deletes under the catalog read lock, the scan's cancel token is published with the gate under one lock). Checks: `:backend:test` 100 (1 skipped), `ng build` 504 kB, `ng test` 35; live smoke on the packaged
  jar (`backend-demo`): search fields, lists/links/marks, hide/unhide, date `2019`, copies, duplicates/entities/
  missing filters, entity card counts, preflight, op admission, not-implemented errors, images 200.
- 2026-10-18 **F2 viewer landed** (branch `claude/catalog-f2-6209d0`): catalog_plan §7.5 in the mock UI — sort menu
  (six fields, direction, reshuffle), size filter, five scale modes, full screen + slideshow, photo ratings/favorites
  with multi-select by entry name, copy-to targets through preflight → Confirm → `COPY_PHOTOS`, metadata dialog, bio
  drawer, viewer defaults on the Appearance page, §7.7 keys. Checks: `ng build` 542 kB, `ng test` 45. Unverified:
  `requestFullscreen` (pending in the embedded preview pane), the live `photoMeta`/`albumText`/`COPY_PHOTOS` paths.
- Open after phase 0 (each owned by a track): `Catalog.resolve` returns no links (B1), merge/creditAs/compareCopies/
  entities-under/genres filter/alias-resolved filters (B1), every operation item (B2), packers (B3), `Entry.flags`
  always 0 and `bio` null, `photos`/`photoMeta`/`albumText` (B4), the UI beyond the shells (F1–F4). `ops/Manifest`
  and `Journal` records are B2's.

- 2026-10-14 `.cry` password is session-only: the key lives in server memory (never in settings.json), forgotten on restart or 10 s after the last client leaves; clients see an 8-hex fingerprint only; albums indexed with a key survive a missing key (only their `.cry` reads fail); Folders page shows a masked set state and an eye toggle for a typed password.
- 2026-10-14 UI polish: sort = field × direction (`ui/sort-menu.ts`, 4 reverse `Sort` orders + indexes), folder tree filter/collapse/expand, kind strip, light/dark toggle only, settings behind the brand button, zip-file archive icon, datalist arrows and Save icon fixed. Checks: backend 70 tests pass (1 skipped), frontend build + 15 tests pass.
- 2026-10-14 SIMD resampler "5-10x slower in the app": the dev run `bootRun` carried Spring Boot's optimized launch
  (`-XX:TieredStopAtLevel=1`, C1 only), where the Vector API is never intrinsified and boxes every vector: 4 thumbnails/s
  instead of ~200 with the jar, 320 CPU-s per minute, 2.4 GB working set. Fixed with `optimizedLaunch = false` and
  `Resampler.SIMD` requiring C2 (`c2()`, the path is logged at start as `Resampler.PATH`; the warm-up runs at normal priority; warming the
  gather branch as well was tried and dropped: it left the main shape 3x slower afterwards); with C2 the SIMD path is 1.7x faster than scalar on 12 threads and on par in
  the decode-bound app (202 vs 189 thumbnails/s; cold scan of 120 albums 1.5 vs 1.7 s). Numbers in
  [scalebench/results.md](scalebench/results.md).
- 2026-10-14 xanderView `.cry` images (SPEC L2a): `source/Cry` decorates every container (`Containers.open`), password
  in Settings → Folders stored as the derived key (`cryKey`); stored signatures carry the key state so a password
  change re-indexes only the albums holding `.cry` files; locked `.cry` files are not images (pure-`.cry` albums
  removed, tombstoned, on the next scan). Byte compatibility checked against files written by xanderView's own
  encryptor (`cry-golden.tsv`); decrypt cost measured at ~4.4 GB/s (24 MiB read 16 ms vs 11 ms plain). Checks:
  `:backend:test` 69 (+1 env-gated bench) green, frontend `ng build`.

- 2026-10-14 storage-aware reads (Next 7, steps 1–3): per-disk FIFO lanes with SSD/HDD detection (C: NVMe → SSD,
  V: network → HDD verified; D: untouched, reserved for the cold final test), read → queue → parallel decode
  pipeline, head probes for TIFF/PNG/GIF/BMP, on-disk/packing order for heads, one gallery pass per viewed solid
  archive, roots on different disks scanned at once. Checks: `:backend:test` 63 (+1 env-gated bench) green,
  frontend `ng test` 15/15 and `ng build`.

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

0. **Catalog, operations, viewer** — models/studios/photographers/genres/favorites as linked entities, confirmed and
   journaled file operations (move, Recycle Bin delete, hide, pack/unpack/repack, `.cry` encrypt/decrypt, copy photos),
   duplicate galleries by content stamp, multi-selection + context menus, grouped sidebar trees, entity cards, viewer
   rework (sort/filter/scale/full screen/slideshow/photo marks/copy targets/bio/metadata), plugin contracts for
   extraction and covers. Plan, data model, wire additions and the parallel track breakdown:
   [catalog_plan.md](catalog_plan.md) (2026-10-18, under review). Changes INTENT (library writable through
   operations only; photos can be marked). Subsumes items 1 (cover strategies as beans + ServiceLoader) and 4 (date
   in `AlbumPatch`).
1. **C2/N6 pluggable cover strategies** — `CoverSelector` takes ordered `List<CoverStrategy>` beans instead of the
   hardcoded `List.of(NAME_HINT, SMALLEST)`; keep "pinned first, then all entries" fallback. → part of Next 0 (§5).
2. **Test gaps (backend)** — `ZipContainer`/`Containers` (nested entries, exclusions), `AlbumDiscovery`
   (min-images, AccessDenied, incomplete walk), `ImageController` (ETag/404/original stream, WebP thumbs), `MvStoreBlobStore`, `SettingsStore` round trip, WS `update`/`facets`/`scan`/`reveal`.
3. **Test gaps (frontend)** — `AlbumStore` (generation guard, `loadMore` identity check, refresh max-wait),
   `MockGateway.matches/comparator` parity with server semantics, `Prefs` persistence.
4. **M5 editable shot date** — add `date` to `AlbumPatch` → `AlbumMeta` edited layer → details panel. → part of Next 0
   (bulk "Shot date…" assignment).
5. **Q5 contextual facets** — call `gw.facets(filters)` so suggestions narrow to the current query (protocol and
   `WsGateway` already support it; the store calls it unfiltered).
6. ~~More containers~~ — done 2026-10-14 through 7-Zip-JBinding (`SevenZipContainer`); open: multi-volume archives
   (`.part1.rar`, `.7z.001`), encrypted ones, keeping an opened archive across image requests (each `/img` request
   opens it again: cheap for zip/7z headers, a pass for a wrapped tar).
7. **HDD-aware scan and viewer** — indexing reads up to `index-threads` albums at once, which thrashes a spinning
   disk; solid 7z / wrapped-tar galleries cost Θ(N²) decompression. Plan (reviewed 2026-10-14): a FIFO lane per disk
   (scan + view; serial on HDD/remote/unknown, free on SSD; lock order lane → Limiter), storage autodetected per root
   with an override, a paced one-pass gallery for sequential archives. Details and test rigs:
   [hdd_ssd_fix_goals.md](hdd_ssd_fix_goals.md). Done 2026-10-14 (plan steps 1–3): `source/Disks` (detection + lanes,
   `hdd-readers`), `storage` per root (wire + Folders page), read → queue → parallel decode pipeline, album turn in
   the scan, probes from the head for TIFF/PNG/GIF/BMP, heads in on-disk/packing order, one-level directory listing.
   Codex review (gpt-6.1-sol xhigh) found 4 defects, all fixed (cancel before the disk turn, gone link targets fail
   the listing, truncated PNG/TIFF facts fall back to a full read, GIF size = first frame). Steps 4–5 done the same
   day: `album/Gallery` (one paced pass per viewed sequential archive), scopes on different disks walked at once;
   Codex review of them: gallery memory bounded to the lookahead, archive probing outside the gallery lock, no
   gallery after shutdown, threads in the gallery key; `index-threads` is per disk by decision. `TuplBlobStoreTest
   .concurrentWritersAndReadersSeeWholeValues` failed once (LockInterruptedException), 3/3 green on rerun: flaky,
   store code untouched.
   Final test on the cold USB HDD done (results in the plan). Cold comparison with the old code (`a6713b0`, second
   cache drop): old scan 12.5 s vs 21.8 s of the first new run (spin-up after dismount, the turn held during a solid
   7z's CPU-bound decompression); an O_DIRECT read benchmark confirms one album at a time on the HDD (53.8 vs
   20.1 MB/s); the folder gallery is 1.5x faster (p95 377 → 63 ms), the solid 7z gallery 2.9 s vs minutes. Fixed:
   `cpuBound()` archives give the disk turn back after their listing. Done: the scan pauses while the UI reads the
   same HDD (`Lane.giveWay`, `QUIET` 300 ms; verified live). Cold rerun of the fixed code (third cache drop): 9.5 s
   vs 12.5 s for the old code; folder gallery 1.05 s (p95 69 ms), solid 7z gallery 3.1 s.

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
| Catalog: one `entity` table for six kinds (model, studio, photographer, genre, tag, list), `album_link` keyed by an `INT` surrogate `album.seq`, text fields stay the truth and links are derived on every save, `TINYINT` codes, stored visible-album counts | one CRUD, one tree, one picker; compact keys (9 bytes instead of 41); re-index can never disagree with the catalog; trees open with one indexed read ([catalog_plan.md](catalog_plan.md) §2, §9) |
| Safe-delete = `hidden` flag (index only); Delete = plain confirmed deletion; transformation sources deleted only after byte-for-byte verification; no Recycle Bin | owner's definition; a bin needs AWT (unusable under Spring's headless default) and fails on held zips; verification is the safety |
| One admission gate (`index/Gate`: SCAN, OP, RESTART, SETTINGS) instead of `running()`/`frozen()` checks; operations snapshot settings and the `.cry` key at admission | two checks are not mutual exclusion; a scan under a root change or a move under a scan cannot be reasoned about |
| Count deltas, photo renames and link replacement happen inside `JdbcAlbumRepository.save`'s transaction; the catalog read lock is held across resolution and that save (`Catalog.resolving`) | one transaction, one place for the delta rule, no double counting against `recount()` (Codex review 2026-10-18) |
| Disk reads gated by a FIFO lane per physical disk (HDD/network/unknown: `hdd-readers` = 1 at once; SSD: no gate), not a dedicated reader thread + byte-bounded queue + worker pool | same disk schedule (one stream, arrival ≈ walk order) with the existing thread-per-album model; decode stays parallel because the turn ends with the read and a bounded queue (2 × threads) decouples reading from decoding; one lock order (turn → queue → slot) keeps it deadlock-free; reviewed by Codex and Fable 2026-10-14 ([plan](hdd_ssd_fix_goals.md)) |
| Hot path native: libjpeg-turbo over FFM, libwebp via webp4j JNI (its DLL exports only `Java_*`), 7-Zip-JBinding JNI; zip stays on the JDK | FFM where the library has a C API, JNI where it does not; the JDK's zip is as fast for stored JPEGs and keeps its tested path |
| Natives packed in the jar per platform, copied once into `~/.thumbnailer/natives/<platform>/<bundle>` (`LibraryLoader`) | no temp file per start (webp4j's extraction left undeletable DLLs on Windows), versions coexist, one `stat` per start |
| Archive signature = archive size + mtime + exclusion globs | a rescan of 100 k unchanged archives opens none; the first scan after the upgrade re-lists zip albums once (probes and covers are reused) |
| JPEG decoded at the smallest power-of-two DCT scale ≥ 1.5× the target, finished by a Lanczos3 (`boxed`) | 1/4 + Lanczos3 = 50.8 dB at 57 ms vs a full decode 49.3 dB at 77 ms; 3/8, 5/8 have no SIMD IDCT ([results](scalebench/results.md)) |
| Resampler: gpt-6-astra's Q7 SIMD kernels + Fable/Opus power-of-two box, SIMD and scalar paths with the same bits | best weighted speed of four candidates and the best scalar fallback; the app may run without `--add-modules jdk.incubator.vector` |
| SIMD resampler warmed up at start (`Resampler.warmUp`, ~1 s of one core in the background) | interpreted Vector API code boxes every vector: the first album after start took 1 s with SIMD and 0.5 s scalar, 0.34 s once warmed |
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
| `.cry` key state as a signature suffix (`+locked` / `+hash` / `+` none) instead of a schema column or a key-change event | a password change re-indexes exactly the affected albums through the ordinary scan; archives without `.cry` keep their `stat`-only rescan; one-time re-index of pre-feature signatures is the price |
| `settings.json` keeps the PBKDF2-derived `.cry` key, not the password | the key decrypts the files anyway (as xanderView's key file does), but a reused password never lands on disk |
| Roots must be disjoint | nested roots would index the same album twice under different ids |
| Mock gateway in the browser | UI work without a backend; also the demo mode |
| Custom layout engine (bricks/grid) as a pure function | testable, absolute placements enable virtual scrolling |
