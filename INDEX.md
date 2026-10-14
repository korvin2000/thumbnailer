# INDEX — navigation hub

Read top-down; stop at the first level that answers the question. Maps list every source file with its role and key
symbols, so open code only after the map points to it.

## L0 — documents

| file | answers | change when |
|---|---|---|
| [INTENT.md](INTENT.md) | why: users, outcomes, quality bar, non-goals | product direction changes |
| [SPEC.md](SPEC.md) | what: requirements with IDs, status, evidence | behaviour is added/changed |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | how: domain model, packages, **wire contract** (§3), layout engine | design or protocol changes |
| [PLAN.md](PLAN.md) | now/next: state, backlog, open questions, decision log | work starts/finishes |
| [CLAUDE.md](CLAUDE.md) | agent rules: commands, invariants, conventions | workflow changes |
| [README.md](README.md) | user-facing build/run/config/keyboard | user-visible changes |
| [goal.md](goal.md) | original brief (RU/EN), source of INTENT | never |
| [hdd_ssd_fix_goals.md](hdd_ssd_fix_goals.md) | HDD vs SSD scan and viewer strategy: problem, design (lanes, detection, gallery pass), plan, test rigs (RU) | the plan is refined or lands |

## L1 — source maps

- **Backend** (Java 27, Spring Boot 4.1 on Undertow, `:backend`) → [docs/map/backend.md](docs/map/backend.md) · rules [backend/CLAUDE.md](backend/CLAUDE.md)
  `album` (domain + ports + AlbumService) · `source` (dirs/zips) · `image` · `metadata` · `cover` · `index` (scan) ·
  `settings` · `store` (H2 JDBC, MVStore) · `web` (WS `/ws`, HTTP `/img`) · `demo`
- **Frontend** (Angular 22, `:frontend`) → [docs/map/frontend.md](docs/map/frontend.md) · rules [frontend/CLAUDE.md](frontend/CLAUDE.md)
  `core/` (model, gateways, store, layout engine, prefs) · `ui/` (components) · `styles/` (tokens, base)
- **Build/tooling**: [settings.gradle.kts](settings.gradle.kts) · [backend/build.gradle.kts](backend/build.gradle.kts) (bundles UI into `static/`) ·
  [frontend/build.gradle.kts](frontend/build.gradle.kts) (npm glue) · [.claude/launch.json](.claude/launch.json) (preview servers `frontend`, `backend`, `backend-demo`) ·
  [.claude/skills/](.claude/skills/) (`compact-code`, `god-style`, `java-sdk-design`) · [.claude/rules/](.claude/rules/) (path-scoped agent rules:
  `docs-sync`, `wire-contract`) · [.claude/settings.json](.claude/settings.json) → Stop hook [.claude/hooks/docs-sync-check.mjs](.claude/hooks/docs-sync-check.mjs)
- **Natives** (libjpeg-turbo, libwebp/webp4j, 7-Zip-JBinding: sources, platforms, loader, update recipe) → [docs/natives.md](docs/natives.md)
- **Resampler benchmark** (standalone Gradle build, not in the app) → [scalebench/README.md](scalebench/README.md) — quality (PSNR/SSIM vs float Lanczos3, zone plate) and speed of every candidate
- **KV benchmark** (standalone Gradle build, not in the app) → [kvbench/README.md](kvbench/README.md) · evaluation
  [docs/kvstore-evaluation.md](docs/kvstore-evaluation.md) — which embedded store backs the thumbnail `BlobStore`

## L2 — task router (what to touch for a typical change)

| change | files (B = backend `thumbnailer/`, F = frontend `src/app/`) |
|---|---|
| search filter / sort | B `album/AlbumQuery` · B `store/jdbc/JdbcAlbumRepository` (`Where`, ORDER BY) · F `core/model.ts` · F `core/store.ts` (`query`) · F `ui/filters-panel.ts` (Refine tab) · F `ui/topbar.ts` (chips) · F `core/mock-gateway.ts` (`matches`, `comparator`) · ARCH §3 |
| folder tree / location bar | F `ui/folder-tree.ts` · F `ui/location-bar.ts` · F `core/store.ts` (`folder`, `goTo`, `rootOf`, `treeVersion`) · F `ui/sidebar.ts` (tabs, `Prefs.sidebar`) · B `web/ws/AlbumSocket` `folders` · B `AlbumRepository.folders` (JDBC) · F `core/mock-gateway.ts` (`folders`) |
| WS message | B `web/ws/Request`/`Event` · B `web/ws/AlbumSocket.handle` · F `core/model.ts` · F `core/gateway.ts` · F `core/ws-gateway.ts` · F `core/mock-gateway.ts` · ARCH §3 |
| album field | B `album/Album` (+`AlbumMeta`/`AlbumPatch` if editable) · `schema.sql` · B `JdbcAlbumRepository` · F `core/model.ts` · F `ui/details-panel.ts` · F mock · ARCH §1 |
| user edit behaviour | B `album/AlbumService.patch` · B `album/AlbumMeta.over` · F `core/store.ts` `patch` · F `ui/details-panel.ts` |
| archive / container kind | a format 7-Zip reads: B `source/SevenZipContainer.EXTENSIONS`; else B `source/` new `ContainerFactory` `@Component` (see `ZipContainer.Factory`) · B `source/Kind` · F `Kind` in `core/model.ts` |
| image codec / render pipeline | B `image/Thumbnailer` (chain, `GAP`) · B `image/TurboJpeg` · B `image/WebP` · B `image/Resampler` (measure with [scalebench](scalebench/README.md)) · B `image/Header` + `ImageProbe` (probes) · natives: [docs/natives.md](docs/natives.md) |
| metadata source | B `metadata/` new `MetadataExtractor` `@Component @Order(n)` |
| cover rule | B `cover/CoverStrategy` constant + `CoverSelector` chain |
| what counts as album / identity / removal | B `index/AlbumDiscovery` · B `index/Reconciler` · B `index/ScanService` (scopes) · B `album/AlbumService.commit`/`retainRoots` (tombstones, re-rooting) · test `index/ScanTest` |
| thumbnail size/format | B `image/Render` (spec; `epoch()` invalidates stored covers) · B `image/Thumbnailer` · B `image/Resampler` · `settings.json` `covers`/`thumbs` (F `ui/settings/thumbnails-page.ts`) · B `AlbumService` limiters (`coverSlots`, `thumbSlots`) |
| libraries / restart / optimize | B `settings/Libraries` · B `settings/LibraryLocator` · B `ThumbnailerApplication.restart` · B `web/ws/Library` · B `store/*` `stats`/`compact` · F `ui/settings/library-page.ts` · ARCH §2 `settings`, §3 |
| storage engine | B `store/<engine>/` adapter with `@ConditionalOnProperty(thumbnailer.store.albums\|blobs)` · `application.yml` · blob engines: measure first with [kvbench](kvbench/README.md) |
| tile layout | F `core/layout.ts` + `layout.spec.ts` · F `ui/tile-grid.ts` · ARCH §4 |
| search-box syntax | F `core/search-syntax.ts` + spec · F `ui/topbar.ts` (chip-removal regexes) |
| keyboard shortcut | F `app.ts` `onKey` · F `ui/album-viewer.ts` `onKey` · F `ui/tile-grid.ts` · cheat sheet in `ui/settings/keyboard-page.ts` · README |
| theme / colours | F `styles/_tokens.scss` · F `core/prefs.ts` |
| user preference | F `core/prefs.ts` (localStorage `thumbnailer.*`) · F `ui/settings/appearance-page.ts` |
| server-side setting (settings.json) | F `core/model.ts` `Settings` · F `ui/settings/folders-page.ts` / `thumbnails-page.ts` (draft in `settings-dialog.ts`) · F `core/mock-gateway.ts` defaults · B `settings/` |
| config key | `application.yml` · `@Value` site · README table · ARCH §2 |
| disk read strategy (SSD/HDD) | B `source/Disks` (detection, lanes) · B `album/AlbumService.render` (turn → queue → decode slots) · B `index/Reconciler` + `AlbumIndexer` (album turn) · B `settings/Settings.Root.storage` · F `ui/settings/folders-page.ts` · [hdd_ssd_fix_goals.md](hdd_ssd_fix_goals.md) |
