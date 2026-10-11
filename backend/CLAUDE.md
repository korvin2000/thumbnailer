# Backend rules (Java 26, Spring Boot 4.1) — loaded when working under backend/

Map: [docs/map/backend.md](../docs/map/backend.md). Design: [ARCHITECTURE §1–2](../docs/ARCHITECTURE.md#2-backend-package-thumbnailer).

## Conventions (match them)

- Package = feature; classes package-private unless used across packages. Data = records with normalizing compact
  constructors; protocols/events = sealed interfaces + pattern-matching `switch`.
- Spring: constructor injection only, component scanning, no `@Bean` methods, no `@ConfigurationProperties`
  (`@Value("${thumbnailer.x:default}")`). SPI implementations are `@Component`s collected as `List<…>`
  (`Containers`, `Metadata`; extractor order by `@Order`). Adapters chosen by
  `@ConditionalOnProperty(name = "thumbnailer.store.albums|blobs", havingValue = …, matchIfMissing = true)`.
- Jackson 3: `tools.jackson.databind.json.JsonMapper`; annotations still `com.fasterxml.jackson.annotation`.
  `AlbumSocket` hides `Album.internal` and `Entry.mtime` from the wire via mixins.
- Nullness by convention (null = not set / no filter / unchanged); `Optional` for lookups. No custom exceptions:
  `IllegalArgumentException` / `NoSuchElementException` / `IllegalStateException`; WS turns any exception into
  `Event.Error`. Per-item I/O failures: `catch (IOException | RuntimeException e)` → `log.warn` → skip.
- Threads: platform daemon threads named `scan`, `index-N`, `ws-broadcast`; no `@Async`, no virtual threads.
- Paths: always `Containers.normalize()` (absolute, `/`), compare with `Containers.prefix()`.

## Invariants (don't break)

- `AlbumService` is the only writer (`commit`, `patch`, `remove`, `retainRoots`). Writes happen under
  `synchronized (lock(id))`; events are published **after** the lock; `commit` re-reads the stored album under the
  lock so a scan never overwrites a concurrent edit.
- Every save bumps `revision`; blob keys are `cover/{id}/{rev}` and `thumb/{id}/{rev}/{index}`; `store()` drops all
  older keys by prefix (`cover/{id}/`, trailing slash matters). Changing the scheme means changing `store()`,
  `remove()`, `cover()/thumb()` and `BlobStore.remove(prefix)` together.
- Metadata merges differ on purpose: `AlbumMeta.over` (edits over extracted, null falls through) vs `orElse`
  (extractor chain, blank falls through, tags union). In the edited layer `""`/`[]` mean "explicitly empty".
- Identity: `signature` (all files incl. sidecars, with mtime) decides re-index; `contentId` (images, name + bytes)
  decides moves. A move needs the old path to be gone.
- Removal (`Reconciler.Pass.prune`) only for roots whose walk completed without cancel or errors; an album is marked
  seen before indexing, so failures never delete. Unreachable roots are skipped.
- `ScanService.close()` (on `ContextClosedEvent`) must stop scans before the stores close; the H2 URL keeps
  `DB_CLOSE_ON_EXIT=FALSE` for that reason. Indexers honour thread interruption.
- `JdbcAlbumRepository.save` is delete + insert in one transaction; `entries == null` keeps stored entries; tags are
  stored as `" a b "` and matched with `LIKE '% tag %'` — change `album()`, `facets()` and `Where` together.
  `schema.sql` must stay idempotent (`IF NOT EXISTS`); there is no migration tool.
- Entry `index` = position among readable images (name-sorted); it is the id used by `/img/{id}/{index}` and the UI.

## Extending (recipes)

- Container kind: `ContainerFactory` `@Component` (copy `ZipContainer.Factory`), add to `Kind`, mirror `Kind` in
  `frontend/src/app/core/model.ts`.
- Metadata source: `MetadataExtractor` `@Component @Order(n)` returning a partial `AlbumMeta`.
- Cover rule: new `CoverStrategy` + add it to the chain in `CoverSelector` (chain is hardcoded — see PLAN).
- Storage engine: new package under `store/`, implement the port, `@ConditionalOnProperty` on a new value.
- WS message: record in `web/ws/Request` or `Event` with `@JsonTypeName`, a `case` in `AlbumSocket.handle`, then the
  frontend mirror (see `.claude/rules/wire-contract.md`).

## Tests

JUnit 5 + AssertJ. Prefer plain unit tests (construct the class directly; `JdbcAlbumRepositoryTest` shows an
embedded H2 with `schema.sql`). `ScanTest` is the end-to-end harness: full context, per-class data dir under
`build/test-data/`, in-memory H2, `@TempDir` root, synchronous `ScanService.scan(null, cancelled)`, generated JPEGs
with a per-test size salt (identical sizes across tests would look like moves). Real WS client example:
`socketEchoesIdsHidesServerFieldsAndDropsRemovedRoots`.
