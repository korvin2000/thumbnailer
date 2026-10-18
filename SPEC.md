# SPEC — what Thumbnailer does

[↑ INDEX](INDEX.md) · why: [INTENT.md](INTENT.md) · exact contract (fields, messages, routes): [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · gaps: [PLAN.md](PLAN.md)

Status: ✅ done · ◐ partial · ○ missing. Evidence names the test or the owning file. Keep IDs stable; reference them in
PLAN items and commit messages. When behaviour changes, update the row here and the contract in ARCHITECTURE.

## L — Library and albums

| ID | requirement | status | evidence |
|---|---|---|---|
| L1 | The unit is an album: a directory holding ≥ `min-images` (2) images directly, or an archive holding images — zip (JDK), and through 7-Zip RAR, 7z, tar, ISO and tar inside gzip/bzip2/xz/Z (`.tar.gz .tgz .tar.bz2 .tbz2 .tar.xz .txz .tar.Z .taz`); nested dirs are separate albums; an unchanged archive rescans by `stat` | ✅ | `index/AlbumDiscovery`, `source/SevenZipContainer`, `ScanTest`, `SevenZipContainerTest` (RAR/ISO/Z untested: 7-Zip cannot write them) |
| L2 | Images: jpg jpeg png gif webp bmp tif tiff; JPEG through libjpeg-turbo (CMYK/12-bit fall back to ImageIO + TwelveMonkeys), WebP stills through libwebp (animations: first frame through ImageIO + TwelveMonkeys), the rest through ImageIO; corrupt entries skipped | ✅ | `source/AlbumContainer.IMAGE_EXTENSIONS`, `image/Thumbnailer` |
| L2a | xanderView's encrypted `.cry` images (in folders and archives) decrypt byte-compatibly with a password set in Settings → Folders; only the derived AES key is kept (`cryKey`), never the password, and only in memory for the session (clients see only a fingerprint) (forgotten on restart or 10 s after the last client leaves, never in `settings.json`); without a key `.cry` files are not images for new indexing (a folder of only `.cry` files is no album), but albums already indexed with a key stay as they are and only their on-demand reads fail until the password is entered again; setting a password, or a different one, re-indexes exactly the affected albums on the next scan; thumbnails and originals decrypt on demand at once; a wrong password skips the files with one warning per album | ✅ | `source/Cry`, `source/CryTest` (golden files written by xanderView), `ScanTest.encryptedImagesFollowThePassword` |
| L3 | Album stats: image count, bytes, average and max resolution, shot date, modified/indexed time | ✅ | `index/AlbumIndexer` |
| L4 | Album id is stable across re-index, moves and renames | ✅ | `ScanTest.renamedAlbumKeepsItsIdentity…` |
| L5 | The library on disk is never modified by the app | ✅ | design rule (no writers in `source/`) |

## S — Scanning and index

| ID | requirement | status | evidence |
|---|---|---|---|
| S1 | Scan roots and exclusions (`*` globs, global and per root) are configured in the UI and persisted | ✅ | `settings/`, `ui/settings/folders-page.ts`; Save disabled while a scan runs and roots/path map changed (`SettingsDialog.blocked`); the server refuses such a save too with the gate's blocker (`A scan is running`, `index/Gate`, `AlbumSocket`) |
| S2 | Incremental: unchanged albums (same path + signature) are skipped; changed ones re-indexed; unchanged photos inside a changed album are not re-probed, an unchanged cover is not re-rendered | ✅ | `index/Reconciler`, `AlbumIndexer` |
| S3 | Moves/renames detected by content id (image names + sizes); id, rating and edits kept | ✅ | `ScanTest.reconciles…`, `…renamed…` |
| S4 | Removal only after a complete, uncancelled walk of a reachable scope (a root, or a folder below one), and only of albums at or below it; unplugged roots untouched. A removed album leaves a tombstone: one that comes back with the same content (moved out of a rescanned folder, restored from a backup, or a copy of a deleted album) keeps id, rating, edits and pinned cover; the tombstone is consumed by the save that adopts it, so a failed save keeps it | ✅ | `ScanTest.reconciles…`, `…scanningAFolderPrunesOnlyBelowIt`, `…aRemovedAlbumThatComesBackKeepsItsIdentity`, `JdbcAlbumRepositoryTest.deleteLeavesATombstone…` |
| S5 | Roots must not overlap; widening a root (`A:/pics/x` → `A:/pics`) or narrowing it keeps the albums still inside (re-rooted, not re-indexed), the rest are removed; also converged at every scan start | ✅ | `ScanTest.overlappingRootsAreRejected`, `…wideningOrNarrowingARootKeepsTheAlbumsStillInside`, `AlbumService.retainRoots` |
| S6 | Async scan with live progress (≤4/s), cancel, one scan at a time, optional scan on start; one virtual thread per album, ≤ `index-threads` albums and ≤ covers `threads` (settings, default 4) cover renders at once | ✅ | `index/ScanService`, `AlbumService.renderCover`, `LimiterTest` |
| S6a | Reads follow the storage: each folder's disk is detected (SSD; HDD, network and unknown read as HDD) or set per folder (Settings → Folders: Auto/SSD/HDD); an HDD serves one reader at a time (FIFO, `hdd-readers`) while decode, resize and encode run in parallel from a bounded queue, an SSD is not gated; browsing renders queue apart from the scan; probes come from the file head for JPEG/TIFF/PNG/GIF/BMP/WebP; heads are read in on-disk order, a solid archive only in packing order and in one pass, also in the viewer (one gallery pass per album, paced by what is shown); roots on different disks are scanned at once; on an HDD the scan pauses while the viewer reads the same disk, and a compressed solid archive decompresses without holding the disk. Measured on the owner's USB HDD against the previous code: cold scan of 11 albums 9.5 s (was 12.5 s), a 113-photo folder gallery 0.86–1.05 s, p95 ≤ 69 ms (was 1.26 s, p95 377 ms), the same as a solid 7z 2.9–3.1 s (was ~14 thumbnails in 2 min) ([plan](hdd_ssd_fix_goals.md)) | ✅ | `source/Disks`, `album/Gallery`, `DisksTest`, `GalleryTest`, `ContainersTest`, `SevenZipContainerTest`, `HeaderTest`, `ScanTest` |
| S7 | Index survives restarts. Several libraries (each its own settings, database and covers): `thumbnailer.data-dir` is the home holding `libraries.json` (known, active); switching (refused for a dir whose `settings.json` does not parse) restarts the application in-process, falling back to the previous library if the new one cannot start; `thumbnailer.library-dir` given on the command line or environment pins one (switch and forget refused) | ✅ | `settings/Libraries`, `LibraryLocator`, `ThumbnailerApplication.restart`, `LibrariesTest` (restart: jar smoke run; fallback not exercised) |
| S8 | A library moved to another drive, prefix or machine is found through a prefix path map (`pathMap [{from, to}]`, longest match); stored paths, ids and covers stay, nothing is re-indexed; exclusions keep matching stored paths; root status shows the mapped location | ✅ | `Settings.locate`, `ScanTest.pathMapFindsAMovedLibraryWithoutReindexing`, `ExclusionsTest` |
| S9 | Scan one folder: `scan {paths}` takes roots or folders below them (else an error); nested paths fold into the outer one; an existing folder is spelled as the file system spells it (no duplicates from a typed case) | ✅ | `ScanService.scopes`, `ScanTest.scanningAFolderPrunesOnlyBelowIt`, `…aScopeTypedInAnotherCaseUsesTheStoredSpelling` |
| S10 | Folder tree data: child folders of any stored path with album counts at or below and a nested flag, derived from album paths (indexed `album_path`) | ✅ | `AlbumRepository.folders`, `JdbcAlbumRepositoryTest.foldersCount…` |
| S11 | Library statistics (albums, images, sizes, last indexed, cover and database live/file bytes, settings size, version) and Optimize (cover store compaction now, H2 `SHUTDOWN COMPACT` at the restart); library operations refused while a scan runs; once a switch or an optimize is accepted no scan starts and further ones are refused until the restart | ✅ | `web/ws/Library`, `JdbcAlbumRepositoryTest.stats…`, `TuplBlobStoreTest.compaction…` |
| S12 | Local folder listing for the picker: drives, visible readable subfolders, nearest existing folder with an error for a bad path | ✅ | `web/ws/Browse`, `BrowseTest` |

## M — Metadata

| ID | requirement | status | evidence |
|---|---|---|---|
| M1 | Fields: name, model, studio, photographer, description, shot date, tags | ✅ | `album/AlbumMeta` |
| M2 | Sources in priority order: sidecar (`album.json`/`album.txt`/single `*.txt`), file name (studio-release pattern), EXIF (date, Artist) | ✅ | `metadata/`, `FileNameExtractorTest` |
| M3 | User edits override extracted values field by field and survive re-index; explicit empties stick | ✅ | `ScanTest.userEditsSurviveExtractedRefresh` |
| M4 | Rating 0 (unrated) … 10 | ✅ | `AlbumService.patch` |
| M5 | Shot date editable by the user | ○ | `AlbumPatch` has no date |

## C — Covers and thumbnails

| ID | requirement | status | evidence |
|---|---|---|---|
| C1 | Default cover: name hint (`cover`, `poster`, `logo`, `_cv` suffix) else smallest file | ✅ | `CoverSelectorTest` |
| C2 | Cover strategy is extensible | ◐ | `CoverStrategy` SPI exists; chain hardcoded in `CoverSelector` |
| C3 | User can pin any photo as cover and unpin | ✅ | `AlbumService.patch`, `ui/album-viewer.ts` (`C`) |
| C4 | Covers and thumbnails: longer side = the spec's `size` (default 512), proportions kept, never upscaled, EXIF-rotated; JPEG decoded at a DCT scale ≥ 1.5× the target, then an antialiased Lanczos3/cubic resampler (SIMD with the Vector API, else scalar; same bits); covers WebP q75, entry thumbnails JPEG q85 by default; about 3.3x faster per render than before (239 → 71 ms per 24 MP JPEG) and 3.2x the parallel throughput | ✅ | `ThumbnailerTest`, `ResamplerTest`, [scalebench/results.md](scalebench/results.md) |
| C5 | Only covers are cached (KV store, one value per album, tagged with the album state and render spec it shows: a stale or torn value is a miss and re-renders; stores written before rendering was configurable stay valid); entry thumbnails render per request from the files, browser-cached by revision and render epoch | ✅ | `AlbumService.cover/storedCover/thumb`, `ScanTest.coverIsServedOnlyForTheAlbumStateItShows`, `TuplBlobStoreTest` |
| C6 | Rendering is configurable per kind (covers, entry thumbnails): format WebP/JPEG, quality, size, parallel renders; a changed spec re-renders covers on demand and changes image URLs and ETags | ✅ | `image/Render`, `ThumbnailerTest`, `ScanTest.coverIsServedOnlyForTheAlbumStateItShows` |

## Q — Search and sort

| ID | requirement | status | evidence |
|---|---|---|---|
| Q1 | Live search-as-you-type over name, model, studio, photographer, description, tags | ✅ | `JdbcAlbumRepositoryTest`, `core/store.ts` |
| Q2 | Search syntax: `#tag`, `model:`/`studio:`/`by:`/`kind:` with quotes | ✅ | `search-syntax.spec.ts` |
| Q3 | Filters: tags (all), model/studio/photographer, kind, roots, size, photo count, megapixels, date range, rating | ✅ | `ui/filters-panel.ts`, `JdbcAlbumRepositoryTest` |
| Q4 | Sorts: date shot, size, photos, quality, rating, name, indexed — every field in both directions (field + direction control) | ✅ | `AlbumQuery.Sort`, `ui/sort-menu.ts` |
| Q5 | Facet suggestions (top tags/models/studios/photographers) | ◐ | global only; not narrowed by the current query |
| Q6 | Results update live while scanning or editing | ✅ | `core/store.ts` `onEvent`/`refresh` |

## U — UI

| ID | requirement | status | evidence |
|---|---|---|---|
| U1 | Brick layout packs 16:9 and 9:16 tiles without gaps (proportional covers fill them centre-cropped, `object-fit: cover`); strict grid mode as alternative | ✅ | `layout.spec.ts` |
| U2 | Configurable tiles per row (1–12), responsive below 1440px | ✅ | `core/prefs.ts`, `responsiveColumns` |
| U3 | Virtual scrolling and paging for thousands of albums | ✅ | `ui/tile-grid.ts` |
| U4 | Tile shows name and size; hover shows meta; details panel (floating/inline/bottom sheet) with editing | ✅ | `ui/album-card.ts`, `ui/details-panel.ts` |
| U5 | Left sidebar with Folders / Refine tabs (`E` / `F`), slides over content below 1100px | ✅ | `ui/sidebar.ts`, `ui/filters-panel.ts`, `app.scss` |
| U6 | Album viewer: photo grid, lightbox, set cover, reveal in OS file manager, show path | ✅ | `ui/album-viewer.ts`, `AlbumSocket.reveal` |
| U7 | Themes: light/dark toggle (`T`, top bar); a first run takes the system theme | ✅ | `styles/_tokens.scss`, `core/prefs.ts` |
| U8 | Keyboard driven (see README) | ✅ | `app.ts`, `album-viewer.ts`, `tile-grid.ts` |
| U9 | Works without backend in dev (mock dataset) | ✅ | `core/mock-gateway.ts` |
| U10 | Settings dialog grouped in pages (Library, Folders, Thumbnails, Appearance, Keyboard, About) with one draft (bound to its library: discarded on a library switch) and an explicit Save/Revert for server-side settings | ✅ | `ui/settings/settings-dialog.ts`, `ui/settings/*-page.ts` |
| U11 | Folders are chosen in a folder picker (drives, places, crumbs, filter, typed path) instead of typed paths; a new library folder may be typed | ✅ | `ui/settings/folder-picker.ts`, `core/folder-picker.ts` |
| U12 | Library management: known libraries with switch/forget/add, composition chart (covers / metadata / overhead), counts, last update, sizes, Optimize | ✅ | `ui/settings/library-page.ts` |
| U13 | Folder validation: a folder inside an indexed root is refused, a parent of indexed roots offers to replace them; an unreachable root is relocated with `Locate…`, which derives a path-map rule (drive and Unix prefixes) | ✅ | `ui/settings/folders-page.ts`, `core/paths.spec.ts` |
| U14 | Cover and thumbnail rendering (format, quality, size, threads) editable in the UI | ✅ | `ui/settings/thumbnails-page.ts` |
| U15 | Explorer-like directory tree (Library › roots › folders with album counts, lazy, keyboard); selecting a folder shows only albums at or below it; filter box over the loaded tree, collapse all, expand selected (≤ 300 folders) | ✅ | `ui/folder-tree.ts`, `AlbumStore.goTo` |
| U16 | Location bar above the grid: ↑, crumbs with sub-folder popovers, editable path (must lie inside an indexed folder), album count | ✅ | `ui/location-bar.ts` |
| U17 | Rescan of only the shown folder (Library = all roots); Cancel while running; empty-folder state offers it | ✅ | `ui/location-bar.ts`, `app.html` |

## K — Catalog ([catalog_plan.md](catalog_plan.md); status = phase 0 landed: data model, wire, mock; logic per track)

| ID | requirement | status | evidence |
|---|---|---|---|
| K1 | Entities of six kinds (model, studio/agency, photographer, genre, tag, favorites list) in one table with aliases; album text fields resolve into links on every save; display names from links | ◐ | `catalog/Catalog.resolve` (B1), `schema.sql` tables, `JdbcCatalogRepositoryTest` |
| K2 | Auto-created entities flagged until confirmed; rename keeps the old name as an alias; merge unions links; delete unlinks | ◐ | `Catalog.save/confirm/delete` (phase 0), `merge` (B1) |
| K3 | Cards: model (profile, studios table with credited alias and roles, genres, own hashtags), studio (roster, photographers, genres), photographer, genre, list; derived facts read-only, curated facts editable | ○ | F3, `ui/entity-card.ts` |
| K4 | Sidebar grouped by Folders / Studios / Models / Photographers / Genres / Favorites with counts, Unassigned rows, batch confirm/merge/delete | ○ | F3, `ui/entity-tree.ts` |
| K5 | Filters by entity id, by missing role, by genre; `model:`/`studio:`/`by:`/`#tag` resolve through aliases; facets from links | ◐ | `JdbcAlbumRepository.Where` (`entities`, `missing` phase 0; aliases B1) |
| K6 | Favorites lists for albums and photos; photo ratings 1–10 by entry name | ◐ | `CatalogRepository.mark` (phase 0), viewer UI (F2) |
| K7 | Hidden = safe-delete: index only, excluded from default views and counts, visible in the Hidden view, reconciliation unaffected | ◐ | `JdbcAlbumRepositoryTest` hidden rules (phase 0), Views strip (F3) |
| K8 | Copies by content stamp: `copies` per page, copy list with Compare, Duplicates view | ◐ | `copies` (phase 0), `compareCopies` (B1), UI (F3) |
| K9 | Multi-selection (≤ 500), context menus on albums, folders, entities, photos; confirm dialogs for every operation | ◐ | `core/selection.spec.ts` (phase 0), F1/F4 |

## O — Operations ([catalog_plan.md](catalog_plan.md) §4)

| ID | requirement | status | evidence |
|---|---|---|---|
| O1 | One admission gate for scans, operations, restart and root settings | ✅ | `index/Gate`, `GateTest` |
| O2 | Move, copy, delete, pack/unpack/repack (zip, 7z, auto), encrypt/decrypt `.cry`, copy photos, folder rename/move/copy/delete: manifested, journaled, verified byte for byte before the source is deleted, recoverable after a crash | ○ | B2/B3: `ops/FileOps` (phase 0 refuses with "not implemented") |
| O3 | Identity survives every operation: `relocate`/`rebase` for path changes, re-index with asserted identity (`commit(prior, …, nameMap)`) for container changes; pins and photo marks follow renamed entries | ◐ | `index/Reindexer`, `AlbumService.commit(prior…)` (phase 0) |
| O4 | Bulk metadata assignment (model, studio, photographer, genre, tags add/remove, rating, shot date) to a selection or a tree node's albums | ◐ | `bulkUpdate` (phase 0), menus (F4) |
| O5 | Viewer: sort (name, size, date, dimensions, rating, random), min-size filter, five scale modes, full screen with slideshow, photo favorites and ratings, copy to 1 of 10 targets with skip-identical and auto-rename, metadata view, bio text | ○ | F2, B4 |
| O6 | Plugin contracts: `Extraction` context for extractors and cover strategies, beans + `ServiceLoader`, later a `:spi` module | ○ | B4, phase 3 |

## N — Platform and non-functional

| ID | requirement | status | evidence |
|---|---|---|---|
| N1 | One self-contained jar serves API and compiled UI | ✅ | `backend/build.gradle.kts` |
| N2 | WebSocket for data and live events, HTTP for image bytes (cacheable, ETag) | ✅ | `web/` |
| N3 | Storage swappable by configuration (`thumbnailer.store.albums`, `.blobs` = `tupl` default | `mvstore`) | ✅ | `store/` adapters, `TuplBlobStoreTest` |
| N4 | Local only: binds 127.0.0.1, WS origins restricted to loopback, no auth | ✅ | `application.yml`, `AlbumSocket` |
| N5 | A slow client never blocks a scan; scans never block the UI | ✅ | `AlbumSocket` send decorator + broadcast thread |
| N6 | Extensible by SPI: containers, extractors, cover strategies, stores | ◐ | see C2 |
| N7 | Scales to > 100 k albums: covers ≈ 12 KiB each in a compacting KV store, one packed entries value per album, slim reconcile (path → id, signature), bounded indexing memory; a sorted page ≈ 2 ms and a text search ≈ 60 ms at 100 k albums | ✅ | `TuplBlobStore`, `store/jdbc/EntryCodec`, `AlbumRepository.stamps`, `schema.sql` sort indexes |
