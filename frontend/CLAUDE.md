# Frontend rules (Angular 22, zoneless, signals, Vitest) — loaded when working under frontend/

Map: [docs/map/frontend.md](../docs/map/frontend.md). Design: [ARCHITECTURE §4](../docs/ARCHITECTURE.md#4-frontend-angular-22-standalone-signals-zoneless-scss).
Node.js/npm are global (OS PATH). Run Angular CLI from `frontend/` (`npx ng …`) or via `npm --prefix frontend …`
(commands in the root CLAUDE.md).

## Conventions (match them)

- Standalone components, `ChangeDetectionStrategy.OnPush`, `inject()`, signal `input()/output()/model()`,
  `@if/@for/@let`; inline `template` + `styles` (only `App` uses separate files). No NgModules, no zone.js, no NgRx,
  no RxJS in components except subscribing to `Gateway.events`.
- Files kebab-case without `.component`; classes without `Component` suffix; selector prefix `app-`; `protected` for
  template-bound members.
- State: `AlbumStore` (root) owns query/results/selection/settings; `Prefs` owns persisted UI preferences
  (localStorage `thumbnailer.*`); `Toasts` for messages. Components hold only local UI state.
- Styling: only `var(--…)` tokens from `styles/_tokens.scss` (oklch, dark base, light via `[data-theme]` or system);
  shared classes (`.btn .input .chip .segmented .panel`) live in `styles/_base.scss` — reuse them, don't restyle.
  Breakpoints 1400 / 1100 / 760.
- Formatting: code is hand-formatted dense (long lines). **Do not run Prettier on whole files** — `.prettierrc`
  (`printWidth: 100`) would reformat everything. `tsconfig` is not `strict`.

## Gotchas

- Gateway choice (`app.config.ts`): `?mock` or (dev build and no `?live`) → `MockGateway`; otherwise `WsGateway`.
  There is no automatic fallback.
- `AlbumStore.runSearch` drops stale responses with a generation counter; `loadMore` requires
  `loadedFor === query()` — `query` is a `computed`, keep its object identity stable (don't rebuild it per call).
- Live events (`albumChanged`, `albumRemoved`, `scanProgress`) trigger `refresh()` (trailing 600 ms, max wait 1.5 s,
  re-queries all loaded pages up to 1000). An album already shown with a higher `revision` is never replaced by an
  older one.
- Image URLs carry `?v=revision`; any album update must carry the new revision (the mock bumps it too).
- `MockGateway.matches()`/`comparator()` must keep server filter/sort semantics (ARCHITECTURE §3).
- Search syntax lives in `search-syntax.ts`, and `Topbar` chip removal strips tokens with mirrored regexes —
  change both.
- Keyboard: `App.onKey` (global), `AlbumViewer.onKey` (viewer), `TileGrid.onKey` (focused grid) all listen; the App
  handler skips viewer routes and form fields. Update `ui/settings/keyboard-page.ts` and README for new keys.
- Native `<dialog>`s live in the top layer: `App`'s toasts are hidden behind an open dialog, so `SettingsDialog` mirrors
  `Toasts.list()` inside itself; `app-folder-picker` is rendered inside the settings dialog and opened later, so it
  stacks above it. Folder choice goes through `FolderPicker.pick()` (promise), never a typed path alone.
- The settings draft belongs to the library it was seeded from (`SettingsDialog.seededDir`): a `dataDir` change reseeds
  it, so Save can never push one library's roots into another (the server would drop that library's albums).
- Sidebar state is one pref, `Prefs.sidebar` (`'folders' | 'refine' | 'none'`), not a boolean; the tree owns
  `filters.roots` (always one path, read via `store.folder()`, set via `store.goTo()`), so Refine's badge/Reset use
  `store.refining()`/`resetRefine()` and never count or clear the folder. `store.treeVersion` tells the tree to reload.
- `TileGrid` needs exactly one projected `<ng-template>` for tiles; the empty state is an input `TemplateRef`.
  Library tile ids are album ids; viewer tile ids are `String(entry.index)`.
- `layout.ts` is pure and spec-covered: landscape = `aspect >= 1`, a portrait tile has the landscape tile's area,
  `MAX_SCALE` 0.15. Keep it Angular-free.
- `Icon` uses `bypassSecurityTrustHtml` on its static map only — never pass user input.
- New layout mode → also add it to `LAYOUT_MODES` (Prefs validates stored values against it).
- Catalog/ops state lives beside `AlbumStore`, not in it: `CatalogStore` (entities, card, `version` bumped on its own save/delete/merge/confirm and on
  `catalogChanged`), `OpsStore` (progress, last result); `AlbumStore.onEvent` only routes the events. Display names
  come from `Album.links`; the inputs edit the raw text (`model`, `studio`, …) — never write link names back into it.
- One `Selection` class (`core/selection.ts`, cap `SELECT_CAP` = 500) for albums, photos and entity rows; plain click
  = only this id, Ctrl toggles, Shift ranges over the grid order.
- Dialog-style services return promises: `FolderPicker.pick()`, `Confirm.ask()`, `EntityPicker.pick()`; the context
  menu is `ContextMenu.open(x, y, items)` with data-only `MenuEntry`s built per subject on every open. Their
  components are mounted once in `app.html`.

## Tests

Plain Vitest specs next to the code (`*.spec.ts`, explicit `import { describe, it, expect } from 'vitest'`), no
TestBed so far; pure logic in `core/` is the place to test. Covered: `layout.ts`, `search-syntax.ts`, `paths.ts`, `selection.ts`, the mock catalog (over a `MockGateway`); a root service is built with `Injector.create` and a fake `Gateway` (`catalog-store.spec.ts`).
