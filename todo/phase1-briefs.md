# Phase 1 — session briefs (progress note, 2026-10-18)

State: catalog_plan.md phase 0 is committed on `main` as `1ac4fa5` (tree clean). Checks at that commit:
`:backend:test` 100 (1 skipped), `ng build` 504 kB, `ng test` 35, live smoke on the packaged jar green.
Reviews folded in: Opus parity pass, Codex gpt-6-astra (9 findings), Codex gpt-6.1-sol re-check (2 more).
Owner decisions this session: safe-delete = `hidden` flag only, no Recycle Bin; data model tightened (§2.1);
all seven phase-1 tracks run **in parallel**, each in its own worktree branched from `1ac4fa5`, Opus for backend
tracks (B1/B2 xhigh, B3/B4 high), Fable xhigh for UI tracks (F1/F2/F3); reviews by Codex only (gpt-6-astra high
for B1/B2, gpt-6.1-sol xhigh for the rest), never by Opus. Sessions launched; see below.

Launched 2026-10-18 (all seven, `initiation: user_asked`, fresh context, own worktree from `1ac4fa5`):
b1-catalog local_0f697415-cd85-4c50-a40b-2e0f33956a72 · b2-ops local_3b27dbd9-7e5c-4f90-8ab5-9e9b996d4b02 ·
b3-packers local_029b316d-2389-48a4-892e-f113b264642a · b4-extraction local_8cc03766-bfb0-47a3-8899-0d0400c359c9 ·
f1-ui-infra local_107182eb-e2d0-4266-8593-e5916a9c8364 · f2-viewer local_26da897e-f3cd-4eab-8c8d-7d4ca19ca441 ·
f3-trees-cards local_5408aa3a-87b1-4dc3-8a1b-8b28b44d8db1. Next: as each reports, check its branch (diff, tests),
then merge in order B3 → B1 → B4 → B2 → F1 → F2 → F3 into main only on the owner's say-so; then phase 2 (F4, B5).

### Phase 1 results (all seven merged into main 2026-10-18 in order B3 B1 B4 B2 F1 F2 F3; docs reconciled in f810e23; nothing pushed)

Merge checks on main: `:backend:test` 139 (1 skipped), `ng build` 609 kB (warn budget 600), `ng test` 59, full `./gradlew build`,
live smoke on the packaged jar (`backend-demo`): entities/facets/alias filter/under/entityCard.derived/album links/photoMeta/preflight/copies/hidden/duplicates/missing all answer; Browse tree grouped by Models shows 12 models with counts. Next: phase 2 (F4 actions, B5 integration — see PLAN "Open after phase 1"), phase 3 (Codex reviews per track, `:spi`, docs).

| track | branch (worktree under .claude/worktrees/) | commit | checks |
|---|---|---|---|
| B1 | claude/catalog-b1-091200 | 53159f0 | backend 111 tests, 1 skipped, green |
| B2 | claude/catalog-b2-821f44 | 7e4d54d | backend 108, 1 skipped, green; FileOpsTest 8/8 |
| B3 | claude/catalog-b3-73a732 | 163fec6 | 18 B3 tests green; full suite 111 with 1 flaky phase-0 failure (see below) |
| B4 | claude/catalog-b4-84057e | da5afa1 | backend 109, 1 skipped, green |
| F1 | claude/catalog-f1-597139 | 35f8ba5 | ng build 511.9 kB, ng test 42, mock pass |
| F2 | claude/catalog-f2-6209d0 | 1549a37 | ng build 541 kB, ng test 45, mock pass; full screen unverified |
| F3 | claude/catalog-f3-299886 | c1f10bc | ng build 556 kB, ng test 42, mock pass |

Files touched by more than one branch (expected merge conflicts): PLAN.md, SPEC.md (all 7); README.md (B2 B4 F1 F2);
ScanTest.java (B1 B2 B4); ARCHITECTURE.md (B1 B2 B4); docs/map/backend.md (B1-B4); docs/map/frontend.md (F1-F3);
keyboard-page.ts (F1 F2). Ownership kept otherwise (extra: B2 INDEX.md, B4 backend/CLAUDE.md, F3 frontend/CLAUDE.md, B3 Archives.java test helper).

Known issue: `AlbumServiceTest.removeDeletesUnderTheCatalogReadLock…` is a phase-0 race (recount thread may log before
`blob.remove` once `remove` drops the read lock); failed twice in B3's full runs, passes alone. Fix in integration.
Worktree gotcha: `gradle/wrapper` jar and native DLLs are gitignored; sessions copied them from the main checkout
(untracked). Use `devtools/gradle-9.8.1/bin/gradle` or copy them in.

Integration items (B5) found in the reports:
- B2 ⇄ B3 contract: B2 expects `Unpacker.unpack` to return hashes as read from the archive keyed by written name;
  B3 returns read-back hashes of the written files (per its brief). Pick one (simplest: B2 hashes written files itself,
  compares with B3's `Packers.hashes(archive)` / pack-time hashes). `Packer.pack` must name entries exactly as given.
- Duplicate crypt code: B2 `ops/Crypt` (own decrypt copy + archive rewrite `Crypt.view`) vs B3 `CryptRewriter` +
  public `Cry.decrypt`. Keep one; B3's `Cry.decrypt` lets `ops/Crypt` drop its copy.
- B3 7z packer may leave `*.spill` files next to the target on a crash; B2 recovery should delete them.
- Phase-0 files: `AlbumService.patch(id, Function<Album,AlbumPatch>)` (B1: read-modify-write race in CatalogApi text
  edits); `AlbumService.setFlags(albumId, name, flags)` (B4: photoMeta flag write-back); `CatalogRepository.photos(listId,
  offset, limit)` + count (B4 runs SQL via JdbcTemplate until then); backend/CLAUDE.md: add `catalog-backfill` thread,
  drop the two `LIKE '% tag %'` sentences (CLAUDE tags line + JdbcAlbumRepository Javadoc); PLAN "Open after phase 0"
  bullet still lists B1/B4 items; the frontend mock lacks B1 semantics (derived always present; `under`/derived `albums`
  = shared count capped 100).
- F4 wiring (frozen files): app.html `[selection]="store.selection"`, `(contextMenu)` on tile-grid, `[selectionMenu]`
  on location-bar, move `<app-copies-popover/>` from Topbar to app.html; album-card `.check { z-index: 1 }` (corner
  hidden by scrim) and hover actions covering the ×N badge; `EntityTree.contextMenu` output `{kind, entity|null,
  selected, x, y}`; `CopiesPopover.deleteRequested`; `App.deleteAlbums(ids)` no-op; `EntityCardView.showPhotos()`
  placeholder; `SettingsDialog.open(page?)` + `thumbnailer:settings` CustomEvent (F2 "Edit targets…"); `viewerSortDesc`
  pref; refetch settings after a root-rename `opResult`; F2 metadata button enabled for flags 0 or 3; B4 shapes:
  `photoMeta → {type:"photoMeta", rows:[{dir,tag,value}]}`, `albumText → {type:"text", name, text}`.
- B3 verdict: vendored 7-Zip-JBinding writes 7z and zip; only `setLevel(0)` (Copy, non-solid) — `setSolid`/
  `setThreadCount` reset the level. STORED zip entries and crypt members held whole in memory one at a time.
- B2 unverified: real second volume, `AtomicMoveNotSupportedException` fallback, key-less recovery for rar/iso → zip.
- B4: `thumbnailer.genres` property (empty default) drives file-name genre words; `Header.meta` 0/1/3 → flags.


Phase 2 launched 2026-10-18 (own worktrees from f810e23): f4-actions local_7a6af17b-66ae-4f15-8604-30e7de86359b (Fable xhigh,
frontend only) · b5-integration local_0f1466aa-c35a-441e-ad9d-f9ae06bb7ac5 (Opus xhigh, backend only). Next: check each
branch when it reports, merge B5 then F4 into main on the owner's say-so, then phase 3 (Codex reviews, `:spi`, docs).


Phase 2 merged 2026-10-18: B5 `c16326d`, F4 `08f001a`, docs `1dbda5e`; every track branch is merged into main (none unmerged),
nothing pushed. Merge checks: `:backend:test` 139 (1 skipped), `ng build` 549 kB, `ng test` 76, full build, live smoke on
`backend-demo` (PACK → zip revision 2, UNPACK → folder revision 3 with the same id, `opResult` events, album context menu).
Open after phase 2: PLAN.md State bullet. Next: phase 3 — Codex reviews per track (gpt-6-astra high B1/B2, gpt-6.1-sol
xhigh B3/B4 + one UI review), `:spi` split + sample plugin, docs pass; the nine `.claude/worktrees/*` can be cleaned up.


Phase 3 (2026-10-18): Codex reviews on merged main via workflow wf_049165ac-5b0 (B1/B2 gpt-6-astra high, B3/B4/UI gpt-6.1-sol
xhigh), each verified by a t4 reader: B1 12, B2 20, B3 13, B4 11, UI 32 findings; verdicts in the scratchpad
`phase3-verdicts.json` / `.md` (most confirmed; highs: replay deletes photo marks, recovery/verification holes, staging-file
clobbering, duplicate archive names, stale settings draft removing roots, "Delete unused" stripping hidden albums…).
Fixes: workflow wf_af68399c-785 (four t4 agents in their own worktrees, one per backend track, commit on their branch; shared
rule for duplicate archive names: containers fail closed, ops refuse, compare says differing) + one t6 agent fixing the UI in
the main checkout (no commit). Then: merge the four branches, run suites, commit; then the `:spi` split (catalog_plan §5 last
step: Gradle subproject `:spi`, package `thumbnailer.spi` with AlbumContainer+Item+EntrySink, Entry, AlbumMeta, Kind,
Extraction, MetadataExtractor, CoverStrategy; `AlbumMeta.effective()` public; hashing defaults → backend `Stamps`;
`Containers.normalize` not referenced from the SPI; sample `:plugins:names` with the file-name parser, bundled into the jar;
t2/t3), then PLAN/SPEC/README/catalog_plan docs pass, full build + smoke, commit.


Phase 3 progress: UI fixes committed 09a762c (27/32); backend fix branches merged 2199706/679d307/049c0a6/8f57210, map
reconciled 4ae7524; backend 171 tests green. Integration agent (t4, main checkout, no commit) implements: entities.unused,
preflight.keyVerified, reveal.path, compareCopies subdirs/excluded → differing, Steps rollback keeps a foreign .part,
TarStream link entries refused, index with the operation key (Containers.open with key, Reindexer/Steps), AlbumService.setFlags
(Entry read). Then commit, then :spi split (t3), then PLAN/SPEC/catalog_plan docs, full build + smoke, commit. Open (owner):
remove-alias rule (refuse while in use?); deferred: scan quarantine of journaled paths, photos N+1 batching, COPY_PHOTOS
single pass, Crypt.view streaming, UI re-preflight on policy change, mock FOLDER_* notes.


Phase 3 closed 2026-10-18: integration a598713, :spi split + docs a3f6edc. Checks: backend 171 + plugin 4 tests, ng test 92,
full build, jar smoke (plugin loaded under Boot, entities.unused, reveal path error, CRYPT preflight). Everything is on
main, nothing pushed. Left: PLAN "Open after phase 3" (deferred small items + the remove-alias owner decision);
13 worktrees under .claude/worktrees (7 phase-1, 2 phase-2 app sessions, 4 workflow) can be cleaned up.


How to start: `mcp__ccd_session__start_session` with `initiation: "user_asked"`, `context: "fresh"`,
`use_worktree: true`, `branch_name: catalog-<track>`, `model: "opus"` (backend only; omit for Fable),
`effort` as above, `background` = the COMMON BACKGROUND below, `prompt` = the track's brief. Merge order
afterwards: B3 → B1 → B4 → B2 → F1 → F2 → F3, then phase 2 (F4 actions, B5 integration), phase 3 (Codex reviews,
`:spi` split, docs).

## COMMON BACKGROUND (the `background` field, same for every track)

Thumbnailer (C:\work\thumbnailer, Java 27 / Spring Boot 4 backend + Angular 22 frontend) is growing from an
album organizer into a catalog of linked entities (models, studios, photographers, genres, tags, favorites lists)
with confirmed, journaled file operations on the library and a reworked viewer. The design is catalog_plan.md
(reviewed by Codex and three critics; Windows/JDK facts in §4.3 come from probes on this machine). Phase 0,
commit 1ac4fa5 on main, laid the backbone: schema, records, wire contract (docs/ARCHITECTURE.md §1.1 and the
"Catalog, operations and photos" table of §3), one admission gate (index/Gate), Reindexer with asserted identity,
AlbumService quiesce/relocate/rebase/removeBelow, Containers.openRaw, AlbumContainer.extract, ops/ records and
interfaces, Catalog with its read-write lock, the mock emulation and the UI shells. Seven phase-1 tracks run in
parallel, each in its own worktree with a strict file-ownership matrix (catalog_plan.md §8 phase 1 + "Shared-file
matrix"); shared files are frozen; a change needed outside your files goes into your final report, never into
those files. Repo rules: root CLAUDE.md, backend/CLAUDE.md or frontend/CLAUDE.md (invariants, conventions),
docs sync in the same change (.claude/rules/docs-sync.md). Commit to your branch when green; never push; never
touch main. Reviews happen later by Codex.

## B1 catalog — `b1-catalog`, branch `catalog-b1`, opus xhigh

You are track **B1 catalog** of catalog_plan.md §8 phase 1. Work only in your worktree; commit to your branch when green; never push, never touch main.

Read first: catalog_plan.md §2 (all of it, especially §2.5 resolution and §2.7 concurrency), §3.1–3.6, §6, §8 (your row + the shared-file matrix); docs/ARCHITECTURE.md §1.1 and the "Catalog, operations and photos" table of §3 (the contract phase 0 implemented: keep every wire name; rows marked "(B1)" are yours); backend/CLAUDE.md invariants; docs/map/backend.md lines for catalog/, store/jdbc, web/ws/CatalogApi.

Files you own (edit nothing else; if you need a change elsewhere, describe it in your final report): backend/src/main/java/thumbnailer/catalog/** , store/jdbc/JdbcCatalogRepository.java, web/ws/CatalogApi.java, and in store/jdbc/JdbcAlbumRepository.java ONLY the `Where` class and `facets()` (plus their tests), docs/map/backend.md lines of those files, SPEC.md rows K1/K2/K5/K8 evidence, PLAN.md one state bullet for B1.

Scope (what "done" means):
1. `Catalog.resolve` (today returns `Resolution.NONE`): the rule of §2.5 — effective text of model/studio/photographer (name lists via `Names`), tags (already normalized by `AlbumMeta.tag`) and genres → whole-string alias lookup first, then split; `Catalog.norm`; miss → insert-or-get with the read→write lock escalation already sketched in `Catalog` (auto = true for MODEL/STUDIO/PHOTOGRAPHER/GENRE, false for TAG); two names on one entity = one link; `alias` on the link = the token as written when it differs from the primary name; `Resolution.aliasText` = every alias of every linked entity for the `search` column. Runs inside `Catalog.resolving` (phase 0 already holds the read lock across resolve + `repo.save`). New auto entities announce `catalogChanged { kinds }` (publish after the save, not under the lock).
2. `backfill()`: when `meta.catalog_version < 1`, resolve every album in batches of 1000 with plain SQL through the repository (no `commit`, no events, no revision), then `recount()`, set the marker; run it from Catalog's own `ApplicationReadyEvent` listener holding the gate as SETTINGS (retry later if the gate is busy).
3. `merge(from[], into)` (§2.5 Merge row: collision deletes incl. entity_link pairs between A and B, aliases + primary names → B, profile B wins / A fills, rating max, auto = and, recount B, `search` recompute of affected albums as a column-only UPDATE, `catalogChanged`); rename via `save` already keeps the old name as an alias — make sure `search` of its albums is recomputed on rename and alias add (column-only).
4. `creditAs(modelId, studioId, alias, roles)` and `deleteEntities` as the two-step flows of §2.5 (catalog write first, lock released, then per-album `patch`/`unlink` through `AlbumService`; `albumChanged` per rewritten album).
5. `link`/`unlink` for roles 1–5: effective list ± the entity's name (explicit `""` when the list becomes empty), through `AlbumService.patch`; count = albums actually changed.
6. `entities` with `under` (Studio › its models, Model › her studios, Photographer › studios, Genre › models: albums' co-links), `entityCard.derived` per kind (ARCHITECTURE §3 `entity` row: albums, models, studios table [{entity, aliases, genres, albums}], photographers, genres, tags; curated vs derived as §7.3 describes).
7. `Where`: `model`/`studio`/`photographer`/`tags`/`genres` filters resolve through `alias` to ids and become `EXISTS` on `album_link` (unresolvable → no match); keep the `tags` column write as is but the filter no longer uses `LIKE '% tag %'`. `facets()` from `album_link` (one `GROUP BY role, entity_id` over the matching albums, primary names, top 50 per role, `genres` included, hidden excluded).
8. `compareCopies(a, b)`: both containers through `Containers.openRaw`, every entry's bytes compared (streamed) → `comparison { identical, differing }`.
9. Tests: `CatalogTest` (resolution incl. the race "two albums, one new name" with two threads, whole-string-first, explicit empty stops re-linking, alias on the link, aliasText), merge/rename/credit/delete, filters and facets in `JdbcAlbumRepositoryTest`, `ScanTest` additions where the full context is needed (auto entities after a scan; counts after hide; backfill on an old database). Whole suite green: `export JAVA_HOME=/c/work/thumbnailer/devtools/jdk-27; ./gradlew -PskipFrontend :backend:test` (background + wait when long).

Invariants to keep (backend/CLAUDE.md): AlbumService is the only index writer; lock order stripe → catalog, never back; catalog writes never take an album stripe (text changes go through patch/unlink as a separate step); `catalogChanged` never bumps revision; `UNDER` untouched; `entity.albums` changes only inside `JdbcAlbumRepository.save`, `delete`, `setListLink` and `recount()`.

Docs in the same commit: map lines, ARCHITECTURE rows that say "(B1)" become real (edit only those parentheses), SPEC K rows, one PLAN bullet. Final report: what landed, test counts, anything unverified, requests to other tracks (B2 ops, B4 extraction, F3 cards) with exact file/field names.

## B2 ops core — `b2-ops`, branch `catalog-b2`, opus xhigh

You are track **B2 ops core** of catalog_plan.md §8 phase 1. Work only in your worktree; commit to your branch when green; never push, never touch main.

Read first: catalog_plan.md §4 (all: 4.1 principles, 4.2 gate, 4.3 open handles and the probe facts, 4.4 raw access + album boundary + containment, 4.5 the operations table, 4.6 re-index with identity, 4.7 manifest/journal/recovery, 4.8 conflicts and space), §6 (`preflight`, `op`, `opProgress`, `opResult`), §8 your row and the shared-file matrix; docs/ARCHITECTURE.md §3 rows `preflight`/`op`/`cancelOp` and the `Op` type; backend/CLAUDE.md (invariants: ops is the only library writer, index updates only through AlbumService/Reindexer, gate, quiesce, openRaw); docs/map/backend.md for ops/, index/Reindexer, album/AlbumService (read the current signatures: `FileOps`, `Op`, `OpProgress`, `OpResult`, `Preflight`, `Hashes`, `pack/Packer`, `pack/Unpacker`, `Reindexer.reindex/scan`, `AlbumService.quiesce/quiesceBelow/release/busy/relocate/rebase/removeBelow/remove`, `Containers.openRaw`, `AlbumContainer.extract`, `Cry.encrypt/plainName`, `Disks` lanes, `Settings.locate`).

Files you own: backend/src/main/java/thumbnailer/ops/** EXCEPT ops/pack/** (B3's), index/Reindexer.java, web/ws/OpsApi.java, their tests, map lines, SPEC O2/O3 evidence, one PLAN bullet. Do not edit AlbumService, the repositories, Settings, the containers or ScanService: if you need something there, write it in the report.

Scope (done = every row of §4.5 except the packer/crypt internals works end to end on a real directory tree, journaled and recoverable):
1. Worker thread `ops`, one item at a time in a disk turn (`Disks` lane of the album's root, SCAN use); progress ≤ 4/s; cancel between items; `close()` finishes or rolls back the current item.
2. `Manifest` and `Journal` records + files (§4.7 layout under `<library-dir>/ops/`), phases `copying → verified → disposing → installing → indexing` (+ `rebasing`), per-file journaling for in-place CRYPT, atomic moves journaled as `installing` with `tmp = src`; `CHECKPOINT SYNC` (via a `JdbcClient` injected into FileOps is acceptable) before a journal line is removed; recovery at start exactly as the §4.7 table, including the key-less CRYPT rule.
3. Operations: MOVE (same volume atomic with the existence check right before the rename — `ATOMIC_MOVE` replaces silently on Windows; other volume copy to `.part` with hashing, verify by read-back hash, delete source, rename), COPY (+ scoped scan via `Reindexer.scan`), DELETE (confirmed list; directory albums whole), PACK/UNPACK/REPACK/CRYPT orchestration against the `Packer`/`Unpacker` interfaces and `Cry.encrypt`/`plainName` (B3 fills the implementations in parallel; until its branch lands, write a minimal test-only zip packer/unpacker in your tests so your flows are covered), COPY_PHOTOS (decrypted export of `.cry`, identical-skip by size + hash, `name (2).ext`), FOLDER_RENAME (case-only through a temporary name), FOLDER_MOVE, FOLDER_COPY, FOLDER_DELETE; index updates through `relocate`/`rebase`/`removeBelow`/`remove`/`Reindexer.reindex` in the order verify → delete source → install → re-index; the directory-album boundary rules of §4.4 (own files only for pack/crypt; whole directory for move/copy/delete; preflight notes).
4. Containment and conflicts (§4.4, §4.8): entry-name validation, no symlink following, destination inside a source, overlapping sources, destination re-validated per item, Skip/Rename policies, `AtomicMoveNotSupportedException` fallback, space from the manifest's byte total per file system with a 64 MiB margin, pre- and per-item checks.
5. Open handles (§4.3): `quiesce`/`quiesceBelow` before touching files, `release` after; retry 5 × 200 ms on `FileSystemException`, then skip "in use".
6. `preflight` refined per §4.5/§6: do/skip with reasons ("already packed", "already unpacked", "would not be an album", "contains N nested albums", "folder keeps N sub-folders", "leaves the library", "in use"), consequences, warnings, bytes/free; `free` through `locate(normalize(target))`.
7. Tests (`FileOpsTest`, plain JUnit with @TempDir and the real services where cheap, `ScanTest` style where the context is needed): same-volume and forced-copy moves, conflict policies, nested albums, directory album with sub-folders, phase-injection hooks that throw at each journal transition followed by recovery (files and index both right), locked source (an open `RandomAccessFile`: Windows refuses the rename), existing destination, cancel between items, encrypt + flatten with a pinned cover and marked photos (through your test packer). Whole suite green: `export JAVA_HOME=/c/work/thumbnailer/devtools/jdk-27; ./gradlew -PskipFrontend :backend:test` (background + wait when long).

Docs in the same commit: map lines for ops/*, ARCHITECTURE §3 `preflight`/`op` rows' "(B2 …)" parentheses become real, SPEC O2/O3, one PLAN bullet, and a short "Operations" paragraph in README.md (what is deleted when, no Recycle Bin, journal location). Final report: what landed, test counts, what is unverified (e.g. real cross-volume), requests to B3/F4 with exact names.

## B3 packers + cry — `b3-packers`, branch `catalog-b3`, opus high

You are track **B3 packers + cry** of catalog_plan.md §8 phase 1. Work only in your worktree; commit to your branch when green; never push, never touch main.

Read first: catalog_plan.md §4.4 (raw access, one-pass extract), §4.5 rows Pack/Unpack/Repack/Encrypt/Copy photos (formats: `auto` = zip STORED for images + DEFLATED for text, 7z = method Copy, non-solid; flatten rule; verification by reopening and hashing), §8 your row; backend/CLAUDE.md (natives: load only through `natives.LibraryLoader`; 7-Zip calls its streams from its own threads; a `sequential()` archive is read in one pass, never per file); docs/map/backend.md lines for ops/pack, source/SevenZipContainer, source/Cry, source/AlbumContainer, ops/Hashes; the current `Packer`/`Unpacker` interfaces (keep their signatures; B2 calls them). A probe on this machine showed the vendored 7-Zip-JBinding exposes IOutCreateArchive7z/Zip with out-archive JNI symbols (not yet exercised at runtime).

Files you own: backend/src/main/java/thumbnailer/ops/pack/** , source/Cry.java (the `encrypt`/`plainName` side only), source/SevenZipContainer.java (an `extract` override + a package-visible accessor so the out-archive side initialises through the same `Native.init()`), their tests (`PackersTest`, `CryTest` additions, `SevenZipContainerTest` additions), map lines, SPEC O2 evidence for the packer part, one PLAN bullet. Nothing else.

Scope (done = every function below is implemented, byte-verified in tests, and the whole backend suite is green):
1. `ZipPacker`: JDK `ZipOutputStream`, STORED entries for images (CRC32 and size computed while hashing the bytes that are read anyway) and DEFLATED for other files, entry times from the source items, one pass through `raw.extract(names, sink)`; returns the per-entry wyhash map (`Hashes`).
2. `SevenZipPacker`: 7-Zip-JBinding `IOutCreateArchive7z` (`openOutArchive7z`), method Copy / level 0, `setSolid(false)`, multithreading off, entries streamed from `extract`, same hash map; prove at runtime in a test that the vendored native build writes 7z and zip. If the native refuses out-archives, say so in the report and keep zip as the only format (the plan allows it: `auto` = zip).
3. `Packer.hashes(archive)`: reopen through `Containers.openRaw` (zip or 7z) and hash every entry (one pass for a solid archive).
4. `Unpacker.unpack(raw, dir, nameMap)`: `extract` in one pass into `dir` (created by the caller as `<name>.part/`), structure preserved, the flatten rule (an archive whose only top-level entry is a directory of the archive's own base name → its content at the top, `nameMap` old → new), entry-name containment (refuse absolute, drive-prefixed, `..`), times preserved, returns hashes of the written files read back from disk.
5. `SevenZipContainer.extract`: one `Pass` delivering every named entry's full bytes in packing order (like `heads` with no limit), fail-closed (an unreadable entry fails the whole call), the sink possibly on 7-Zip's threads (no thread-confined state).
6. `Cry.encrypt` streaming: `encrypt(InputStream in, long size, String name, byte[] key, OutputStream out)` in 64 KiB chunks (the header needs `size` up front), byte-exact with the golden files (`cry-golden.tsv`) and with the existing array version (keep it as a thin wrapper for tests); a name-length field over 32767 must throw, never clamp; `plainName(head, key)` for a decrypt's output name. New files are named `<original name>.cry` (`photo.jpg.cry`). Archive rewrite helper (`CryptRewriter` in the packer package): streams an archive through `extract` into a new zip, encrypting plain images / decrypting `.cry` members per direction, skipping members already in the target state, keeping other files; returns the name map and the hashes of plaintext bytes so B2 can verify "decrypt(result) == source" without a second key.
7. `PhotoNames` (tiny): the copy-photos naming rule — identical (same size, same hash) → skip; same name, different bytes → `name (2).ext`, `(3)`…; used by B2.
8. Tests: round trips folder → zip → folder, folder → 7z → folder (bytes identical, times within zip's 2 s resolution), flatten, containment refusal, STORED vs DEFLATED choice, hashes equal between pack and reopen, solid 7z one-pass extract order and fail-closed behaviour (write test archives with `source/Archives.java` as `SevenZipContainerTest` does), streaming encrypt golden-exact + big payload, rewrite in both directions with mixed members, naming rule. Whole suite: `export JAVA_HOME=/c/work/thumbnailer/devtools/jdk-27; ./gradlew -PskipFrontend :backend:test` (background + wait when long).

Docs in the same commit: map lines, SPEC O2 evidence (packer part), one PLAN bullet. Final report: what landed, test counts, the 7z runtime verdict, the exact public signatures B2 should call, anything unverified.

## B4 extraction + photos — `b4-extraction`, branch `catalog-b4`, opus high

You are track **B4 extraction + photos** of catalog_plan.md §8 phase 1. Work only in your worktree; commit to your branch when green; never push, never touch main.

Read first: catalog_plan.md §5 (the plugin contract: `Extraction`, `MetadataExtractor`/`CoverStrategy` signatures, beans + ServiceLoader, text budget), §2.6 rows `bio` and `Entry.flags`, §3.3 (`photos`), §6 rows `markPhoto`/`photos`/`photoMeta`/`albumText`, §8 your row and the shared-file matrix; docs/ARCHITECTURE.md §2 rows `image`, `metadata`, `cover`, `index` and the §3 rows marked "(B4)"; backend/CLAUDE.md (extractor and cover SPIs are `@Component`s collected as lists; `Metadata` folds with `orElse`; a solid archive is read in one pass); docs/map/backend.md lines for metadata/, cover/, index/AlbumIndexer, image/Header, web/ws/PhotosApi.

Files you own: backend/src/main/java/thumbnailer/metadata/** , cover/** , index/AlbumIndexer.java (the context build, bio detection, flags; keep its `index(…, nameMap)` signature and the reuse rules), image/Header.java (flags only), web/ws/PhotosApi.java, their tests, map lines, SPEC K6 (photo marks backend part) and O6 evidence, one PLAN bullet. Nothing else (`Entry`, `Album`, `AlbumMeta`, `CatalogRepository.marks/mark` exist from phase 0; `Catalog.marks/mark` too).

Scope (done = the contract of §5 is real, the three photo requests work, and the suite is green):
1. `Extraction` record exactly as §5 (`name, path, kind, images, items, longestDir, sampleImage, texts, container`; `Extraction.of(images)` for callers without a container), built once per index by `AlbumIndexer`: `texts` = `*.txt` members decoded by BOM → UTF-8 strict → CP1251 fallback, ≤ 8 files, ≤ 64 KiB each, ≤ 256 KiB total (read in the album's turn, through `heads`/`extract` for a sequential archive, never per file); `longestDir` = the longest directory name inside an archive (null for a folder album); `sampleImage` = the median image name.
2. `MetadataExtractor.extract(Extraction)` + `default int order()`, `CoverStrategy.pick(Extraction)` + `order()`; `Metadata` and `CoverSelector` collect Spring beans AND `ServiceLoader` providers from the application class path, sorted by `order()`, a failing provider skipped with one warning; `CoverSelector.candidates(entries, pinned)` keeps its signature (wraps `Extraction.of`); `NAME_HINT`/`SMALLEST` become ordinary strategies. `SidecarExtractor`, `FileNameExtractor`, `ExifExtractor` rewritten against the context (same behaviour; sidecar texts from `ctx.texts`; `FileNameExtractor` also fills `AlbumMeta.genres` only if the name clearly carries one of the configured genre words — otherwise leave genres empty). Register the built-ins also through `META-INF/services` so the ServiceLoader path is exercised by a test.
3. `bio`: the first `*.txt` member (by name) whose name contains `bio` or `model` → `Album.bio`; `Entry.flags`: `META_KNOWN | META` from `Header` (an APP1/Exif IFD/`eXIf` seen in the head; `META_KNOWN` alone when the head was parsed and none was found), reused probes keep their flags.
4. `PhotosApi`: `photoMeta` (metadata-extractor over the entry's head, else the whole entry through the container, rows `{dir, tag, value}`; after answering, write the learned flags back into the album's packed entries through the repository — one row — via `AlbumService` if it needs the stripe lock: ask for a tiny `AlbumService.setFlags(id, name, flags)` in your report if no existing path fits, and skip the write-back until then), `albumText` (decoded as in 1, UTF-8 out), `photos(listId, offset, limit)` (`photo_link` join `photo` join `album`, visible albums only, `index` resolved from the packed entries).
5. Tests: `ExtractionTest` (texts budget and decoding, longestDir, sampleImage), extractor/cover chains with a ServiceLoader provider in `src/test/resources/META-INF/services`, `Header` flags for JPEG with/without Exif, PNG `eXIf`, TIFF; `PhotosApi` through `ScanTest`'s socket harness or a plain test; existing tests (`FileNameExtractorTest`, `CoverSelectorTest`, `ScanTest`) adapted but not weakened. Whole suite: `export JAVA_HOME=/c/work/thumbnailer/devtools/jdk-27; ./gradlew -PskipFrontend :backend:test` (background + wait when long).

Docs in the same commit: map lines, ARCHITECTURE §2 `metadata`/`cover`/`index` rows (the context, ServiceLoader) and the §3 rows marked "(B4)", SPEC O6 and K6 evidence, one PLAN bullet. Final report: what landed, test counts, anything unverified, requests to other tracks (F2 viewer: exact `photoMeta`/`albumText` shapes; B2: nothing expected).

## F1 UI infra — `f1-ui-infra`, branch `catalog-f1`, fable xhigh

You are track **F1 UI infra** of catalog_plan.md §8 phase 1. Work only in your worktree; commit to your branch when green; never push, never touch main.

Read first: catalog_plan.md §3.7 (selection), §7.1 (context menu, confirm, entity picker), §7.7 (keyboard, library keys), §8 your row and the shared-file matrix; frontend/CLAUDE.md (conventions + the new gotchas on `Selection`, dialog services, `ContextMenu`); docs/map/frontend.md lines for `core/selection.ts`, `core/store.ts` (`store.selection` exists; `albumRemoved` prunes it), `ui/shared/context-menu.ts`, `confirm.ts`, `entity-picker.ts` (phase-0 shells with fixed public APIs: `ContextMenu.open(x, y, items: MenuEntry[])`, `Confirm.ask(opts) → Promise<ConfirmResult|null>`, `EntityPicker.pick(opts) → Promise<Entity|null>`), `ui/tile-grid.ts`, `ui/album-card.ts` (`checked` input and check corner exist; `app.html` already binds `[checked]="store.selection.has(a.id)"`), `ui/location-bar.ts`, `app.ts`.

Files you own: frontend/src/app/ui/shared/context-menu.ts, confirm.ts, entity-picker.ts, ui/tile-grid.ts, ui/location-bar.ts, app.ts (keyboard only), their specs, map lines, SPEC K9 evidence, one PLAN bullet. Do not edit app.html, store.ts, album-card.ts, model.ts, the mock or any other component (F2/F3 own theirs; F4 wires the menus later). If the selection needs a hook in `album-card.ts` or `app.html`, describe it in the report.

Scope (done = the mock dev UI, `npm --prefix frontend start`, shows polished, keyboard-complete infrastructure that F4 can wire actions into without touching your files):
1. `ContextMenu`: fixed-position panel (`.panel`), submenus (`children` as a list or a lazy `() => Promise<MenuEntry[]>`), icons, shortcut hints, `danger`, `checked`, disabled items, separators, keyboard (↑ ↓ → ← ⏎ Esc, type-ahead optional), closes on outside click / scroll / resize, viewport-aware placement (flip near edges), opens from a right-click on tiles (`TileGrid` emits `contextmenu` with the tile id and coordinates; the App wires it later — expose an output), from `Shift+F10` / the context-menu key on the focused tile, and from the location bar's selection chip. Mobile: long-press is out of scope.
2. `Confirm`: native `<dialog>` with title, message, item list (≤ 10 + "and N more", `showAll` scrollable for deletes), the `Preflight` summary (bytes, free space, skipped items with reasons, consequences, warnings), option controls (`select`, `check`, `password2` = two password fields that must match), primary/danger button, Esc cancels, returns the option values; focus trap; works above the settings dialog (top layer).
3. `EntityPicker`: `<dialog>` with a search box over `gw.entities(kind, { text, limit })` (debounced), keyboard list, recent picks (localStorage `thumbnailer.recent.<kind>`), "Create "…"" row when `allowCreate` and no exact match (calls `saveEntity`), returns the entity.
4. Selection in the grid: `TileGrid` emits `select` with modifiers (plain / Ctrl / Shift) so the App can call `store.selection.click(id, order, mods)` — since `app.html` is frozen, make `TileGrid` accept an optional `selection` input (the `Selection` instance) and apply the click rules itself when given, keeping `select`/`open` outputs as today; keyboard: `Ctrl+A` (select all loaded up to `SELECT_CAP`, loading more pages through a `requestMore` output), `Esc` clears (in `app.ts`), Shift+arrows extend; a check corner is already drawn by the card.
5. `LocationBar`: a selection chip ("N selected · Deselect") when ≥ 2 are selected, with a ▾ that opens the context menu for the selection (items come from an input `menuItems: () => MenuEntry[]` that F4 fills); keep every existing behaviour (crumbs, edit, rescan).
6. `app.ts` keys of §7.7 library section (`Ctrl+A`, `Esc` selection first, `Delete` → emits nothing yet: route through a small `App` output-less hook that F4 completes; `H` hide → `store.patch` on the selection via `bulkUpdate` is allowed here since the gateway method exists), without breaking the existing keys; update `ui/settings/keyboard-page.ts` and README's keyboard line (those two lines are yours).
7. Specs for the pure parts (menu placement math, selection click rules already covered — extend for Shift+arrows), `ng build` green within the 600 kB budget, `ng test` green; a manual check in the mock UI (`?mock`) of every dialog and the menu.

Docs in the same commit: map lines, SPEC K9 evidence, one PLAN bullet, keyboard page + README keys. Final report: what landed, bundle size, spec counts, the exact hooks F4 must use (outputs, inputs, service calls), anything unverified.

## F2 viewer — `f2-viewer`, branch `catalog-f2`, fable xhigh

You are track **F2 viewer** of catalog_plan.md §8 phase 1. Work only in your worktree; commit to your branch when green; never push, never touch main.

Read first: catalog_plan.md §7.5 (the whole viewer section), §7.6 (Appearance page: viewer defaults), §7.7 (viewer keys), §3.3 (photo favorites), §2.4 (photo marks are keyed by entry name), §8 your row and the shared-file matrix; frontend/CLAUDE.md; docs/map/frontend.md lines for `ui/album-viewer.ts`, `ui/shared/rating.ts`, `ui/settings/appearance-page.ts`, `core/prefs.ts` (keys exist: `viewerScale`, `viewerSort`, `viewerMinSide`, `slideshowSeconds`), `core/selection.ts` (use one instance for photos, keyed by entry name), `core/gateway.ts` (`markPhoto`, `photoMeta`, `albumText`, `preflight`, `runOp` with kind `COPY_PHOTOS`, `album()` returns `marks`), `core/ops-store.ts`, `core/catalog-store.ts` (`lists`), `core/mock-photos.ts` (the mock answers all of it; the live server answers "not implemented" for `photoMeta`/`albumText` until track B4 lands — show a toast and keep the UI usable), `ui/shared/context-menu.ts` + `confirm.ts` + `entity-picker.ts` (phase-0 shells; F1 polishes them in parallel — use only their public APIs).

Files you own: frontend/src/app/ui/album-viewer.ts (you may split it into `ui/viewer/*.ts` — then add map lines), ui/shared/rating.ts, ui/settings/appearance-page.ts, their specs, map lines, SPEC O5 evidence, one PLAN bullet, the viewer part of `ui/settings/keyboard-page.ts` and README's keyboard line (the viewer keys only; F1 edits the library keys — keep the edits on separate lines to ease the merge). Nothing else.

Scope (done = §7.5 works in the mock UI, compact and keyboard-complete, and nothing of the current viewer regressed):
1. Header: bio button when `album.bio` (side drawer with the text from `albumText`, monospace, wrapped, copy), sort menu (name · size · date · dimensions · rating · random, each with a direction toggle; random with reshuffle), filter (min longer side with presets 0/800/1200/1800, min bytes), scale menu, full screen, rating, show in folder, details — compact, no wasted space.
2. Grid: rating badge and heart on marked photos; hover actions cover · view · favorites · rate (1–10 strip); right-click menu (View · Use as cover · Rate ▸ · Favorites ▸ [lists with check marks, New list… via `EntityPicker` with `allowCreate`] · Copy to ▸ targets 1–0 from `settings.copyTargets` (+ "Edit targets…" opens the settings dialog on the Operations page) · Show metadata (disabled only when `flags & META_KNOWN && !(flags & META)`) · Show in folder · Copy path); multi-select with `Selection` (Ctrl/Shift) so Copy to and Favorites apply to several photos; Copy to runs `preflight` → `Confirm.ask` → `runOp({ kind: 'COPY_PHOTOS', albumIds: [id], names, target })` and shows the `opResult` summary.
3. Lightbox: scale modes fit / fit large / 1:1 / fit width / fit height (CSS as §7.5 describes), pan by scroll/drag when larger than the viewport, click toggles fit ↔ 1:1, full screen (`requestFullscreen` on the lightbox element, `F11`), slideshow (`Space`, interval `Prefs.slideshowSeconds`, order = current sort incl. random, wraps, thin progress line), caption with rating, heart, copy-to keys, metadata button; metadata dialog = table (dir · tag · value) with a filter box and "Copy all".
4. Keys (§7.7 viewer): `F11`, `Space`, `R`, `S`, `1`–`9`,`0`, `Ctrl+1…0` rating, `F`, `M`, `B`, plus the existing `Esc`/arrows/`C`; `AlbumViewer.onKey` must not clash with `App.onKey` (it already skips viewer routes).
5. Appearance page: viewer defaults (scale, slideshow seconds, min side, sort) bound to `Prefs`; the viewer reloads on `catalogChanged` as well as on a newer `revision`.
6. Specs for the pure parts (sort comparators incl. seeded shuffle, filter, scale-mode CSS class mapping), `ng build` within budget, `ng test` green; a manual pass in the mock UI (`?mock`) of every mode, full screen and the slideshow.

Docs in the same commit: map lines, SPEC O5 evidence, one PLAN bullet, viewer keys in the keyboard page and README. Final report: what landed, bundle size, spec counts, anything unverified (the live `photoMeta`/`albumText` paths wait for B4), requests to other tracks.

## F3 trees + cards — `f3-trees-cards`, branch `catalog-f3`, fable xhigh

You are track **F3 trees + cards** of catalog_plan.md §8 phase 1. Work only in your worktree; commit to your branch when green; never push, never touch main.

Read first: catalog_plan.md §2.5 (text is the truth; display from links; inputs edit raw text), §3.2 (editing), §3.3–3.6 (favorites, hidden + Views strip, trees with multi-select and batch actions, duplicates with copies popover and Compare), §7.2 (sidebar: Browse tab with group-by; the tree context menus are F4's — leave hooks), §7.3 (cards per kind: field schema + relationship sections, derived vs curated), §8 your row and the shared-file matrix; frontend/CLAUDE.md; docs/map/frontend.md lines for `ui/sidebar.ts`, `ui/folder-tree.ts` (reuse its patterns), `ui/details-panel.ts` (phase-0 entity mode shell: `mode`, `entityId`, back arrow, chips), `ui/entity-card.ts` (placeholder), `core/catalog-store.ts` (`card`, `openCard`, `lists`, `entities(kind, opts)`, `copiesOf`, `version`, mutations), `core/prefs.ts` (`groupBy` exists), `core/model.ts` (`Entity`, `EntityCard`, `EntitySave`, `Filters.entities/missing/hidden/duplicates/genres`), `core/mock-catalog.ts` (the mock answers everything incl. `under`, derived sections, merge, creditAs; the live server answers "not implemented" for merge/creditAs/under/derived until track B1 lands — toast and keep the UI usable), `ui/filters-panel.ts`, `ui/topbar.ts`, `core/selection.ts`.

Files you own: frontend/src/app/ui/sidebar.ts, ui/entity-tree.ts (new), ui/entity-card.ts, ui/details-panel.ts, ui/filters-panel.ts, ui/topbar.ts (search chips for `entities`/`missing`/`hidden`/`duplicates`/`genres` only — keep the existing chip code intact), core/catalog-store.ts, their specs, map lines, SPEC K3/K4/K7/K8 evidence (UI part), one PLAN bullet. Do NOT edit `ui/location-bar.ts` (F1 owns it): show the entity scope as a search chip in the topbar and in the Browse panel header instead. Do not edit app.html, store.ts, album-card.ts, the mock, model.ts.

Scope (done = §3.5/§7.2/§7.3 work in the mock UI with the elegance of the existing panels, and the album details panel keeps every current feature):
1. Sidebar: first tab **Browse** with a group-by selector in the header (icon button + menu: Folders · Studios · Models · Photographers · Genres · Favorites; `Prefs.groupBy`), `app-folder-tree` for Folders, `app-entity-tree` otherwise; below the tree the fixed **Views** strip: Hidden (count, "Unhide all" via `bulkUpdate` on `{ hidden: true }` query) and Duplicates (count) → `filters.hidden = true` / `filters.duplicates = true`.
2. `EntityTree`: rows All · Unassigned (`filters.missing = kind`) · entities from `entities(kind, { byCount, offset, limit })` with name, stored count, auto dot (dimmed), filter box (server prefix search when paged), sort by name/count, "N more…" paging, a second level when `under` answers (expand = `entities(kind2, { under: id })`), multi-select with `Selection` (Ctrl/Shift) and a small action strip when ≥ 1 selected: Confirm · Merge into… (`EntityPicker` of the same kind) · Delete… (`Confirm.ask`) acting on the selection; header "Delete unused…"; plain click = scope (`filters.entities = [id]`); right-click emits an output with the row for F4 to wire menus; keyboard like the folder tree.
3. Scope chips in the topbar: `model: <name>` / `studio:` / `by:` / `genre:` / `#tag` / `list:` for `entities`, "unassigned <kind>" for `missing`, "hidden", "duplicates"; removing a chip clears that filter.
4. `EntityCard` (right panel, entity mode, 400 px): one component driven by a field schema per kind (§7.3 table) with shared controls (text, number with unit hint, date/year, rating, chips with add/remove, textarea), aliases chips, header with the newest album's cover (from `entities` scope search: first album of `filters.entities=[id]`, one `search` call), relationship sections per kind: Studios table for models (derived rows marked "from albums" + curated rows editable: Add studio…, Credited as… via `creditAs`), genres (curated chips + derived), hashtags (curated entity_link → tag chips), photographers, models/roster for studios, counts, "Show albums" (sets the scope) and "Show photos" for lists (placeholder until F4's photo grid); every field saves on change through `saveEntity` (debounced for text), collision error → toast with "Merge into X?" (opens the picker). Age computed from `born` when a year parses; height/weight shown with ft/in and lb.
5. `DetailsPanel`: album mode inputs for model/studio/photographer/genres as raw text name lists with datalist suggestions from `entities(kind, { text })`, chips of the linked entities below each input (click → card, × → `unlink`), a shot-date input (`patch { date }`, clear = `""`), "N copies" line with a popover from `CatalogStore.copiesOf`/the badge: list of copies (`copies(albumId)`) with Open · Show in folder · Hide, and **Compare** (`compareCopies`) before offering Delete (which F4 wires; leave a hook).
6. `FiltersPanel`: Genre select (facets `genres`), "Show hidden" switch.
7. Specs for pure parts (schema → fields mapping, age/unit conversions, chip parsing), `ng build` within budget, `ng test` green; manual pass in the mock UI (`?mock`).

Docs in the same commit: map lines, SPEC K3/K4/K7/K8 evidence, one PLAN bullet. Final report: what landed, bundle size, spec counts, anything unverified (live paths that wait for B1), hooks left for F4 (outputs, service calls) with exact names.
