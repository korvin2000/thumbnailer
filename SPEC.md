# SPEC — what Thumbnailer does

[↑ INDEX](INDEX.md) · why: [INTENT.md](INTENT.md) · exact contract (fields, messages, routes): [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · gaps: [PLAN.md](PLAN.md)

Status: ✅ done · ◐ partial · ○ missing. Evidence names the test or the owning file. Keep IDs stable; reference them in
PLAN items and commit messages. When behaviour changes, update the row here and the contract in ARCHITECTURE.

## L — Library and albums

| ID | requirement | status | evidence |
|---|---|---|---|
| L1 | The unit is an album: a directory holding ≥ `min-images` (2) images directly, or a zip archive holding images; nested dirs are separate albums | ✅ | `index/AlbumDiscovery`, `ScanTest` |
| L2 | Images: jpg jpeg png gif webp bmp tif tiff (CMYK/ICC JPEG, WebP via TwelveMonkeys); corrupt entries skipped | ✅ | `source/AlbumContainer.IMAGE_EXTENSIONS` |
| L3 | Album stats: image count, bytes, average and max resolution, shot date, modified/indexed time | ✅ | `index/AlbumIndexer` |
| L4 | Album id is stable across re-index, moves and renames | ✅ | `ScanTest.renamedAlbumKeepsItsIdentity…` |
| L5 | The library on disk is never modified by the app | ✅ | design rule (no writers in `source/`) |

## S — Scanning and index

| ID | requirement | status | evidence |
|---|---|---|---|
| S1 | Scan roots and exclusions (`*` globs, global and per root) are configured in the UI and persisted | ✅ | `settings/`, `ui/settings-dialog.ts` |
| S2 | Incremental: unchanged albums (same path + signature) are skipped; changed ones re-indexed; unchanged photos inside a changed album are not re-probed | ✅ | `index/Reconciler`, `AlbumIndexer` |
| S3 | Moves/renames detected by content id (image names + sizes); id, rating and edits kept | ✅ | `ScanTest.reconciles…`, `…renamed…` |
| S4 | Removal only after a complete, uncancelled walk of a reachable root; unplugged roots untouched | ✅ | `ScanTest.reconciles…` |
| S5 | Roots must not overlap | ✅ | `ScanTest.overlappingRootsAreRejected` |
| S6 | Async scan with live progress (≤4/s), cancel, one scan at a time, optional scan on start | ✅ | `index/ScanService` |
| S7 | Index survives restarts; data dir configurable (`thumbnailer.data-dir`) | ✅ | `application.yml` |

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
| C4 | Thumbnails 16:9 or 9:16 by orientation, centre-cropped, EXIF-rotated, longer side `thumb-width` (640) | ✅ | `ThumbnailerTest` |
| C5 | Thumbnails cached in the KV store, keyed by revision; regenerated lazily when missing | ✅ | `AlbumService.cover/thumb` |

## Q — Search and sort

| ID | requirement | status | evidence |
|---|---|---|---|
| Q1 | Live search-as-you-type over name, model, studio, photographer, description, tags | ✅ | `JdbcAlbumRepositoryTest`, `core/store.ts` |
| Q2 | Search syntax: `#tag`, `model:`/`studio:`/`by:`/`kind:` with quotes | ✅ | `search-syntax.spec.ts` |
| Q3 | Filters: tags (all), model/studio/photographer, kind, roots, size, photo count, megapixels, date range, rating | ✅ | `ui/filters-panel.ts`, `JdbcAlbumRepositoryTest` |
| Q4 | Sorts: newest, oldest, largest, smallest, most/fewest photos, best quality, top rated, name, recently indexed | ✅ | `AlbumQuery.Sort` |
| Q5 | Facet suggestions (top tags/models/studios/photographers) | ◐ | global only; not narrowed by the current query |
| Q6 | Results update live while scanning or editing | ✅ | `core/store.ts` `onEvent`/`refresh` |

## U — UI

| ID | requirement | status | evidence |
|---|---|---|---|
| U1 | Brick layout packs 16:9 and 9:16 covers without gaps or cropping; strict grid mode as alternative | ✅ | `layout.spec.ts` |
| U2 | Configurable tiles per row (1–12), responsive below 1440px | ✅ | `core/prefs.ts`, `responsiveColumns` |
| U3 | Virtual scrolling and paging for thousands of albums | ✅ | `ui/tile-grid.ts` |
| U4 | Tile shows name and size; hover shows meta; details panel (floating/inline/bottom sheet) with editing | ✅ | `ui/album-card.ts`, `ui/details-panel.ts` |
| U5 | Refine panel (left), slides over content below 1100px | ✅ | `ui/filters-panel.ts`, `app.scss` |
| U6 | Album viewer: photo grid, lightbox, set cover, reveal in OS file manager, show path | ✅ | `ui/album-viewer.ts`, `AlbumSocket.reveal` |
| U7 | Themes: system/light/dark | ✅ | `styles/_tokens.scss`, `core/prefs.ts` |
| U8 | Keyboard driven (see README) | ✅ | `app.ts`, `album-viewer.ts`, `tile-grid.ts` |
| U9 | Works without backend in dev (mock dataset) | ✅ | `core/mock-gateway.ts` |

## N — Platform and non-functional

| ID | requirement | status | evidence |
|---|---|---|---|
| N1 | One self-contained jar serves API and compiled UI | ✅ | `backend/build.gradle.kts` |
| N2 | WebSocket for data and live events, HTTP for image bytes (cacheable, ETag) | ✅ | `web/` |
| N3 | Storage swappable by configuration (`thumbnailer.store.albums`, `.blobs`) | ✅ | `store/` adapters |
| N4 | Local only: binds 127.0.0.1, WS origins restricted to loopback, no auth | ✅ | `application.yml`, `AlbumSocket` |
| N5 | A slow client never blocks a scan; scans never block the UI | ✅ | `AlbumSocket` send decorator + broadcast thread |
| N6 | Extensible by SPI: containers, extractors, cover strategies, stores | ◐ | see C2 |
