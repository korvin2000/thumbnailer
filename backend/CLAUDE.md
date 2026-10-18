# Backend rules (Java 27, Spring Boot 4.1, Undertow) — loaded when working under backend/

Map: [docs/map/backend.md](../docs/map/backend.md). Design: [ARCHITECTURE §1–2](../docs/ARCHITECTURE.md#2-backend-package-thumbnailer).

## Conventions (match them)

- Package = feature; classes package-private unless used across packages. Data = records with normalizing compact
  constructors; protocols/events = sealed interfaces + pattern-matching `switch`.
- Spring: constructor injection only, component scanning, no `@Bean` methods, no `@ConfigurationProperties`
  (`@Value("${thumbnailer.x:default}")`). SPI implementations are `@Component`s collected as `List<…>`
  (`Containers`; `Metadata` and `CoverSelector` also take `META-INF/services` providers through `Plugins.load`, ordered by the SPI's `order()`, not `@Order`). Adapters chosen by
  `@ConditionalOnProperty(name = "thumbnailer.store.albums|blobs", havingValue = …, matchIfMissing = true)`.
- Jackson 3: `tools.jackson.databind.json.JsonMapper`; annotations still `com.fasterxml.jackson.annotation`.
  `AlbumSocket` hides `Album.internal` and `Entry.mtime` from the wire via mixins.
- Nullness by convention (null = not set / no filter / unchanged); `Optional` for lookups. No custom exceptions:
  `IllegalArgumentException` / `NoSuchElementException` / `IllegalStateException`; WS turns any exception into
  `Event.Error`. Per-item I/O failures: `catch (IOException | RuntimeException e)` → `log.warn` → skip.
- Threads: platform daemon threads `scan`, `ws-broadcast`, `ws-idle`, `covers-compact`, `resampler-warmup` (once at start), `catalog-backfill` (once at start, only while the gate is busy then), `ops` (one per operation), `pack-read` (the pass feeding a 7z pack); the non-daemon `restart` thread; virtual `walk-N` (one walker per disk in a scan) and `gallery` (one pass per viewed sequential archive); indexing runs one virtual thread per
  album (`index-N`), bounded by a semaphore (`index-threads` albums per disk walker); renders are a pipeline in `AlbumService`
  (covers: indexing + cover misses; entry thumbnails): read in a disk turn (`source/Disks` lane: FIFO, `hdd-readers`
  at once on an HDD/network/unknown disk, ungated on an SSD; separate `SCAN` and `VIEW` lanes), a place in the queue
  `Limiter` (2 × threads), the turn ends with the read, then decode under the slots `Limiter` (threads), all resized
  from `Settings.covers/thumbs` before each acquire. **Order: turn → queue → slot, never back**; a lane is not
  reentrant (hand a `Hold` on instead of entering twice); a scan's album turn spans its reads up to the first cover
  read, except a `cpuBound()` archive, which gives it back after the listing; on an HDD a scan's lane gives way to
  the UI's (`enter`, `giveWay` between head reads: never call it while holding a queue place or a slot). A `sequential()` archive (solid, wrapped tar): several files only through `heads` (one pass, packing order),
  never a per-file loop; entry thumbnails through its `Gallery` pass; a single entry (cover, sidecar, a thumbnail
  taken before) is a partial pass from the block start. No `@Async`. HTTP requests and WS messages run on Undertow's `XNIO-n task-m` workers (never its I/O
  threads: handlers block).
- Web server: Undertow through `web/UndertowServer` (Boot 4 ships none); `spring-boot-starter-tomcat` is excluded for
  every configuration, so a new Boot starter cannot bring Tomcat back. Undertow keeps no list of the sockets Spring
  upgraded: anything that must reach clients at shutdown goes out on `ContextClosedEvent` (`AlbumSocket.goingAway`).
- Hashing: hash4j `Hashing.wyhashFinal4()` (signatures, content ids, cover tags); `String.hashCode` only for lock stripes.
- Native code: TuplDB and TurboJPEG (FFM), webp4j and 7-Zip-JBinding (JNI) need `--enable-native-access=ALL-UNNAMED`
  (manifest, `bootRun`, tests) and the system property `org.cojen.tupl.core.DirectPageOpsSelector=ffm` set before TuplDB
  loads (`main`, tests). Our natives load only through `natives.LibraryLoader` (`load` for JNI, `path` for FFM lookups),
  never from temp files; a new native = files + `natives.sha256` lines per platform ([docs/natives.md](../docs/natives.md)).
  No `critical` downcalls for long native work (a decode held off every other thread's safepoint: -14 % throughput);
  codecs work in native memory. JNI binds per class loader: `System.load` runs in `LibraryLoader`, so the classes with
  `native` methods must share its loader (app classes and jars do).
  Close TuplDB with `shutdown()`, never `close()` (that leaves state to replay). Compaction lives in the adapter.
- Image pipeline: one `Pixels` layout (packed `0x??RRGGBB`) from decode to encode; read an entry once (`AlbumContainer.read`
  into a confined arena, `head` for header parsers). 7-Zip calls its output streams from its own threads: callbacks
  touch no thread-confined state (arenas, `Thread.currentThread()`), and a `heads` sink must not read the container.
  The Vector API is SIMD only in C2 code: `Resampler.SIMD` is false without C2 and `bootRun` sets `optimizedLaunch = false`
  (Boot's default `-XX:TieredStopAtLevel=1` boxed every vector: 4 thumbnails/s instead of 200, the PC lagging).
- Paths: always `Containers.normalize()` (absolute, `/`), compare with `Containers.prefix()` / `within()`. Stored
  paths are canonical and never rewritten; `Settings.locate(canonical)` is the only canonical → physical mapping and
  every disk access (walk, container open, `Files.notExists`, reveal) goes through it. A scan binds each root to one
  (canonical, physical) pair from one settings snapshot.

## Invariants (don't break)

- `AlbumService` is the only writer (`commit`, `patch`, `remove`, `retainRoots`). Writes happen under
  `synchronized (lock(id))`; events are published **after** the lock; both `commit`s re-read the stored album under
  the lock so a scan or an operation never overwrites a concurrent edit; a failed WS request logs its `type`, never
  the payload (passwords).
- Every save bumps `revision`. Only covers are stored: key = album id, value = 8-byte tag (wyhash seeded by the
  render epoch, of cover name + signature) + `[len 4][bytes]`; for `Render.LEGACY_EPOCH` (WebP/75/512) the older
  `[tag 8][webp]` with the RIFF size as length, so old stores stay valid. The epoch travels with the bytes (`Thumb`):
  a value is tagged with the epoch it was rendered with, never the spec current at commit. `storedCover` serves a value
  only if its tag matches the album under the current covers spec and its length is whole; anything else is a miss and
  re-renders. So writes need no ordering with the SQL save, and a change to what a cover depicts or how it is rendered
  must feed the tag (`Render.epoch()`: format, size, quality). Entry thumbnails are never stored.
- Metadata merges differ on purpose: `AlbumMeta.over` (edits over extracted, null falls through) vs `orElse`
  (extractor chain, blank falls through, tags union). In the edited layer `""`/`[]` mean "explicitly empty".
- Identity: `signature` (all files incl. sidecars, with mtime) decides re-index; `contentId` (images, name + bytes)
  decides moves. A move needs the old path to be gone. Stored signatures end in the `.cry` key state (`Cry.signature`,
  compared with `Cry.matches`, which accepts the two valid forms without listing an archive): store and compare through
  `Cry`, never the raw `container.signature()`; a missing key (session-only, `SettingsStore`) never re-indexes a keyed album. Containers always come wrapped by `Cry` (`Containers.open`); arrays a
  container hands out (`head`, `heads`) are never written, 7-Zip keeps them as its head cache.
- Removal (`Reconciler.Pass.prune`) only for scopes (a root or a folder below one) whose walk completed without cancel
  or errors, and only of albums at or below the scope; an album is marked seen before indexing, so failures never
  delete. Unreachable roots are skipped. `AlbumRepository.delete` always leaves a tombstone (`removed`, by content id)
  that `AlbumService.commit` recalls (a read-only peek) for the next new album with that content (id, revision + 1,
  rating, edits, pin); `save` deletes the tombstones of the saved id in its own transaction, so adoption is atomic
  with the save, and `commit` ignores a tombstone whose id is live again.
- Catalog ([catalog_plan.md](../catalog_plan.md) §2): the album's text fields are the truth, `album_link` rows are
  derived from them by `Catalog.resolving` inside every save, which runs `repo.save(album, entries, resolution, Save)`
  under the catalog read lock (album, links, count deltas, renamed photo marks and the alias-extended `search` in one
  transaction); `Album.links` is display data. Lock
  order stripe → catalog read/write lock, never back: catalog writes (merge, rename, alias, delete, list membership, backfill) never
  take an album stripe; what must change album text goes through `patch`/`unlink` as a separate step. Catalog
  changes broadcast `catalogChanged` and never bump `revision`. `hidden` is filtered in `Where.of`, `folders`,
  `rootStats`, `stats` — **never in `UNDER`** (`stamps`, `byRoot`, `reroot` must see hidden albums). `album.seq`
  is the key of every new table, is never in the `MERGE` column list (identity on insert; a tombstone's value set
  by one `UPDATE` on adoption) and stays with the tombstone (`removed` PK `(content_id, id)`, newest wins).
  `entity.albums` changes only in `JdbcAlbumRepository.save` (its own deltas over every role from the links it
  replaces and `Save.visibleBefore/After`; skipped under a scan), `setListLink` (a row really changed, album visible),
  `delete` (run under the catalog read lock, `Catalog.reading`, inside the stripe) and `recount()`.
- One admission gate (`index/Gate`): SCAN, OP, RESTART (never released), SETTINGS (root/path-map saves). Scans,
  operations, library switch/optimize and such saves take it by compare-and-set; two `running()` checks are not
  mutual exclusion. An operation snapshots `Settings` and the `.cry` key at admission.
- `ops/FileOps` is the only code that writes to the library; it updates the index only through `AlbumService`
  (`relocate`, `rebase`, `remove`, `patch(hidden)`) and `index/Reindexer` (`commit(prior, …, nameMap)`: an
  operation asserts the identity; the scan keeps the path-gone heuristic of `commit(base, …)`). It reads through
  `Containers.openRaw` (unwrapped: ciphertext as is, `Exclusions.NONE`) — the one exception to "containers always
  come wrapped by `Cry`" besides `compareCopies`. A busy album (`AlbumService.quiesce`) answers 503 on `/img`: every
  `/img` read of an album's files is an `AlbumService.read` (counted; refused once busy) and `quiesce` waits ≤ 5 s for those
  in flight, gallery passes included.
- The `root` column is informational: scans, pruning, folders and the root filter select by path prefix
  (`path = dir OR path LIKE 'dir/%' ESCAPE '!'`, index `album_path`); `retainRoots` (on `setSettings` and at every scan
  start) re-roots the albums of a removed root that lie under a configured one and removes the rest.
- `ScanService.close()` (on `ContextClosedEvent`) must stop scans before the stores close; the H2 URL keeps
  `DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1` for that reason and `JdbcAlbumRepository.close()` runs `SHUTDOWN`. Indexers honour thread interruption.
- Libraries and restart: every store finds its files under `${thumbnailer.library-dir:${thumbnailer.data-dir}}` (only
  `Libraries`/`LibraryLocator` use `data-dir`, the home); never set `library-dir` in `application.yml` (the locator
  would take it as pinned). Switch and optimize end in `ThumbnailerApplication.restart()` (close context, run again),
  so everything must close cleanly on context close and nothing may assume one context per JVM; library operations
  are refused while the gate is held, and an accepted switch/optimize keeps it as RESTART (a switch hands its SETTINGS
  hold over by `Hold.restart()`, optimize enters by `ScanService.freeze()`: no scan starts, no second operation) until the restart, which falls back to the previous library once if the new one fails to start.
  `thumbnailer.library-pinned` (set by the locator) is the pinned flag. H2 compacts fully only at close (`compact()` → `SHUTDOWN COMPACT`, offline), TuplDB
  online (stepped `compactFile`).
- `JdbcAlbumRepository.save` is two H2 `MERGE ... KEY` upserts (album, packed `entries`) plus the tombstone delete
  (`removed` by id, index `removed_id`) in one transaction;
  `entries == null` keeps stored entries; every `order()` must match one index in `schema.sql` exactly (columns,
  directions, `NULLS LAST`), or that sort falls back to sorting every row (seconds at 100 k albums); tags are
  stored as `" a b "` for `album()` and the search text only; tag filters and facets go through `album_link` (alias-resolved).
  `schema.sql` must stay idempotent (`IF NOT EXISTS`); there is no migration tool.
- Entry `index` = position among readable images (name-sorted); it is the id used by `/img/{id}/{index}` and the UI.

## Extending (recipes)

- Container kind: a format 7-Zip reads is one more entry in `SevenZipContainer.EXTENSIONS`; anything else is a
  `ContainerFactory` `@Component` (copy `ZipContainer.Factory`), add to `Kind`, mirror `Kind` in
  `frontend/src/app/core/model.ts`.
- Metadata source: `MetadataExtractor` `@Component` with `order()`, returning a partial `AlbumMeta` from the `Extraction`
  context; a plugin jar instead registers a public no-argument class in `META-INF/services/thumbnailer.metadata.MetadataExtractor`.
- Cover rule: `CoverStrategy` `@Component` with `order()` (or `META-INF/services/thumbnailer.cover.CoverStrategy`).
- Storage engine: new package under `store/`, implement the port, `@ConditionalOnProperty` on a new value.
- WS message: record in `web/ws/Request` or `Event` with `@JsonTypeName`, a `case` in `AlbumSocket.handle`, then the
  frontend mirror (see `.claude/rules/wire-contract.md`).

## Tests

JUnit 5 + AssertJ. Prefer plain unit tests (construct the class directly; `JdbcAlbumRepositoryTest` shows an
embedded H2 with `schema.sql`). `ScanTest` is the end-to-end harness: full context, per-class data dir under
`build/test-data/`, in-memory H2, `@TempDir` root, synchronous `ScanService.scan(null, cancelled)`, generated JPEGs
with a per-test size salt (identical sizes across tests would look like moves). Real WS client example:
`socketEchoesIdsHidesServerFieldsAndDropsRemovedRoots`.
