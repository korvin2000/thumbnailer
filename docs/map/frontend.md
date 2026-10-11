# Frontend source map — `frontend/src/` (Angular 22, zoneless, signals, Vitest)

[↑ INDEX](../../INDEX.md) · rules: [frontend/CLAUDE.md](../../frontend/CLAUDE.md) · design: [ARCHITECTURE §4](../ARCHITECTURE.md#4-frontend-angular-22-standalone-signals-zoneless-scss) · wire types: [§3](../ARCHITECTURE.md#3-transport)

All components: standalone, OnPush, `inject()`, signal `input()/output()/model()`, inline template+styles (except `App`),
`@if/@for/@let`. Files kebab-case, classes without `Component` suffix, selector prefix `app-`.

## Data flow

```
Topbar/FiltersPanel ─▶ AlbumStore.text/filters/sort ─(computed query, effect 120ms)─▶ Gateway.search ─▶ albums/total
Gateway.events (albumChanged|albumRemoved|scanProgress|connection) ─▶ AlbumStore.onEvent ─▶ refresh() (600ms, max wait 1.5s)
App.tiles (Album→Tile 16:9|9:16) ─▶ TileGrid ─▶ layout() ─▶ visible placements ─▶ <ng-template> AlbumCard
/album/:id ─▶ AlbumViewer (Gateway.album → entries → TileGrid mode=grid + lightbox)
```

## Root — [src/](../../frontend/src/)

- [main.ts](../../frontend/src/main.ts) — `bootstrapApplication(App, appConfig)`.
- [app.config.ts](../../frontend/src/app/app.config.ts) — providers; `createGateway()`: `?mock` or (dev && !`?live`) → `MockGateway`, else `WsGateway`. No backend-probe fallback.
- [app.routes.ts](../../frontend/src/app/app.routes.ts) — `''` library; `album/:id` → `AlbumViewer` (overlay above the mounted library); `**` → `''`.
- [app.ts](../../frontend/src/app/app.ts) + [app.html](../../frontend/src/app/app.html) + [app.scss](../../frontend/src/app/app.scss) — shell `App`: topbar, filters, grid, details, statusbar, settings, toasts; `tiles`, `detailsAlbum` (hovered if `followHover`); global `onKey` (`/ f i t [ ] Esc`). Breakpoints 1400 (details inline) / 1100 (filters overlay) / 760 (mobile).
- [styles.scss](../../frontend/src/styles.scss) → [_tokens.scss](../../frontend/src/styles/_tokens.scss) (CSS vars, oklch, dark base, `@mixin light` for `[data-theme=light]` + system light) · [_base.scss](../../frontend/src/styles/_base.scss) (reset; global `.btn .input .chip .segmented .panel .label .spinner`).

## core/ — [src/app/core/](../../frontend/src/app/core/) (no components)

- [model.ts](../../frontend/src/app/core/model.ts) — wire types mirroring the backend: `Album Entry Kind Sort SORTS Filters AlbumQuery AlbumPatch Settings SettingsInfo ScanProgress Facets SearchResult AlbumDetails GatewayEvent`; `activeFilterCount()`.
- [gateway.ts](../../frontend/src/app/core/gateway.ts) — abstract `Gateway` (DI token): `events`, `mock`, `search album update scan cancelScan getSettings setSettings facets reveal` (Promises), `coverUrl thumbUrl originalUrl`.
- [ws-gateway.ts](../../frontend/src/app/core/ws-gateway.ts) — `WsGateway`: JSON over `/ws`, id-correlated requests, 30s timeout, reconnect 0.5→10s backoff, emits `connection`; image URLs `/img/{id}/…?v=revision`.
- [mock-gateway.ts](../../frontend/src/app/core/mock-gateway.ts) — `MockGateway`: 180 seeded albums, canvas-painted covers, `matches()`/`comparator()` mirror server filter/sort, fake scan.
- [store.ts](../../frontend/src/app/core/store.ts) — `AlbumStore` (root, single state holder): signals `text filters sort albums total loading selectedId hoveredId facets scan settings connected`; computed `query` (parsed text ⊕ filters, `PAGE`=120); `refresh loadMore patch reveal startScan cancelScan saveSettings loadSettings loadFacets`; private `runSearch` (generation guard), `onEvent`.
- [search-syntax.ts](../../frontend/src/app/core/search-syntax.ts) — `parseSearch()`: `#tag`, `model:|m:`, `studio:|s:`, `by:|photographer:|p:`, `kind:zip|dir`, quoted values; rest = text.
- [layout.ts](../../frontend/src/app/core/layout.ts) — pure engine `layout(tiles,{width,columns,gap,mode})→{placements,height}`; modes `bricks|grid`; `responsiveColumns()`; `MAX_SCALE` 0.15.
- [prefs.ts](../../frontend/src/app/core/prefs.ts) — `Prefs`: persisted signals (localStorage `thumbnailer.*`): `theme columns layoutMode(key "layout") showTags followHover filtersOpen detailsOpen`; theme effect sets `html[data-theme]`.
- [toasts.ts](../../frontend/src/app/core/toasts.ts) — `Toasts`: `show() error() dismiss()`.
- [format.ts](../../frontend/src/app/core/format.ts) — `formatBytes formatMegapixels`; pipes `bytes mp basename`.

## ui/ — [src/app/ui/](../../frontend/src/app/ui/) (smart components inject `AlbumStore`)

- [tile-grid.ts](../../frontend/src/app/ui/tile-grid.ts) — generic virtualized `TileGrid<T>`: inputs `tiles mode columns gap padding selectedId hasMore empty`; outputs `select open reachedEnd`; projected `<ng-template>` per tile; viewport ±75%; scroll anchor kept on reflow; arrows/Home/End/Enter; `scrollTo()`.
- [album-card.ts](../../frontend/src/app/ui/album-card.ts) — `AlbumCard`: cover, badges, hover actions, meta scrim; outputs `select open info hover`.
- [topbar.ts](../../frontend/src/app/ui/topbar.ts) — `Topbar`: search box + removable query chips, sort, columns, bricks/grid, theme, details, settings; `focusSearch()`.
- [filters-panel.ts](../../frontend/src/app/ui/filters-panel.ts) — `FiltersPanel`: tags + facet chips, model/studio/photographer, kind, dates, range sliders (size, count, MP, rating), roots.
- [details-panel.ts](../../frontend/src/app/ui/details-panel.ts) — `DetailsPanel`: album meta + inline edit (saved on change via `store.patch`), rating, tags, reveal/copy path.
- [album-viewer.ts](../../frontend/src/app/ui/album-viewer.ts) — `AlbumViewer` (`/album/:id`): entries grid, lightbox, set cover (`C`); own `onKey`; reloads on newer `revision`, closes on `albumRemoved`.
- [settings-dialog.ts](../../frontend/src/app/ui/settings-dialog.ts) — `SettingsDialog` (native `<dialog>`): draft roots/exclusions/scan-on-start, scan controls, appearance prefs, shortcut sheet.
- shared/ — [icon.ts](../../frontend/src/app/ui/shared/icon.ts) (`Icon`, static SVG map `ICONS`) · [range.ts](../../frontend/src/app/ui/shared/range.ts) (`Range` dual slider) · [rating.ts](../../frontend/src/app/ui/shared/rating.ts) (`Rating` 0–10, `model`).

## Tests (plain Vitest, no TestBed)

- [layout.spec.ts](../../frontend/src/app/core/layout.spec.ts) — bricks rows, portrait rows/scale, order, grid cells, `responsiveColumns`.
- [search-syntax.spec.ts](../../frontend/src/app/core/search-syntax.spec.ts) — tags, keys, quotes, kind, empty input.
- Not covered: store, gateways, prefs, components.

## Build/config

[angular.json](../../frontend/angular.json) (application builder; budgets initial 600kB/1.5MB; `test` = `@angular/build:unit-test`) ·
[proxy.conf.json](../../frontend/proxy.conf.json) (`/ws`, `/img` → :8080) · [tsconfig.json](../../frontend/tsconfig.json) (no `strict`) ·
[build.gradle.kts](../../frontend/build.gradle.kts) (`npmInstall`/`npmBuild` glue, skipped by `-PskipFrontend`).
