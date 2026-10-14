# next_goal.md — design and progress (untracked working note)

Owner answers (2026-10-12): one sidebar with two tabs (Folders | Refine) · prefix path map + "Locate…" · library switch
= in-process restart · slim location bar above the grid.

## Requirements → design

| # | requirement (next_goal.md) | design |
|---|---|---|
| R1 | Settings grouped by category/tabs, professional look (docs/settings/*.png) | native `<dialog>` 960×680 max: header (gear, "Settings", settings.json path), 190 px rail (Library · Folders · Thumbnails · Appearance · Keyboard · About), scrolling content of cards; one draft `Settings` for Folders+Thumbnails with a sticky "Unsaved changes · Revert · Save" footer; Appearance applies instantly |
| R2 | Folder selection dialog instead of typing paths | `browse` WS request lists drives + subfolders; `FolderPicker` service (promise) + `app-folder-picker` dialog (↑, crumbs, path box + Go, drives/places, filter, folder list, footer with chosen path). Typed non-existent path allowed only in `allowNew` mode (new library) |
| R3 | Library category: choose the library dir, several libraries, switch | `thumbnailer.data-dir` stays the **home**; it holds `libraries.json` `{active, known[]}`; property `thumbnailer.library-dir` (default = home, so existing installs keep working) is set from `libraries.json` by an `ApplicationListener<ApplicationEnvironmentPreparedEvent>` registered in `main`; all stores use `${thumbnailer.library-dir:${thumbnailer.data-dir}}`. Switch = write `libraries.json`, reply `ok`, in-process Spring restart (close context, run again); the UI reconnects (existing backoff) |
| R4 | Pie chart of library composition, counts, last update, size, optimize button | `LibraryStats` (H2: `COUNT(*)`, `SUM(image_count)`, `SUM(bytes)`, `MAX(indexed_at)`, live = `DISK_SPACE_USED`, file = sizes of `albums.mv.db*`; Tupl: count, live = used pages, file size). Donut: covers / metadata / overhead (= files − live). `optimize` = Tupl compaction + H2 `SHUTDOWN COMPACT` (reopens on next connection), refused while a scan runs |
| R5 | Nested roots check (A:\pics then A:\pics\abc) | inline validation in the Folders page (inside an existing root → rejected with reason; parent of existing roots → offer "Replace nested roots, albums kept"); backend `retainRoots` re-roots albums of a removed root that lies under a new root (`AlbumRepository.reroot`) instead of deleting them |
| R6 | Library moved to another machine, galleries under another prefix | `Settings.pathMap: [{from, to}]` longest-prefix rewrite applied by `Containers.locate(canonical)` / `Containers.canonical(onDisk)`; stored paths never change; all disk access goes through `Containers`; `RootStatus.location` shows where a root is found; unreachable root → "Locate…" (picker) derives the map entry from the longest common suffix |
| R7 | Hard-coded render constants → config (format, quality, threads, size) for covers | `Settings.covers: Render{format WEBP\|JPEG, quality, size, threads}` default WEBP/75/512/4; cover tag epoch derived from the spec (WEBP/512/75 keeps today's value, so existing stores stay valid) |
| R8 | Entry thumbnails in fast JPEG, configurable | `Settings.thumbs: Render` default JPEG/85/512/0 (0 = CPU count); rendered per request through a resizable `Limiter`; content type per format; image URLs carry `&r=<renderEpoch>` so a changed spec bypasses the browser cache |
| R9 | Directory tree panel (Explorer-like) | sidebar tab **Folders**: lazy tree over `folders(path)` (child folders with recursive album counts, derived from stored album paths, `album_path` index); selecting a folder sets `filters.roots=[path]` (prefix filter already supported); replaces Refine's "Search in" checkboxes |
| R10 | Location bar with path choice, go, rescan-this-path | slim bar above the grid: ↑ · Library › crumbs (each with a sibling/child dropdown) · editable path (Enter = go) · album count · "Rescan this folder"; `scan(paths)` accepts a root or any folder below one, prunes only under it after a complete walk |
| R11 | Compact, organic main-screen design | topbar unchanged except Folders/Refine buttons; sidebar = one panel with a tab strip; keys `E` folders, `F` refine |

## Wire contract additions (mirror in Request/Event/AlbumSocket, model.ts, gateway.ts, ws-gateway.ts, mock-gateway.ts, ARCHITECTURE §3)

```ts
Render   { format: 'WEBP' | 'JPEG'; quality: number /*1..100*/; size: number /*64..4096, longer side*/; threads: number /*0..64, 0 = CPU count*/ }
Mapping  { from: string; to: string }          // normalized dirs ending in '/', e.g. 'W:/' → 'X:/users/'
Settings { roots: [{ path, exclusions }]; exclusions: string[]; scanOnStart: boolean; pathMap: Mapping[]; covers: Render; thumbs: Render }
         // defaults: pathMap [], covers {WEBP,75,512,4}, thumbs {JPEG,85,512,0}; absent fields in an old settings.json → defaults
RootStatus { path; location: string /*after pathMap*/; exists; albumCount; lastScanAt }
SettingsInfo { settings; dataDir /*active library dir*/; roots: RootStatus[]; renderEpoch: string /*hex, covers+thumbs spec*/ }

→ scan { paths: string[] | null }                 // was `roots`; each entry a root or a folder below one; null = all roots
→ folders { path }   ← folders { path, folders: [{ name, path, albums /*at or below*/, nested /*has albums strictly below*/ }] }
→ browse { path | null }  ← listing { path, parent: string | null, drives: string[], folders: [{ name, path }], error: string | null }
→ library            ← libraryInfo
→ switchLibrary { dir }   ← ok, then the server restarts (socket closes; client reconnects)
→ forgetLibrary { dir }   ← libraryInfo (never the active one)
→ optimize           ← ok, then the server restarts (Tupl compaction online first, H2 SHUTDOWN COMPACT at close) | error while a scan runs
                     // switchLibrary and forgetLibrary are also refused with error while a scan runs
libraryInfo { home; dir; known: string[]; pinned: boolean /*library-dir forced by CLI/env*/; stats: LibraryStats; about: { version, java, os } }
LibraryStats { albums; images; imageBytes; lastIndexedAt: string | null; coversCount; coversBytes; coversFile; metadataBytes; metadataFile; settingsBytes }
```

HTTP: `/img/{id}/cover?v=rev&r=epoch` (content type by covers format), `/img/{id}/{i}/thumb?v=rev&r=epoch` (by thumbs format).

## Design amendments after the Codex review (binding for the backend)

- **Path map**: `Settings.locate(String canonical) → Path` is a pure function of the settings snapshot (longest `from` prefix,
  case-insensitive match, stored case kept); no mutable mount table and no general inverse. A scan binds each root to one
  pair (canonical root, physical root = `locate(root)`) taken once at scan start; the canonical path of a discovered entry is
  `canonicalRoot + physical.substring(physicalRoot.length)`. Validation: a mapping `from` may not lie strictly inside a
  configured root; `from`/`to` are normalized directories ending in `/`. Exclusions are evaluated in canonical space:
  `Exclusions.rebased(physicalRoot, canonicalRoot)` rewrites the prefix before matching, so discovery and the containers
  keep passing physical paths. Root status uses `locate` for the exists check and reports `location`.
- **Render**: `Render.epoch()` hashes format, size and quality only (threads never change bytes); `(WEBP,512,75)` keeps the
  legacy epoch value **and** the legacy value layout `[tag 8][webp]` with the RIFF size check; every other spec writes
  `[tag 8][len 4][payload]` and checks `len`. `Thumbnailer.render(container, name, spec)` returns `Thumb(bytes, format,
  epoch, portrait)`; the epoch that tags a stored value is the one carried by the bytes (rendered or reused), never the
  spec current at commit time. Admission control lives in `AlbumService`: one resizable `Limiter` for cover renders
  (indexing **and** HTTP cover misses share it), one for entry thumbnails; sized from settings before each acquire,
  `0` = available processors. `ImageController`: content type from the `Thumb`, ETag = `revision + '-' + epoch`.
- **Scan scopes**: `scan(paths)`; each path must equal a configured root or lie below one (else `error`); scope = (owning
  root, prefix); overlapping scopes collapse to the outer one; the walk starts at `locate(prefix)`; prune after a complete,
  uncancelled walk removes stored albums whose path `== prefix` or starts with `prefix/` that the walk did not meet.
- **Tombstones** (so pruning never loses edits): `AlbumRepository.delete` moves identity into table `removed(content_id PK,
  id, rating, cover, cover_pinned, edited_json, removed_at)` (`MERGE`); `AlbumService.commit` of a *new* album first
  `recall(contentId)`s a tombstone and adopts id, rating, edits and the pinned cover (if the entry still exists), deleting
  the tombstone. Covers an album moved out of a rescanned sub-folder, and delete + restore from backup.
- **Roots**: `stamps(prefix)` and prune work by path prefix (`path = ? OR path LIKE ?/%`, index `album_path`); the `root`
  column is informational. `AlbumService.retainRoots(roots)`: a stored root not configured but covered by a configured root
  is re-rooted (`AlbumRepository.reroot(from, to)`, one UPDATE, best effort), otherwise its albums are removed; also called
  at scan start so an interrupted settings save converges.
- **Restart**: `ThumbnailerApplication.app(args)` builds the `SpringApplication` with the `LibraryLocator` listener; `main`
  and `restart()` both use it. `switchLibrary`, `optimize` and `forgetLibrary` are refused with `error` while a scan runs.
  `optimize` = Tupl stepped compaction online, then `AlbumRepository.compact()` flags `SHUTDOWN COMPACT` for the close
  that the restart performs (no online H2 shutdown).
- **Stats** are estimates: H2 live = `DISK_SPACE_USED('ALBUM') + DISK_SPACE_USED('ENTRIES')` (indexes and LOBs excluded),
  file = `albums.mv.db*`; Tupl live = used pages, file = `tupl.db`; the UI labels overhead "indexes and free space".
- **Folders**: query `path, kind`; a ZIP album directly under the parent is not a folder; a DIRECTORY album at
  `parent/name` is a leaf folder (counts 1 + what lies below); LIKE prefixes escaped like `Where`.

## Work order

1. Backend phase 1 (foundation): Settings/Render/pathMap, `Limiter`, Thumbnailer JPEG + spec, AlbumService tag/epoch/limits, AlbumIndexer, ImageController content types, Containers mounts + all disk-access sites, reroot. Tests + docs.
2. Backend phase 2: libraries.json + library-dir + restart, stats + optimize, folders query + index, browse, scan by sub-path, WS messages, tests, docs.
3. Frontend phase 1: contract mirrors (model/gateway/ws/mock), store additions, settings dialog redesign + pages + folder picker.
4. Frontend phase 2: sidebar tabs, folder tree, location bar, topbar buttons/chips, keys, empty states, mock emulation.
5. Verify: backend tests, frontend tests + build, live smoke run (`?live`) in the browser pane; Codex review of the diff; docs sync.

## Status

- [x] 1 backend phase 1
- [x] 2 backend phase 2 — scan scopes + tombstones + prefix roots/`reroot`, `folders`, `browse`, libraries (`Libraries`, `LibraryLocator`, `restart`), stats/optimize, WS messages; 46 backend tests green; jar smoke run: switch/optimize restart, `Implementation-Version`
- [x] 3 frontend phase 1 — contract mirrors, `ui/settings/*` (rail + six pages), `core/folder-picker.ts` + `app-folder-picker`; donut per the dataviz skill; owner-thread fixes: `optimize` → ok + restart, stats reload on dialog open, stale folder dropped on settings reload, `core/paths.ts`
- [x] 4 frontend phase 2 — `ui/sidebar.ts` (tabs), `ui/folder-tree.ts`, `ui/location-bar.ts`, topbar buttons + `in:` chip, keys E/F, empty-folder state, mock `roots` prefix filter; build + tests green, checked in the mock UI
- [x] 5 verification + docs — full `./gradlew build` green (47 backend tests), `ng test` 15/15, live run of the packaged jar (`backend-demo`): tree/crumbs/rescan, settings pages, picker, switch + optimize restarts, JPEG thumbs; Codex diff review → 11 findings fixed (per-library draft, atomic tombstone adoption, switch validation + fallback, real-case scopes, settings guard during scans, pinned provenance, restart admission gate, tree cache, picker loading guard, folder reset after save, Unix path mapping); not fixed: contentId-based recall is ambiguous by construction (same names+sizes), OnPush cards keep old image URLs until re-rendered after a render-settings change

## Decisions / dead ends

- Phase 1 (backend): prefix rules live in `Containers.within/rebase` (used by `Settings.locate`, `Exclusions.rebased`, `ScanService`); `retainRoots`/`reroot` untouched (phase 2); 36 backend tests green.

- Library switch by in-process restart rather than re-creatable stores: every store, the H2 URL and the settings file hang off one property; restart reuses the existing reconnect path in the UI.
- Path map (not per-root mount) because one entry covers every root on a remounted drive; "Locate…" derives it.
- Tree derived from stored album paths (the library is a snapshot of the file system), not from a live directory walk (slow on network drives, shows folders without albums).
