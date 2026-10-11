# PLAN — state, next steps, decisions

[↑ INDEX](INDEX.md) · requirements: [SPEC.md](SPEC.md)

Living document: update **State** and tick items when work lands; add a **Decisions** row when a choice is costly to
undo. Reference SPEC IDs.

## State (2026-10-11, v0.1.0, single initial commit + uncommitted tree)

- Feature-complete against the brief except the ◐/○ rows in SPEC (C2, M5, Q5, N6).
- Checks green: backend `:backend:test` 20/20 (6 classes), frontend `ng test` 9/9 (2 specs), `ng build` clean
  (initial bundle ≈ 368 kB raw / 98 kB transfer, budget 600 kB).

## Next (ordered)

1. **C2/N6 pluggable cover strategies** — `CoverSelector` takes ordered `List<CoverStrategy>` beans instead of the
   hardcoded `List.of(NAME_HINT, SMALLEST)`; keep "pinned first, then all entries" fallback.
2. **Test gaps (backend)** — `ZipContainer`/`Containers` (nested entries, exclusions), `AlbumDiscovery`
   (min-images, AccessDenied, incomplete walk), `ImageController` (ETag/404/original stream), `MvStoreBlobStore`
   prefix removal, `SettingsStore` round trip, WS `update`/`facets`/`scan`/`reveal`.
3. **Test gaps (frontend)** — `AlbumStore` (generation guard, `loadMore` identity check, refresh max-wait),
   `MockGateway.matches/comparator` parity with server semantics, `Prefs` persistence.
4. **M5 editable shot date** — add `date` to `AlbumPatch` → `AlbumMeta` edited layer → details panel.
5. **Q5 contextual facets** — call `gw.facets(filters)` so suggestions narrow to the current query (protocol and
   `WsGateway` already support it; the store calls it unfiltered).
6. **More containers** — 7z/rar via commons-compress as new `ContainerFactory` beans (INTENT extension direction).

## Tech debt / hygiene

- `frontend/tsconfig.json` has no `"strict": true`; enabling it needs a pass over nullability.
- `.prettierrc` says `printWidth: 100` but the code is hand-formatted dense (lines up to ~200); decide one way before
  ever running Prettier on whole files.
- `frontend/.vscode/launch.json` "ng test" targets the Karma debug page (stale for Vitest).
- Entry `index` = position among *readable* images; an image that becomes unreadable shifts later indices (and their
  thumbnail URLs) on the next re-index. Acceptable now; revisit if entry ids must be stable.

## Open questions

- Should the dev UI fall back to the mock automatically when no backend answers? (Today: dev = mock unless `?live`;
  prod = live unless `?mock`.)

## Decisions (why things are the way they are)

| decision | reason |
|---|---|
| H2 (SQL) for metadata, H2 MVStore for thumbnails, both behind ports | one dependency, embedded, file-based; ports keep engines swappable |
| WebSocket for data/events, HTTP for image bytes | async push + id-correlated requests; images stay cacheable by the browser |
| Two metadata layers (`extracted`, `edited`), effective = edited over extracted | re-index refreshes facts without losing user edits; explicit empties stick |
| `revision` bumps on every save and is part of blob keys and image URLs | no stale thumbnails in browser or store; a late render of an old revision cannot win |
| `AlbumService` is the only writer, striped per-album locks, events after the lock | a scan cannot overwrite a concurrent user edit; no I/O under lock |
| Removal only after a complete walk; unreachable roots skipped | an unplugged disk or a cancelled scan never wipes the index |
| Identity: `signature` (all files + mtime) vs `contentId` (images, name + bytes) | sidecar edits trigger re-index; moves/renames keep id and edits |
| Roots must be disjoint | nested roots would index the same album twice under different ids |
| Mock gateway in the browser | UI work without a backend; also the demo mode |
| Custom layout engine (bricks/grid) as a pure function | testable, absolute placements enable virtual scrolling |
