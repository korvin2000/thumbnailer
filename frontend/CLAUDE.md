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
- Sidebar state is one pref, `Prefs.sidebar` (`'folders' | 'refine' | 'none'`; `folders` is the Browse tab, whose tree is picked by `Prefs.groupBy`), not a boolean; the tree owns
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
  = only this id, Ctrl toggles, Shift ranges over the grid order. The entity scope is `filters.entities` (ids, all must
  match) or `filters.missing`; names for its chips come from `CatalogStore.known` (filled by every page and card), never
  from a second request per chip.
- Modal `<dialog>`s must keep keyboard focus inside (focus the dialog itself after `showModal()` and after a clicked
  button disappears): with focus on `body`, Esc reaches `App.onKey` and clears the album selection instead of closing.
- Dialog-style services return promises: `FolderPicker.pick()`, `Confirm.ask()`, `EntityPicker.pick()`; the context
  menu is `ContextMenu.open(x, y, items)` with data-only `MenuEntry`s built per subject on every open. Their
  components are mounted once in `app.html` (the folder picker too: a `<dialog>` inside the closed settings dialog would
  have no box; top-layer order, not DOM position, stacks a later `showModal()` above).
- Menus and operations: `ui/menus.ts` builds entries from a `Target` (ids + the loaded albums, or a node's query) and an
  actions interface; `ui/actions.ts` is the one implementation (every flow = preflight → `Confirm` → `OpsStore.run`).
  Keep the builders pure (spec-tested) — no `inject()` there; put a new action on the interface and on `Actions`.
- The settings dialog is `@defer`red (`SettingsLink.open(page)` loads and opens it) and the viewer and photo list are
  `loadComponent` routes: never import them eagerly from `App` or the initial bundle grows past the 600 kB budget.
- The details panel's entity mode is `CatalogStore.cardId` (set by `openCard` from anywhere); the card component only
  calls `openCard` when its input differs, and `closeCard` on destroy.
- Operation words come from `OpsStore.opNoun/opVerb`; a clean result is a toast, one with skipped/failed items is
  `OpsStore.issues`, which the app turns into a `Confirm` without Cancel.
- Synthetic key events in tests or the browser pane must target an element (`document.body`), not `document`: the key
  handlers call `e.target.closest(...)`.

## Tests

Plain Vitest specs next to the code (`*.spec.ts`, explicit `import { describe, it, expect } from 'vitest'`), no
TestBed so far; pure logic in `core/` (and the pure `ui/menus.ts`, `ui/op-confirm.ts`, `ui/viewer/photo-order.ts`) is the place to test. Covered: `layout.ts`, `search-syntax.ts`, `paths.ts`, `selection.ts`, the mock catalog (over a `MockGateway`), the menu builders (with a recording stub of the actions), the operation → dialog mapping; a root service is built with `Injector.create` and a fake `Gateway` (`catalog-store.spec.ts`).
