# Frontend source map — `frontend/src/` (Angular 22, zoneless, signals, Vitest)

[↑ INDEX](../../INDEX.md) · rules: [frontend/CLAUDE.md](../../frontend/CLAUDE.md) · design: [ARCHITECTURE §4](../ARCHITECTURE.md#4-frontend-angular-22-standalone-signals-zoneless-scss) · wire types: [§3](../ARCHITECTURE.md#3-transport)

All components: standalone, OnPush, `inject()`, signal `input()/output()/model()`, inline template+styles (except `App`),
`@if/@for/@let`. Files kebab-case, classes without `Component` suffix, selector prefix `app-`.

## Data flow

```
Topbar/FiltersPanel ─▶ AlbumStore.text/filters/sort ─(computed query, effect 120ms)─▶ Gateway.search ─▶ albums/total
FolderTree/LocationBar/Topbar chip ─▶ AlbumStore.goTo(path) ─▶ filters.roots=[path] (path-prefix filter) ─▶ store.folder ─▶ crumbs, tree reveal
FolderTree (expanded node) / LocationBar (crumb popover) ─▶ Gateway.folders(path) ─▶ Folder[] (cached per node; reloaded on store.treeVersion)
Gateway.events (albumChanged|albumRemoved|scanProgress|connection) ─▶ AlbumStore.onEvent ─▶ refresh() (600ms, max wait 1.5s)
App.tiles (Album→Tile 16:9|9:16) ─▶ TileGrid ─▶ layout() ─▶ visible placements ─▶ <ng-template> AlbumCard
/album/:id ─▶ AlbumViewer (Gateway.album → entries → TileGrid mode=grid + lightbox)
SettingsDialog.draft ─(model)─▶ FoldersPage/ThumbnailsPage ─▶ Save ─▶ AlbumStore.saveSettings ─▶ Gateway.setSettings
LibraryPage/FoldersPage ─▶ FolderPicker.pick() ─▶ app-folder-picker (Gateway.browse) ─▶ path ─▶ switchLibrary / addRoot / pathMap rule
```

## Root — [src/](../../frontend/src/)

- [main.ts](../../frontend/src/main.ts) — `bootstrapApplication(App, appConfig)`.
- [app.config.ts](../../frontend/src/app/app.config.ts) — providers; `createGateway()`: `?mock` or (dev && !`?live`) → `MockGateway`, else `WsGateway`. No backend-probe fallback.
- [app.routes.ts](../../frontend/src/app/app.routes.ts) — `''` library; `album/:id` → `AlbumViewer` (overlay above the mounted library); `**` → `''`.
- [app.ts](../../frontend/src/app/app.ts) + [app.html](../../frontend/src/app/app.html) + [app.scss](../../frontend/src/app/app.scss) — shell `App`: topbar, sidebar, location bar + grid (column `.main`), details, statusbar, settings, toasts; `tiles`, `detailsAlbum` (hovered if `followHover`), `emptyFolder` empty state; global `onKey` (`/ e f i t [ ] Esc`; `toggleTab`, Esc closes the sidebar only while it overlays). Breakpoints 1400 (details inline) / 1100 (sidebar overlay) / 760 (mobile).
- [styles.scss](../../frontend/src/styles.scss) → [_tokens.scss](../../frontend/src/styles/_tokens.scss) (CSS vars, oklch, dark base, `@mixin light` for `[data-theme=light]` + system light) · [_base.scss](../../frontend/src/styles/_base.scss) (reset; global `.btn .input .chip .segmented .panel .label .spinner`; settings page classes `.card .hint .field .check`).

## core/ — [src/app/core/](../../frontend/src/app/core/) (no components)

- [model.ts](../../frontend/src/app/core/model.ts) — wire types mirroring the backend: `Album Entry Kind Sort SORTS Filters AlbumQuery AlbumPatch Render Mapping Settings DEFAULT_SETTINGS RootStatus SettingsInfo Folder Listing LibraryStats LibraryInfo ScanProgress Facets SearchResult AlbumDetails GatewayEvent`; `activeFilterCount()`.
- [gateway.ts](../../frontend/src/app/core/gateway.ts) — abstract `Gateway` (DI token): `events`, `mock`, `search album update scan(paths?) cancelScan getSettings setSettings facets reveal folders browse library switchLibrary forgetLibrary optimize` (Promises), `coverUrl thumbUrl originalUrl`.
- [ws-gateway.ts](../../frontend/src/app/core/ws-gateway.ts) — `WsGateway`: JSON over `/ws`, id-correlated requests, 30s timeout, reconnect 0.5→10s backoff, emits `connection`; image URLs `/img/{id}/…?v=revision&r=renderEpoch` (epoch remembered from the last settings reply).
- [mock-gateway.ts](../../frontend/src/app/core/mock-gateway.ts) — `MockGateway`: refuses root/path-map changes while its fake scan runs (server rule); 180 seeded albums, canvas-painted covers, `matches()`/`comparator()` mirror server filter/sort (`roots` = path prefixes), fake scan (`scan(paths)` accepted, walks everything); `folders()` from album paths, `browse()` over a fake disk (`DISK`, E: root unreachable, copy under `D:/Backups`), `locate()` path-map rewrite, library stats/switch (connection drop + reconnect)/forget/optimize.
- [store.ts](../../frontend/src/app/core/store.ts) — `AlbumStore` (root, single state holder): signals `text filters sort albums total loading selectedId hoveredId facets scan settings library connected treeVersion` (bumped on scan end / reconnect / settings saved); computed `query` (parsed text ⊕ filters, `PAGE`=120), `folder` (= `filters.roots[0]`), `refining` (active filters minus the folder), `roots`; `goTo(path) rootOf(path) resetRefine clearFilters refresh loadMore patch reveal startScan(paths?) cancelScan saveSettings loadSettings loadLibrary switchLibrary forgetLibrary optimize loadFacets`; private `applySettings` (load and save: drops a folder under no root), `runSearch` (generation guard), `onEvent`.
- [folder-picker.ts](../../frontend/src/app/core/folder-picker.ts) — `FolderPicker` (root): `pick({ title, startAt?, allowNew? }) → Promise<string | null>`, `open` signal read by `ui/settings/folder-picker.ts`, `choose() cancel()`; a new `pick` settles the pending one with null.
- [search-syntax.ts](../../frontend/src/app/core/search-syntax.ts) — `parseSearch()`: `#tag`, `model:|m:`, `studio:|s:`, `by:|photographer:|p:`, `kind:zip|archive|rar|7z|tar|iso|dir` (archive formats → `ARCHIVE`), quoted values; rest = text.
- [layout.ts](../../frontend/src/app/core/layout.ts) — pure engine `layout(tiles,{width,columns,gap,mode})→{placements,height}`; modes `bricks|grid`; `responsiveColumns()`; `MAX_SCALE` 0.15.
- [prefs.ts](../../frontend/src/app/core/prefs.ts) — `Prefs`: persisted signals (localStorage `thumbnailer.*`): `theme columns layoutMode(key "layout") showTags followHover sidebar('folders'|'refine'|'none', validated) detailsOpen`; theme effect sets `html[data-theme]`.
- [toasts.ts](../../frontend/src/app/core/toasts.ts) — `Toasts`: `show() error() dismiss()`.
- [format.ts](../../frontend/src/app/core/format.ts) — `formatBytes formatMegapixels`; pipes `bytes mp basename`.
- [paths.ts](../../frontend/src/app/core/paths.ts) — `normPath()` (backslashes → '/', no trailing slash, bare drive keeps 'C:/'), `lastSegment()`, `dir()` (trailing '/'), `commonSuffixMapping(stored, found)` (Locate… rule; prefixes cut from the original strings, so '/' and 'C:/' survive); shared by the folders page, tree and location bar.

## ui/ — [src/app/ui/](../../frontend/src/app/ui/) (smart components inject `AlbumStore`)

- [tile-grid.ts](../../frontend/src/app/ui/tile-grid.ts) — generic virtualized `TileGrid<T>`: inputs `tiles mode columns gap padding selectedId hasMore empty`; outputs `select open reachedEnd`; projected `<ng-template>` per tile; viewport ±75%; scroll anchor kept on reflow; arrows/Home/End/Enter; `scrollTo()`.
- [album-card.ts](../../frontend/src/app/ui/album-card.ts) — `AlbumCard`: cover, badges, hover actions, meta scrim; outputs `select open info hover`.
- [topbar.ts](../../frontend/src/app/ui/topbar.ts) — `Topbar`: Folders/Refine sidebar buttons (`toggleTab`, Refine badge = `store.refining`), search box + removable query chips (`in: <folder>` chip → `goTo(null)`), sort, columns, bricks/grid, theme, details, settings; `focusSearch()`.
- [sidebar.ts](../../frontend/src/app/ui/sidebar.ts) — `Sidebar`: 44px header with a `.segmented` Folders | Refine strip (badge), Reset (`store.resetRefine`, Refine only), close ×; body = `app-folder-tree` or `app-filters-panel`.
- [folder-tree.ts](../../frontend/src/app/ui/folder-tree.ts) — `FolderTree`: rows = Library (sum of root counts) › roots (last segment, unreachable dimmed) › lazily loaded `Gateway.folders` children (`children` map, `expanded`/`loading` sets); reachable roots open on first sight; selection = `store.folder()` (click → `goTo`, dblclick/chevron → `toggle`); `reveal()` opens ancestors + scrolls on outside navigation; on `treeVersion` reloads expanded nodes and drops collapsed nodes' children; a new library or root set (`dataDir` + root paths key) clears the tree (`epoch` drops late listings); keys ↑ ↓ → ← Home End.
- [location-bar.ts](../../frontend/src/app/ui/location-bar.ts) — `LocationBar` (34px above the grid): ↑ (`parent`), `crumbs` (Library › root › segments, each with a children popover `menu`/`menuItems` over roots or `Gateway.folders`), edit mode (`commit` normalizes via `normPath`, requires `store.rootOf`, toast otherwise), album count + spinner, Rescan (`startScan([folder])`) / Cancel while scanning; crumb overflow fades on the left (`overflowing` via ResizeObserver).
- [filters-panel.ts](../../frontend/src/app/ui/filters-panel.ts) — `FiltersPanel` (Refine tab body): tags + facet chips, model/studio/photographer, kind (Folders · Zips · Archives), dates, range sliders (size, count, MP, rating). Folders live in the tree.
- [details-panel.ts](../../frontend/src/app/ui/details-panel.ts) — `DetailsPanel`: album meta + inline edit (saved on change via `store.patch`), rating, tags, reveal/copy path.
- [album-viewer.ts](../../frontend/src/app/ui/album-viewer.ts) — `AlbumViewer` (`/album/:id`): entries grid, lightbox, set cover (`C`); own `onKey`; reloads on newer `revision`, closes on `albumRemoved`.
- settings/ — the settings dialog and its pages (global page classes `.card .hint .field .check` in `_base.scss`):
  - [settings-dialog.ts](../../frontend/src/app/ui/settings/settings-dialog.ts) — `SettingsDialog` (native `<dialog>`, `open()/close()`): header with `<dataDir>/settings.json`, rail (`PAGES`, ↑/↓, icons-only below 720px), one `draft: Settings` shared by Folders/Thumbnails via `model()`, `dirty` footer (Revert/Save; `blocked` = scan running and roots/pathMap changed → Save disabled, "Stop the scan before changing folders"); the draft is bound to the library it was seeded from (`seededDir`: reseeded on a `dataDir` change, toast if edits were discarded); a save resets the draft only if it is still the one submitted; hosts `app-folder-picker` and a toast mirror (toasts are otherwise hidden under the top-layer dialog).
  - [library-page.ts](../../frontend/src/app/ui/settings/library-page.ts) — `LibraryPage`: known libraries (ACTIVE badge, Switch, Forget, Add library… via picker with `allowNew`), composition donut (SVG, `segments()` = covers/metadata/overhead with 2px gaps, colours `--c-covers --c-meta --c-over`), stats grid, Optimize; reloads on show and on reconnect.
  - [folders-page.ts](../../frontend/src/app/ui/settings/folders-page.ts) — `FoldersPage` (`draft` model): roots with status/`location`, `Locate…` (`pathMap` rule from `commonSuffixMapping`), `addRoot()` validation (inside a root → toast; parent of roots → Replace notice), exclusions, scan-on-start, path-mapping rules, scan controls + progress.
  - [thumbnails-page.ts](../../frontend/src/app/ui/settings/thumbnails-page.ts) — `ThumbnailsPage` (`draft` model) with two `RenderEditor`s (`app-render-editor`, `render` model: format segmented, quality range, size select, threads stepper 0 = auto).
  - [appearance-page.ts](../../frontend/src/app/ui/settings/appearance-page.ts) — `AppearancePage`: theme, layout, columns, show tags, follow hover (`Prefs`, instant).
  - [keyboard-page.ts](../../frontend/src/app/ui/settings/keyboard-page.ts) — `KeyboardPage`: shortcut tables `LIBRARY`/`VIEWER` (incl. `E` folders / `F` refine tabs).
  - [about-page.ts](../../frontend/src/app/ui/settings/about-page.ts) — `AboutPage`: version/Java/OS from `libraryInfo.about`, home, library, stores, local-only note.
  - [folder-picker.ts](../../frontend/src/app/ui/settings/folder-picker.ts) — `FolderPickerDialog` (`app-folder-picker`, native `<dialog>` above the settings dialog): ↑, crumbs, path box + Go, places (home + drives), filter, folder list over `Gateway.browse`; typed missing path accepted in `allowNew` mode ("will be created"); `gen` counter drops a listing for an older navigation or a dismissed dialog; "Use this folder" disabled while loading.
- shared/ — [icon.ts](../../frontend/src/app/ui/shared/icon.ts) (`Icon`, static SVG map `ICONS`) · [range.ts](../../frontend/src/app/ui/shared/range.ts) (`Range` dual slider) · [rating.ts](../../frontend/src/app/ui/shared/rating.ts) (`Rating` 0–10, `model`).

## Tests (plain Vitest, no TestBed)

- [layout.spec.ts](../../frontend/src/app/core/layout.spec.ts) — bricks rows, portrait rows/scale, order, grid cells, `responsiveColumns`.
- [search-syntax.spec.ts](../../frontend/src/app/core/search-syntax.spec.ts) — tags, keys, quotes, kind, empty input.
- [paths.spec.ts](../../frontend/src/app/core/paths.spec.ts) — `normPath` (backslashes, trailing slash, bare drive), `commonSuffixMapping` (drive and Unix prefixes, case, no common suffix).
- Not covered: store, gateways, prefs, components.

## Build/config

[angular.json](../../frontend/angular.json) (application builder; budgets initial 600kB/1.5MB; `test` = `@angular/build:unit-test`) ·
[proxy.conf.json](../../frontend/proxy.conf.json) (`/ws`, `/img` → :8080) · [tsconfig.json](../../frontend/tsconfig.json) (no `strict`) ·
[build.gradle.kts](../../frontend/build.gradle.kts) (`npmInstall`/`npmBuild` glue, skipped by `-PskipFrontend`).
