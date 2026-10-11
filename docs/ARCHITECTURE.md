# Thumbnailer — architecture and wire contract

Album-centric organizer for studio photo galleries. One self-contained Spring Boot jar (Java 26) serves the compiled
Angular 22 UI, a WebSocket data channel and HTTP image endpoints. Albums live on disk as directories or zip archives;
metadata goes to an embedded SQL database (H2 by default), thumbnails to an embedded key-value store (H2 MVStore by
default). Both stores sit behind small domain ports and are swappable by configuration.

```
thumbnailer/
  backend/    Spring Boot app, package `thumbnailer`          (gradle :backend)
  frontend/   Angular 22 + SCSS, signals, zoneless            (gradle :frontend → dist copied into the jar)
  docs/       this file; map/ = file-level source maps (see INDEX.md)
```

`gradlew build` builds the UI (`npm ci && npm run build`), packages it under `static/` in the jar, runs tests.
`gradlew :backend:bootRun` starts the app at http://localhost:8080. `--thumbnailer.demo=true` generates sample albums.

## 1. Domain model

Primary entity is the **album** (a container of images), never a single photo.

| Album field | Type | Notes |
|---|---|---|
| `id` | string | random UUID, stable for the album's lifetime (survives moves and re-index) |
| `root` | string | scan root that owns it (absolute, normalized with `/`) |
| `path` | string | absolute path of the directory or zip file |
| `kind` | `DIRECTORY` \| `ZIP` | container kind |
| `name` | string | directory / zip base name, overridable by sidecar metadata |
| `cover` | string | entry name of the effective cover image |
| `coverPinned` | boolean | true when the user chose the cover (strategy chain no longer applies) |
| `revision` | long | incremented on every save; image URLs carry it as `?v=` so browser caches never serve a stale thumb |
| `coverPortrait` | boolean | cover thumb is 9:16 (height > width); otherwise 16:9 |
| `imageCount` | int | image entries in the container |
| `bytes` | long | sum of image entry sizes |
| `avgPixels` | long | mean `width*height` of the entries (quality measure, UI shows megapixels) |
| `maxWidth`, `maxHeight` | int | largest dimensions seen |
| `shotDate` | ISO date \| null | earliest EXIF `DateTimeOriginal`, else sidecar `date`, else earliest entry mtime |
| `modifiedAt` | ISO instant | newest entry mtime |
| `indexedAt` | ISO instant | last (re)index time |
| `model`, `studio`, `photographer`, `description` | string \| null | effective metadata (user override, else extracted) |
| `tags` | string[] | lower-case hashtags without `#` ("wedding", "outdoor", "sport", "advertising") |
| `rating` | int | 0 = unrated, 1..10 (user-only) |

Metadata has two layers stored side by side: `extracted` (rebuilt on every index) and `edited` (the user's overrides,
a partial `AlbumMeta` where `""` / `[]` are explicit empties). Effective = `edited` over `extracted`, field by field;
the effective values are what the columns, the search and the wire carry. Re-indexing therefore refreshes extracted
values without touching user edits, and a tag the user removed does not come back.

Entry (an image inside an album): `{ index, name, bytes, width, height, shotAt: ISO local datetime | null }`.
`index` is the position in the container's sorted (by name, case-insensitive) image entry list.

Identity across scans (internal, not on the wire): `contentId` = hash of the sorted image `(name, bytes)` list;
`signature` = hash of `(name, bytes, mtime)` over **all** container entries, sidecar text files included, so a
metadata-only edit triggers a re-index. Identical copies at two paths are two albums; a copy never inherits edits.

## 2. Backend (package `thumbnailer`)

Framework-free domain in the middle, adapters at the edge. Package = feature, one owner per rule.

| Package | Responsibility |
|---|---|
| `album` | `Album`, `Entry`, `AlbumQuery` (with nested `Sort`), `AlbumPatch`, ports `AlbumRepository` (search/find/save/delete/byRoot/facets) and `BlobStore` (put/get/remove bytes by key). Pure records + interfaces. |
| `source` | Where albums live. `AlbumContainer` (list sorted image entries, open entry stream, close) with `DirectoryContainer` and `ZipContainer`; `ContainerFactory` SPI (`@Component`s, nested `DirectoryContainer.Factory` / `ZipContainer.Factory`) + registry `Containers` (`archive(path)` recognises archive files during discovery, `open(kind, path, exclusions)`; future: 7z, rar via commons-compress); image extension policy; `Exclusions` (glob patterns with `*`, matched against the normalized absolute path, e.g. `*/raw/*`, `*_tmp*`). |
| `image` | `ImageProbe` (width/height/orientation/EXIF date without full decode: ImageIO header + metadata-extractor), `Thumbnailer` (subsampled decode → EXIF rotate → centre-crop to 16:9 or 9:16 → scale so the longer side = configured width, default 640 → JPEG bytes). |
| `metadata` | `MetadataExtractor` SPI returning a partial `AlbumMeta` from a container; ordered composite where an earlier extractor's non-blank field wins and tags are unioned. Implementations: (1) sidecar text (`album.txt`, `album.json`, or a single `*.txt` inside the album: `key: value` lines for name/model/studio/photographer/date/description, `#tag` tokens anywhere → tags); (2) **file name** — the real data looks like `CLS-2019-01-14 - EL-Studio - Ellena - New Model - (vol-1) 1800px (x133) 52.3Mb`: first `yyyy-MM-dd` → date; noise `\d+px`, `(x\d+)`, `[\d.]+ ?[MG]b` → dropped; `(vol-N)` / `VOL-N` → description "vol-N"; a leading code like `CLS` before the date → tag (lower-case); the remaining ` - ` separated parts: a part containing `studio`/`photo`/`lab` (case-insensitive) → studio, the first other part → model, the rest → description; `name` = base name without the noise; (3) EXIF (`DateTimeOriginal` → date, `Artist` → photographer). |
| `cover` | `CoverStrategy` SPI (`Optional<Entry> pick(List<Entry>)`): name hints (`cover`, `poster`, `logo`, `_cv` suffix) then smallest-by-bytes fallback. `CoverSelector` yields candidates lazily — pinned cover (if it still exists), name hint, smallest, then every other entry — and the indexer renders the first readable one. The chain is currently a fixed list in `CoverSelector`. |
| `index` | `AlbumDiscovery` walks a root (a directory holding ≥ `minImages` image files directly is an album; nested dirs are their own albums; a zip holding images is an album), skips exclusions. `AlbumIndexer` builds an `Album` from a container (probe entries, metadata, cover, thumb, entry list); when re-indexing it reuses the stored probe of every entry whose `(name, bytes, mtime)` is unchanged, so adding one photo to a 500-photo album probes one file. `Reconciler` compares the known state with the discovered one: unchanged (same path, same signature) → skip; changed (same path, new signature) → re-index keeping id and edits; moved or renamed (new path, `contentId` equals an album, in any root, whose old path no longer exists) → keep id and edits, re-extract metadata from the new name, bump revision; otherwise new; known albums of that root not seen → removed. **Removal happens only after a scan of that root completed without cancellation or walk errors; an unreachable root (unplugged disk) is skipped entirely; an unreadable subfolder (access denied) is skipped without spoiling the walk, since it can never have been indexed.** `ScanService` runs scans asynchronously (discovery on one thread, indexing on a CPU-sized pool), publishes `ScanProgress`, supports cancel (cancelled workers publish nothing), one scan at a time. |
| `album` (service) | `AlbumService` is the single owner of album mutation: commit of an indexed album (merges edits over extracted, bumps `revision`), user patches, removal, and event publishing after the store write. Per-album mutations are serialized so a scan cannot overwrite a concurrent edit. Thumbnails are a regenerable cache: a missing cover thumb is rebuilt on request. |
| `store` | Adapters: `jdbc/JdbcAlbumRepository` (Spring `JdbcClient`, portable SQL, dynamic WHERE from `AlbumQuery`, `schema.sql`), `mvstore/MvStoreBlobStore`. Selected via `thumbnailer.store.*` properties; another database = another adapter + JDBC URL, no domain change. |
| `settings` | `Settings` record persisted as `settings.json` in the data dir; roots with per-root exclusions, global exclusions, scan-on-start. Root status (exists, album count, last scan) is derived at request time. |
| `web` | `ws/` protocol records (sealed `Request`/`Event`, Jackson `@JsonTypeInfo(use = NAME, property = "type")` with the lower-case names below), `AlbumSocket` handler + session hub (broadcast; every session wrapped in `ConcurrentWebSocketSessionDecorator` with a buffer limit so a slow client cannot block a scan), `ImageController` (HTTP image bytes), `SpaFallback` (SPA forward), reveal folder (`AlbumSocket.reveal`: `explorer /select,` on Windows, else `java.awt.Desktop.open` of the parent). WS origins are restricted to `localhost`/`127.0.0.1`. The server binds to `127.0.0.1` by default (`server.address`); no authentication. |
| `demo` | `--thumbnailer.demo=true`: generates ~40 sample albums (dirs and zips, landscape/portrait covers, real-data-style file names plus a few sidecar files) under `<dataDir>/demo` and registers that root when none is configured. Used by the automated checks; real data lives in `C:/work/albums`. |

Configuration (`application.yml`, overridable by CLI/env):

```yaml
thumbnailer:
  data-dir: ${user.home}/.thumbnailer   # settings.json, albums.mv.db (H2), thumbs.mv (MVStore)
  thumb-width: 640                      # longer side of a thumbnail
  min-images: 2                         # directory needs this many images to count as an album
  index-threads: 0                      # 0 = available processors
  demo: false
server.port: 8080
```

Concurrency: scans on threads owned by `ScanService`; WebSocket sends are serialized per session;
`BlobStore` and `AlbumRepository` implementations are thread-safe. Blob keys: `cover/{id}/{revision}` and
`thumb/{id}/{revision}/{index}`; keys of older revisions are dropped on every save and on removal, so a late render
of an old revision can never overwrite the current cover. Events are published after the store write, outside the
per-album lock, and broadcast from a single sender thread.
MVStore auto-commits (default delay) and is closed on shutdown. Image decoding isolates corrupt entries (skipped
with a warning), applies EXIF orientation (including mirrored variants), and relies on TwelveMonkeys `imageio-jpeg`
(CMYK/ICC JPEGs) and `imageio-webp`; `ImageIO.scanForPlugins()` runs at startup so plugins load from the fat jar.

## 3. Transport

**WebSocket `/ws`** carries JSON text frames. Every message has `type`. Requests carry a client-chosen `id`;
the response echoes it; broadcasts have no `id`.

Client → server:

| type | payload | reply |
|---|---|---|
| `search` | `query: AlbumQuery` | `results` |
| `album` | `albumId` | `albumDetails` |
| `update` | `albumId, patch: AlbumPatch` | `albumChanged` (also broadcast) |
| `scan` | `roots: string[] \| null` (null = all) | `ok`, then `scanProgress` broadcasts; `error` while a scan is already running |
| `cancelScan` | – | `ok` |
| `getSettings` | – | `settings` |
| `setSettings` | `settings: Settings` | `settings`; `error` when two roots overlap. Albums of roots that were removed are deleted (`albumRemoved` each) |
| `facets` | `query: AlbumQuery \| null` | `facets` |
| `reveal` | `albumId` | `ok` / `error` (opens the containing folder in the OS file manager, selecting the album) |

Server → client:

| type | payload |
|---|---|
| `results` | `albums: Album[], total: int` (total = matches ignoring offset/limit) |
| `albumDetails` | `album: Album, entries: Entry[]` (entries capped at 5000) |
| `albumChanged` | `album: Album` |
| `albumRemoved` | `albumId` |
| `scanProgress` | `running, root, discovered, indexed, changed, removed, message, startedAt, finishedAt` (throttled, ≤ 4/s) |
| `settings` | `settings: Settings, dataDir, roots: [{ path, exists, albumCount, lastScanAt }]` |
| `facets` | `tags, models, studios, photographers: [{ value, count }]` (top 50 each, by count) |
| `ok` | – |
| `error` | `message` |

Live updates: the client re-runs its current search (trailing 600 ms, max wait 1.5 s, re-querying every loaded page
up to 1000) on `albumChanged` / `albumRemoved` / `scanProgress`, so results appear while a scan runs; responses of
superseded searches are dropped (generation counter in `AlbumStore`; the message `id` only correlates request and
reply); on reconnect it reloads settings and facets and re-runs the search. No server-side subscriptions. The client
currently requests `facets` unfiltered (global suggestions).

```ts
AlbumQuery {
  text?: string            // matches name, model, studio, photographer, description, tags (case-insensitive substring)
  tags?: string[]          // every tag must be present
  model?, studio?, photographer?: string   // exact, case-insensitive
  roots?: string[]         // limit to these scan roots (or path prefixes)
  kind?: 'DIRECTORY' | 'ZIP'
  minBytes?, maxBytes?: number
  minCount?, maxCount?: number
  minMegapixels?, maxMegapixels?: number   // compared with avgPixels / 1e6
  dateFrom?, dateTo?: string               // ISO dates, inclusive, on shotDate
  minRating?, maxRating?: number
  sort: 'NEWEST' | 'OLDEST' | 'LARGEST' | 'SMALLEST' | 'MOST_PHOTOS' | 'FEWEST_PHOTOS' | 'BEST_QUALITY' | 'TOP_RATED' | 'NAME' | 'RECENTLY_INDEXED'
  offset: number, limit: number            // limit ≤ 1000
}
AlbumPatch { name?, model?, studio?, photographer?, description?: string; tags?: string[]; rating?: number; cover?: string }
           // null/absent = unchanged; "" / [] = explicit empty override; cover: entry name pins it, "" unpins (strategies pick again)
Settings   { roots: [{ path: string, exclusions: string[] }], exclusions: string[], scanOnStart: boolean }
```

Absent/null fields in `AlbumQuery` mean "no filter"; absent/null fields in `AlbumPatch` mean "unchanged".
Secondary sort for every `Sort` is `name` ascending; `NEWEST`/`OLDEST` put albums without `shotDate` last.

**HTTP** (binary, cacheable: `Cache-Control: max-age=86400`, `ETag`):

| route | body |
|---|---|
| `GET /img/{albumId}/cover?v={revision}` | cover thumbnail JPEG (regenerated when the cover changes) |
| `GET /img/{albumId}/{index}/thumb?v={revision}` | entry thumbnail JPEG (generated lazily, cached in the blob store) |
| `GET /img/{albumId}/{index}?v={revision}` | original bytes, content type by extension |
| `GET /ws` | WebSocket |
| `GET /**` (no dot in last segment) | SPA `index.html` |

## 4. Frontend (Angular 22, standalone, signals, zoneless, SCSS)

```
src/app/
  app.ts  shell (topbar, filters, grid, details, statusbar, settings, toasts; global keyboard)
  core/   model.ts (contract types above), gateway.ts (abstract Gateway: search/album/update/scan/settings/facets/reveal,
          image URLs, `events`), ws-gateway.ts (reconnecting WebSocket, id correlation), mock-gateway.ts (in-browser dummy
          albums with canvas-drawn covers; used with `?mock`, or in a dev build unless `?live`), store.ts (query/results/
          selection/settings signals), prefs.ts (persisted UI prefs incl. theme), toasts.ts, format.ts (pipes),
          layout.ts (bricks/grid layout engine), search-syntax.ts (`#tag`, `model:`/`studio:`/`by:`/`kind:` tokens)
  ui/     topbar, filters-panel (left: refine search; overlay below 1100px), tile-grid (generic virtualized grid, also
          used by the viewer), album-card, details-panel (right: meta + edit; floating, inline ≥1400px, bottom sheet
          ≤760px), album-viewer (/album/:id: entries grid, lightbox, set cover), settings-dialog (roots, exclusions,
          appearance, scan), shared (icon, rating, range)
styles/   _tokens (CSS variables, light/dark), _base (reset + shared classes .btn .input .chip .segmented .panel)
```

File-level map: [docs/map/frontend.md](map/frontend.md); backend: [docs/map/backend.md](map/backend.md).

Layout engine (`layout.ts`): pure function `layout(tiles, { width, columns, gap, mode }) → { placements[], height }`.
Portrait and landscape tiles always have the same area (a portrait tile is the landscape tile rotated); empty space is
preferred over small thumbnails. Mode `bricks` fills landscape rows of `columns` tiles and collects portrait tiles into
their own rows holding as many as fit (scaled ≤ 15% to close the row), so the row kinds interlock without cropping and
the order shifts by at most one row per kind. Mode `grid` keeps strict order: rows of square cells, each tile fitted and
centred in its cell, rows as tall as their tallest tile. Placements are absolute (`x, y, w, h`), so the grid renders
only the tiles intersecting the viewport (virtual scrolling for thousands of albums).
