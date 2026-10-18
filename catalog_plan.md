# Catalog plan — models, studios, photographers, genres, favorites, file operations, viewer

Status: v2.1; phases 0, 1 and 2 merged on main 2026-10-18 (see PLAN.md State); plan from [todo/goals.txt](todo/goals.txt), [todo/ModelCard.md](todo/ModelCard.md),
[todo/StudioCard.md](todo/StudioCard.md), the owner's duplicate-detection request (§3.6) and the owner's direction
on safe-delete and the data model (§2.1, §4.1). v1 was reviewed by Codex (gpt-6-astra, xhigh) and an Opus pass;
every finding is folded in or answered in §9; Windows/JDK facts come from probes. **Phase 0 (§8) landed
2026-10-18** (uncommitted; state in [PLAN.md](PLAN.md)); phase 1 tracks not started. Tracked
in [PLAN.md](PLAN.md) (Next 0). Code and contracts today: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md),
[docs/map/backend.md](docs/map/backend.md), [docs/map/frontend.md](docs/map/frontend.md), [backend/CLAUDE.md](backend/CLAUDE.md).

## 0. What changes in the product

Thumbnailer grows from an album organizer into a **catalog of five linked entity kinds**: gallery (album), model,
studio/agency, photographer, genre — plus named **favorites lists** for albums and single photos. Three invariants
change and are rewritten in [INTENT.md](INTENT.md) and [backend/CLAUDE.md](backend/CLAUDE.md) when phase 0 lands:

- *"The library on disk is read-only to the app"* → *"the app changes the library only through explicit, confirmed,
  journaled operations (`ops`, §4), one at a time, never during scanning or browsing; a source is deleted only after
  the result was verified byte for byte, and only a confirmed Delete removes files on purpose; safe-delete is the
  `hidden` flag in the index and touches no file"*. Reading paths stay as they are.
- *"never a single photo"* → *"albums are the unit of search and layout; single photos can be rated, listed as
  favorites and copied out"*.
- *"every save bumps `revision`"* stays for album saves; a new sentence: *"catalog changes (entity rename, alias,
  merge) do not touch album rows except the `search` column and are announced by `catalogChanged`, which every
  view treats as 'reload what you show'"*. `revision` keeps meaning "this album's own state and image URLs".

Everything else in INTENT holds: one jar, local only, fast at 100 k albums, compact code, SPIs.

## 1. What exists and is reused

| already there | reused for |
|---|---|
| `album.model/studio/photographer` text, `extracted`/`edited` layers, explicit empties, `AlbumMeta.over/orElse` | the **text stays the source of truth**; entity links are an index derived from it on every save (§2.5) |
| `AlbumService` single writer, striped locks, events after the lock, `revision`, `commit` re-reading under the lock | every catalog write and every post-operation re-index; an edit made during an operation survives it |
| `commit(base, indexed, …)`: prior state from the stored album when its old path is gone | re-index after pack/repack/encrypt keeps id, rating, edits, links, marks (§4.6) |
| tombstones by `contentId`, `Reconciler.moved()` | an album restored from a backup or moved back takes its state back; unpack discovery (§4.5) |
| `AlbumContainer` readers for dir/zip/rar/7z/tar/iso, one-pass `heads` for solid archives | unpack/repack read through them (raw, §4.4) in one pass per archive |
| `Cry` (decrypt, key in session memory), `CryTest.encrypt` as the byte-exact format reference | `Cry.encrypt` (streaming) + `Cry.plainName` move into main code; the ops snapshot the key |
| `Disks` lanes, `Limiter`s | ops read and write in a disk turn like a scan |
| `ScanProgress` throttled broadcast; `ScanService.scan(paths, cancelled)` (synchronous) | `OpProgress`; the scoped scan an unpack or copy runs inside its own exclusive section |
| `TileGrid`, `AlbumCard`, `DetailsPanel`, `FolderTree`, `FolderPicker.pick()` promise dialog, `SortMenu` | selection, context menus, entity trees, entity cards, confirm dialogs follow these patterns |
| `MetadataExtractor` chain, `CoverStrategy` SPI | become the plugin contract (§5) |
| `Settings`/settings dialog pages, `Prefs` | copy targets and operation defaults (server), viewer defaults and group-by (browser) |

## 2. Data model

Principle: **one mechanism, six kinds.** Models, studios, photographers, genres, hashtags and favorites lists are
rows of one `entity` table with a `kind`; everything that links an album to one of them is one `album_link` row;
everything that links two entities (a model's roles, a studio's roster, a model's own hashtags) is one `entity_link`
row. Cards differ in *data* (which profile fields and relationship sections a kind shows), not in code paths. Tags
join the mechanism on purpose: today a `#tag` filter is `LIKE '% tag %'` over every row and facets are counted in
Java; as entities they get the same indexed filter, the same `GROUP BY` facet and rename/merge for free (`beauty` ↔
`beautiful`), and a model's hashtags are ordinary entity links instead of a JSON list.

### 2.1 Keys, types and space

- **Albums get a compact surrogate key** `album.seq INT` (identity, unique) next to the UUID `id`, which stays the
  external, stable identity (wire, image URLs, tombstones). Every new table references `seq` (4 bytes), never the
  36-byte UUID: a link row is 4 + 1 + 4 bytes of key instead of 41, and its two indexes shrink alike. `seq` is part
  of the identity a tombstone keeps (§2.4) so revived albums find their rows again. Existing tables (`entries`,
  `removed.id`) keep the UUID: one row per album, a migration would buy nothing.
- **Kinds and roles are `TINYINT` codes** with a fixed mapping in `EntityKind` (`1 MODEL · 2 STUDIO · 3 PHOTOGRAPHER
  · 4 GENRE · 5 TAG · 6 LIST`; never the enum ordinal), ratings `TINYINT`, flags `BOOLEAN`, text `VARCHAR` with the
  bound the field has, JSON only for card-only fields.
- **Counts that trees show are stored, not computed**: `entity.albums` = linked albums that are *visible* (live and
  not hidden). Every save computes `delta = (visibleAfter ? newLinks : ∅) − (visibleBefore ? oldLinks : ∅)` with
  `visibleBefore = false` for a new or revived album, so hide/unhide, edits of hidden albums, removal and tombstone
  revival all come out right; a merge ends with a recount of the surviving entity. **While a scan or the backfill
  holds the gate the per-save deltas are skipped and `Catalog.recount()` runs once at the end** (otherwise every
  album of a popular studio would contend on one hot entity row under `index-threads` parallel commits); user
  actions maintain the counter transactionally. `recount()` also runs on optimize. A tree opens with one indexed
  read instead of a 500 k-row join. The two counts that stay computed on demand: a favorites list's photos
  (`photo_link_entity`, lists are few) and a kind's "Unassigned" albums (an anti-join probing the `album_link`
  primary key per visible album, ≈ 100 k index probes, cached per tree open until the next `catalogChanged`).
- **No foreign keys** (as today; no migration tool): the service keeps integrity in its transactions (delete entity =
  delete its aliases, album links, entity links, photo links; delete album = keep its rows for the tombstone, purge
  them with it).

Budget at 100 k albums (≈ 5 links per album; H2 row overhead ≈ 8 B, COMPRESS halves pages):

| table | rows | ≈ bytes/row | ≈ total with indexes |
|---|---|---|---|
| `entity` | 5–30 k | 60 (name twice: `name`, generated `sort_name`) | 2–4 MB |
| `alias` | 10–60 k | 40 | 2–4 MB |
| `album_link` | 500 k | 10 + alias (mostly NULL) | 6 MB + two 5 MB indexes |
| `entity_link` | < 10 k | 12 | negligible |
| `photo`, `photo_link` | only marked photos (10–100 k) | 40–80 | ≤ 10 MB |
| `album` additions | 100 k | 4 (`seq`) + 1 (`hidden`) + `bio` (mostly NULL) | 1 MB |

About +25 MB on a database that is ≈ 200 MB live at this size (PLAN); the `tags` column and its `LIKE` scans can go
once the link filter is in (kept during the transition for the mock-parity tests).

### 2.2 Entities

```sql
CREATE TABLE IF NOT EXISTS entity (
    id        INT          AUTO_INCREMENT PRIMARY KEY,
    kind      TINYINT      NOT NULL,   -- 1 model · 2 studio/agency · 3 photographer · 4 genre · 5 tag · 6 favorites list (EntityKind codes)
    name      VARCHAR(256) NOT NULL,   -- primary name as displayed; tags: the normalized tag itself
    rating    TINYINT      NOT NULL,   -- 0 = unrated … 10 (models, studios, photographers; 0 for the rest)
    auto      BOOLEAN      NOT NULL,   -- created by resolution from text, not yet confirmed (models, studios, photographers, genres only; tags, lists: false)
    albums    INT          NOT NULL,   -- visible (live, not hidden) albums linked; maintained per §2.1
    profile   CLOB                     -- JSON, kind-specific card fields (§7.3); null = none; ≤ 4 KiB stays in the row (MAX_LENGTH_INPLACE_LOB=4096 in the H2 URL)
);
ALTER TABLE entity ADD COLUMN IF NOT EXISTS sort_name VARCHAR(256) GENERATED ALWAYS AS (LOWER(name));
CREATE INDEX IF NOT EXISTS entity_kind ON entity (kind, sort_name);      -- trees by name
CREATE INDEX IF NOT EXISTS entity_count ON entity (kind, albums DESC);   -- trees by count

-- every name an entity is known by, including its primary name; the lookup key of resolution (§2.5)
CREATE TABLE IF NOT EXISTS alias (
    kind      TINYINT      NOT NULL,
    norm      VARCHAR(256) NOT NULL,   -- `Catalog.norm`: trimmed, lower-case, whitespace collapsed, cut at 256; tags: `AlbumMeta.tag` (hyphens)
    entity_id INT          NOT NULL,
    PRIMARY KEY (kind, norm)
);
CREATE INDEX IF NOT EXISTS alias_entity ON alias (entity_id);

CREATE TABLE IF NOT EXISTS meta (name VARCHAR(32) PRIMARY KEY, val VARCHAR(256) NOT NULL);   -- catalog_version (§2.5 backfill); KEY/VALUE are reserved words in H2 2.x
```

`profile` holds what only the card shows (born, birthplace, hair, height, web page, years active, notes): JSON keeps
the schema stable while the cards evolve, and none of it is filtered or sorted by. Fields that are listed, sorted or
counted are columns.

**Policy:** a normalized name is unique *per kind* (a model "Nikka" and a studio "Nikka" coexist; a second model
"Nikka" is refused: "Merge instead?"). Not a technical necessity but what makes resolution unambiguous; the price is
one rename when two different people share a stage name, which the alias mechanism covers ("Nikka (FM)").

### 2.3 Links

```sql
ALTER TABLE album ADD COLUMN IF NOT EXISTS seq INT AUTO_INCREMENT;        -- surrogate key (§2.1); H2 fills existing rows
CREATE UNIQUE INDEX IF NOT EXISTS album_seq ON album (seq);

-- an album credited to an entity; role = the entity's kind (kept for the (album, role) key and "unassigned" queries)
CREATE TABLE IF NOT EXISTS album_link (
    album_seq INT     NOT NULL,
    role      TINYINT NOT NULL,
    entity_id INT     NOT NULL,
    alias     VARCHAR(256),             -- the token in the album's text when it is not the primary name ("Nikka"); derived, §2.5
    PRIMARY KEY (album_seq, role, entity_id)
);
CREATE INDEX IF NOT EXISTS album_link_entity ON album_link (entity_id, album_seq);   -- entity → albums, counts, merge

-- curated entity-to-entity facts, direction subject → object: model → studio (credit: `alias` = her stage name there,
-- `roles` = the genres she shoots there, ModelCard's third column), model → genre (roles overall), model → tag (her own
-- hashtags), studio → genre, studio → model (public roster), studio → photographer, photographer → studio / genre
CREATE TABLE IF NOT EXISTS entity_link (
    from_id INT NOT NULL,
    to_id   INT NOT NULL,
    alias   VARCHAR(256),               -- model → studio only: the credited name at that studio
    roles   VARCHAR(256),               -- model → studio only: genre names (name-list syntax of §2.5), resolved to genre entities for display
    PRIMARY KEY (from_id, to_id)
);
CREATE INDEX IF NOT EXISTS entity_link_to ON entity_link (to_id);
```

Adding `seq` makes H2 copy the `album` table and rebuild its indexes once (minutes on a 200 MB library, the file
temporarily doubled): the upgrade therefore runs in `JdbcAlbumRepository`'s constructor like `packLegacyEntries`,
guarded by an INFORMATION_SCHEMA check and logged, not from `schema.sql`; if H2 does not fill existing rows on
`ADD COLUMN … AUTO_INCREMENT`, a one-time `UPDATE album SET seq = …` from a sequence precedes the unique index.
`seq` stays **out of the `MERGE` column list** (the identity fills inserts, updates leave it alone); adopting a
tombstone's `seq` is one `UPDATE album SET seq = ? WHERE id = ?` after the `MERGE` in the same transaction.

The model card's *Studio / Alias / Roles* table merges two sources per studio: **derived** rows (her albums grouped by
their studio link: distinct `album_link.alias` values, the genres of those albums, the count) and **curated** rows
(`entity_link` model → studio, with `alias`, for credits that have no albums yet or that the user wants fixed). The
same two-source rule applies to every relationship section (§7.3): derived facts are read-only and marked, curated
ones are editable.

### 2.4 Photo marks (sparse)

```sql
-- only photos the user rated or listed have rows; identified by entry name (stable), never by index (shifts on re-index);
-- the name is stored once, memberships key on the photo's own id
CREATE TABLE IF NOT EXISTS photo (
    id        INT           AUTO_INCREMENT PRIMARY KEY,
    album_seq INT           NOT NULL,
    name      VARCHAR(4096) NOT NULL,   -- the entry-name bound of `album.cover`
    rating    TINYINT       NOT NULL,   -- 0 = unrated (a row exists for list membership alone)
    UNIQUE (album_seq, name)
);
CREATE TABLE IF NOT EXISTS photo_link (   -- favorites lists (kind 6) of a photo: 8-byte rows
    photo_id  INT NOT NULL,
    entity_id INT NOT NULL,
    PRIMARY KEY (photo_id, entity_id)
);
CREATE INDEX IF NOT EXISTS photo_link_entity ON photo_link (entity_id, photo_id);
```

A removed album keeps its `album_link`, `photo` and `photo_link` rows exactly as it keeps its tombstone, and the
tombstone keeps its `seq`: an album that comes back with its id (restored from a backup, moved back) is saved with
the same `seq` and has its links and marks again. Every count and list joins `album`, so orphans are invisible.
**Tombstones become one per `(content_id, id)`** (today one per `content_id`, so deleting two copies of one gallery
overwrote the first) and carry `seq` and `hidden`: `recall(contentId)` returns the newest. **Purge** (new; today
`removed_at` is written and never read): Optimize deletes tombstones older than 365 days together with their
`album_link`, `photo` and `photo_link` rows by `seq`. An operation that renames entries (encrypt, decrypt, flatten)
carries an **old → new name map** into the re-index, which remaps `photo.name` and the pinned cover in the same
transaction (§4.6).

### 2.5 Resolution: text is the truth, links are its index

What the user types and what the extractor finds is **text** (`model`, `studio`, `photographer` strings, the `tags`
list and the new `genres` list, each a name list); the catalog needs ids. One pure function and one transaction, run
inside every album save (`commit`, `patch`, `link`, `unlink`), after the album's stripe lock and under the catalog
lock (§2.7):

1. **Name-list syntax** (one rule, `Names.parse/join`): names separated by `,` or `;`; a name containing a separator
   is quoted `"Smith, John"`; `join` quotes when needed, so `parse(join(x)) == x`. Before splitting, the **whole
   string** is looked up: if it is an alias of the kind, it is one name ("Art & Soul", "Black and White" never split;
   a duo "Ellena & Aurora" becomes one auto entity until the user writes `Ellena, Aurora` or splits it on the card).
2. Each name → `Catalog.norm` → `alias (kind, norm)`. Hit → that entity; miss → **insert-or-get**: the save
   releases the catalog read lock, takes the write lock, re-checks and inserts entity (`auto = true` for models,
   studios, photographers and genres; `false` for tags) + alias, then continues under the read lock (two albums
   indexing "Ellena" at once get one entity, and a lost race never poisons the album's transaction). Two names
   resolving to one entity are one link.
3. The album's `album_link` rows of roles model/studio/photographer/genre/tag are replaced by the result in the save
   transaction (`entity.albums` adjusted by the difference); `alias` = the token as written when it differs from the
   primary name. Tags are already normalized by `AlbumMeta.tag`, so for them name = norm and no alias is ever stored.
   Favorites links never come from text and are left alone.
4. The `search` column = today's text **plus every alias of every linked entity** (the resolution returns that
   alias text and `AlbumService` hands it to `repo.save(album, entries, aliasText)`), so "Nikka" and "Ariell van
   Hoover" both find the album whichever the file name said.

Columns `model`, `studio`, `photographer` keep **exactly today's semantics** (effective text, edited over extracted,
explicit empties): nothing rewrites them behind the user's back. Display names come from the links.

| action | what happens |
|---|---|
| **Display** (tile, viewer header, card lists) | the linked entities' primary names, from `Album.links[{entity, role, name, alias}]` (one query per result page); the inputs in the details panel show the raw text, so an edit never erases an alias credit |
| **Edit text** in the details panel | `edited.<field> = text` → save → rule; a toast names the resolution when it differs from the typed text ("Nikka → Ariell van Hoover") |
| **Assign entity** (menu, picker; `link {albumIds, entityId}`) | server: `edited.<field> = join(parse(effective) ∪ {entity.name})` — computed from the *effective* list, so the extracted credits are kept, then save → rule |
| **Unassign** (`unlink`) | server: drop from the effective list every token that resolves to that id; an empty result is the explicit `""`, which also stops a re-index from re-linking the extracted value |
| **Re-index** | re-extracts text, edits win (`over`), the rule runs again: links follow, never disagree |
| **Rename entity** | entity row; the old primary name **stays as an alias** (else texts would re-create it as a new auto entity); `search` of its albums is recomputed (column-only `UPDATE`, bounded by its album count); `catalogChanged` |
| **Add alias** | alias row (refused when another entity of the kind owns it → "Merge?"); `search` recompute; `catalogChanged`; a later save of any album saying that name now links here |
| **Merge A into B** (same kind only) | one transaction under the catalog write lock: `DELETE` A's `album_link`/`photo_link`/`entity_link` rows that would collide with B's and the `entity_link` rows between A and B themselves (no self-links), then `UPDATE … SET entity_id = B` (both sides of `entity_link`); A's aliases and primary name → B's aliases; profile: B's fields win, A fills B's empties; rating = max; `auto = A.auto && B.auto`; delete A; recount B; `search` recompute; `catalogChanged`. Texts are untouched: they still say A's name, which now resolves to B |
| **"Credited as Y at studio X"** (model card) | two steps, never one transaction: (1) under the catalog write lock `entity_link (M → X).alias = Y` and alias Y added to M, lock released; (2) ordinary `patch` per album of hers at X (stripe lock → catalog read lock, revision bump, `albumChanged` each, bounded set) rewriting the token that resolves to M into Y in the edited layer. The derived and curated rows then agree |
| **Delete entity** | two steps: (1) per-album `unlink` for its albums (stripe lock → catalog read, as *Unassign*); (2) under the catalog write lock delete its aliases, remaining links and the row; `catalogChanged` |
| **Confirm** an `auto` entity | `auto = false`. Trees show auto entities dimmed with a dot; Confirm and Merge into… are one click away, so a scanned library is browsable by model and studio at once and curation is merging, not typing |
| **Filters** | `model:`/`studio:`/`by:`/`#tag` resolve the text through `alias` to an id and filter `album_link` through its primary key (aliases work in search syntax; the `LIKE '% tag %'` scan goes); unresolvable → no match (every non-empty text is linked, by construction). Tree and card scopes send ids (`entities`, all must match) |
| **Facets** | one `SELECT role, entity_id, COUNT(*) … GROUP BY` over the links of the matching albums, names joined from `entity`; replaces the per-row string counting in Java |
| **Backfill** | on start, if `meta.catalog_version` is below 1: resolve every album's text in batches of 1 000 with plain SQL (no `commit`, no events, no revision), `recount()`, set the marker; runs before `scanOnStart` under the gate (§4.2); idempotent (links are replaced per album) |

### 2.6 Album additions

| field | type | meaning |
|---|---|---|
| `seq` | INT column, server-only (`Album.Internal`) | the surrogate key of §2.1; assigned by the identity on insert, set from the tombstone on adoption (§2.3), never written by `MERGE` |
| `hidden` | BOOLEAN column + wire | **safe-delete**: a virtual deletion in the index only, no file touched; excluded from every default grid, folder count, root count, entity count, facet and library stat — `AND NOT hidden` is added in `Where.of` (search, count, facets) and explicitly in `folders`, `rootStats`, `stats`, **never in `UNDER`**, which `stamps`, `byRoot` and `reroot` use: reconciliation must keep seeing hidden albums or every scan would re-create and prune them; shown by the Hidden view (`AlbumQuery.hidden = true`), reversible with Unhide; user state: part of `Tombstone` and `removed.hidden`, kept by `commit`; the copies count (§3.6) also sees hidden albums |
| `bio` | VARCHAR(4096) null | entry name of the text file whose name contains `bio` or `model` (first by name), found by the indexer; the viewer shows a button when set |
| `stamp` | wire only | the album's `contentId` (16 hex), for grouping copies in the UI |
| `genres` | wire + both `AlbumMeta` layers | the GENRE name list, resolved like the others |
| `tags` | unchanged on the wire and in the layers | now also resolved into TAG links (§2.5); the `tags` column stays until the link filter replaces the `LIKE` scan |
| `links` | wire only | `[{ entity, role, name, alias? }]`, per result page in one query |
| `copies` | wire only | albums sharing this album's `contentId`, itself included (1 = unique), per result page in one query (§3.6) |
| `shotDate` | semantics | the indexer writes the computed date (EXIF, else sidecar, else oldest mtime) into `extracted.date`; `withEdits` sets `shotDate = effective date`, so `edited.date` overrides it and `AlbumPatch.date` makes M5 true: `YYYY-MM-DD`, `YYYY-MM` or `YYYY` (stored as the first day, so a selection can be grouped by year), `""` removes the override |
| `Entry.flags` | int, packed | bit 0 `META_KNOWN`, bit 1 `META`: the marker walk saw an APP1/IFD/`eXIf`; legacy values decode with flags 0 (unknown: the viewer enables the button, and a `photoMeta` answer writes the learned flags back into the album's packed entries, one row, so an already-indexed library converges without a re-index); reused probes carry their flags. `EntryCodec` values start with `0x00, version` — unambiguous because the old format starts with the LEB128 count, and a legacy one-byte `0x00` value (an empty list, written by tests only) decodes as empty |

`AlbumPatch` gains `date`, `genres`, `hidden`, and `addTags`/`removeTags` (additive, for bulk "add this hashtag"
without replacing each album's list; `tags` keeps replacing).

One database, not two: the new tables are small next to `album` (§2.1 budget), filters and counts join `album`, and a
second H2 file would cost two datasources, no joins and a second backup/optimize path. `split:27` + COMPRESS already
bound the file. Decision row for PLAN.

### 2.7 Concurrency

- Album saves: stripe lock as today. The resolution runs inside the save transaction under a **catalog
  read-write lock**: resolution takes it for reading and escalates to writing only to create a missing entity
  (§2.5 step 2); merge / rename / alias / confirm / the catalog half of *credited as* and *delete entity* / backfill
  take it for writing and **never take an album stripe lock** — anything that must change album text does so in a
  separate step through `patch`/`unlink` (stripe → catalog read). Lock order: stripe → catalog, never back:
  deadlock-free.
- `search` recompute and `entity.albums` adjustments are column-only `UPDATE`s (never the full-row `MERGE` of `save`,
  which would roll back a concurrent save's `revision`).
- The viewer and the grid reload on `catalogChanged` (names), not only on a changed `revision`.

## 3. Behaviour

### 3.1 Scanning

Unchanged pipeline; `AlbumIndexer` additionally finds the bio file and the text sidecars once (§5 budget), builds the
extraction context and `commit` runs the resolution rule. Cost: one alias lookup per name (PK), one insert for a new
entity. The scan never deletes entities; auto entities that lose their last album stay (count 0; "Delete unused" is a
tree action).

### 3.2 Editing

Details panel: Model / Studio / Photographer / Genres inputs hold the raw text (name lists) with datalist suggestions
from `entities {kind, text}` (prefix search over aliases); under each input the linked entities as chips → click
opens the card (§7.3), × unassigns by id. Rating, tags, description as today; **shot date** gets a date input
(`AlbumPatch.date`, clear = back to the indexed date).

### 3.3 Favorites

Lists are entities of kind 6 (`LIST`). "Add to favorites ▸" lists them plus "New list…". An album is in a list ⇔ an
`album_link (role 6)` row (`link/unlink`); a photo ⇔ a `photo_link` row (`markPhoto`). The Favorites tree mode lists
the lists with their stored album count and their photo count (computed, §2.1); a list node offers "Show albums"
(scope) and "Show photos" (`photos {listId}` → a photo grid across albums; the backend lands in B4, the grid in F4).

### 3.4 Hidden

"Hide" = `patch {hidden: true}` (no file touched). Hidden albums leave every default view and count (§2.6). They are
reached through a fixed **Views** strip at the bottom of the Browse tab, independent of the group-by: **Hidden**
(count, "Unhide all") and **Duplicates** (§3.6); the Refine tab also has a "Show hidden" switch (`hidden = true`:
only hidden). Hidden albums never appear under Favorites: they are virtually deleted, not favored.

### 3.5 Trees (sidebar)

The sidebar's first tab becomes **Browse** with a group-by selector (`Prefs.groupBy`): Folders · Studios · Models ·
Photographers · Genres · Favorites. Folders = today's tree. Each entity mode is the same `EntityTree` component fed
by `entities {kind, sort, offset, limit}` (name, the stored `albums` count, auto; `total` for "N more…"): rows "All", "Unassigned"
(`AlbumQuery.missing = kind`, its count computed per open, §2.1), then the entities; filter box (server-side prefix
search when the list is paged); sort by name or by count. **Entity rows are multi-selectable** with the same
`Selection` class, so Confirm, Delete entity… and Merge into… act on a batch (`confirmEntity`/`deleteEntity` take
`ids[]`; merge takes `from[]`): curating hundreds of auto entities is not one click each. Selecting a row (plain
click) sets `filters.entities = [id]` (or `missing`); the location bar shows
"Models › Ariell van Hoover · 132 albums" and the search chip reads `model: Ariell van Hoover`. A second level
(Studio › its models, Model › her studios…) is one more `entities {kind, under}` query, phase 2 if wanted; the
component is written for depth ≥ 1. Node actions that "operate on its albums" send the node's **query** (§6: `op`
and `bulkUpdate` accept `query` as well as `albumIds`); the server enumerates ids under the same 500 cap and the
preflight shows the count.

### 3.6 Duplicates

Every album carries a content stamp: `contentId` = wyhash of its sorted `(image name, bytes)` list, indexed
(`album_content`), kept across moves. Albums sharing a stamp at different paths are **copy candidates**: names and
sizes agree, bytes are not compared. Nothing new is stored:

- Each result page gets `copies` per album from one query over the page's stamps (`SELECT content_id, COUNT(*) …
  GROUP BY`; hidden copies count: they are still on disk). A tile with `copies > 1` shows a "×N" badge; the details
  panel "N copies (same names and sizes)".
- `copies {albumId}` → `copyList [Album]`: every album with that stamp, as a popover from the badge: path, root,
  size, indexed date, with Open · Show in folder · Hide · Delete… per row, and **Compare**: `compareCopies {a, b}`
  reads both containers (raw) and compares every entry byte for byte → "identical" / "differ in N files". The
  dialog labels unverified rows "same names and sizes"; Delete… in this popover runs Compare first and refuses
  when the files differ.
- The Browse tab's Views strip (§3.4) has a fixed **Duplicates** entry filtering with `AlbumQuery.duplicates = true`
  (`content_id IN (SELECT content_id FROM album GROUP BY content_id HAVING COUNT(*) > 1)`); the grid groups by
  `stamp` (the wire field) with a thin separator, whatever the sort.
- A stronger stamp (hash of the heads the indexer already reads) is a later refinement behind the same `copies`
  mechanism; it would still only narrow candidates, and Compare stays the proof.

### 3.7 Selection

One `Selection` class (`core/selection.ts`, pure, keyed by string ids; one instance for albums in `AlbumStore`, one
for photos in the viewer): `ids`, `anchor`, click = select one, Ctrl+click toggles, Shift+click ranges over the
grid order, `Ctrl+A` / "Select all" selects the loaded items up to `SELECT_CAP` (500; album pages are loaded until
the cap or the end), "Select first N…" asks N ≤ 500, Esc clears. `selectedId` (focus, details panel) stays; a
selection of ≥ 2 shows a count chip in the location bar with "Deselect". Tiles show a check corner when selected. An
album action applies to the selection when the clicked tile is in it, else to the tile.

## 4. File operations (`ops` package)

### 4.1 Principles

- **Explicit, confirmed, manifested, journaled.** Every operation starts from a `preflight` reply shown in a confirm
  dialog (items with do/skip and reasons, bytes, free space, the exact consequence: "3 folders are deleted after the
  archives are verified", "the result is a zip", "leaves the library"); runs on one worker thread (`ops`), one item
  at a time, in a disk turn (`Disks` lane); writes a **manifest** of what it must preserve and a **journal** line
  before each phase; **never deletes a source before the result was verified byte for byte against the manifest**.
  Deletion is plain `Files.delete` — no Recycle Bin: the verification is what makes a transformation safe, and the
  owner's *safe-delete* is the `hidden` flag (§2.6), which touches no file. The two dangerous operations that remove
  files on purpose (Delete, Delete folder) are confirmed in their own dialog listing every item.
- `FileOps` is the only code that writes to the library; index updates go through `AlbumService` (still the only
  index writer): `relocate`, `rebase`, `remove`, `patch(hidden)`, and the re-index of §4.6 through the public facade
  `index/Reindexer`.
- Conflict policies are **Skip** (default) and **Rename**; there is no Replace: it adds recovery states nobody asked
  for. There is no "keep sources" switch either: a transformation always replaces its source (the verification is
  the safety), so an album never silently forks into a result with a new id and a stale source.
- Cancel between items, never inside one (an item completes, or rolls back to the source). Progress `OpProgress {id,
  kind, total, done, current, skipped, failed, message}` ≤ 4/s; `OpResult {done, skipped: [{item, reason}], failed}`
  when it ends.

### 4.2 Admission gate

One `Gate` (`AtomicReference<Activity>` in `index`): `SCAN`, `OP`, `RESTART`, `SETTINGS`. `tryEnter(kind)` is a
compare-and-set; `ScanService.start`, `FileOps.submit`, `Library.switchTo/optimize` (replaces `freeze()`),
`setSettings` with root/path-map changes and `Catalog.backfill` all go through it; the holder's message names the
blocker ("A scan is running"). An operation **snapshots** `Settings` and the `.cry` key at admission (the key may
otherwise vanish 10 s after the last client disconnects). Shutdown: `FileOps.close()` on `ContextClosedEvent`
finishes or rolls back the current item, then stops. The scoped scans an unpack or copy needs (§4.5) run inside the
operation's own gate hold through `Reindexer.scan(paths, cancelled)`, the facade over the package-private
synchronous `ScanService.scan`.

### 4.3 Open handles

Measured on this machine (JDK 27, NTFS, probe 2026-10-18): a file held through `FileChannel.open(READ)` can still be
renamed and deleted (the JDK opens it with `FILE_SHARE_DELETE`), but **any** open handle inside a directory —
including that channel — blocks renaming the directory; `RandomAccessFile`, `FileInputStream` and `ZipFile` (and so
7-Zip's `RandomAccessFile`) block rename and delete of the file itself with "being used by another process".
Therefore `AlbumService.quiesce(scope)` (an album id, or a directory prefix for folder operations) closes the
albums' `Gallery` passes and marks them busy: `cover/thumb/original` of a busy album answer `503 Retry-After: 2` and
open nothing; the operation `release`s them when the item ends. A `FileSystemException` on rename or delete is
retried 5 × 200 ms (another process may hold the file), then the item is skipped "in use" with the source untouched.
Two more probe facts shape the code: `Files.move("Foo", "foo")` is a **silent no-op** (case-only renames go through
a temporary name), and `Files.move(src, dst, ATOMIC_MOVE)` **replaces an existing file silently** (the final rename
of a `.part` checks the destination immediately before; a directory onto an existing directory fails, which is fine).

### 4.4 Raw access and the album boundary

- Every container today comes **wrapped by `Cry`**: `.cry` members read as plaintext under their `.cry` names, and
  without a key they are not images at all. A lossless move/pack/unpack/repack must copy **ciphertext as is**:
  `Containers.openRaw(kind, path)` returns the unwrapped container (an acknowledged exception to "containers always
  come wrapped", used only by `ops` and by `compareCopies`, §3.6), listing with `Exclusions.NONE` so excluded files
  are preserved too. Only *decrypt* and *copy photos* use the wrapped reader.
- One-pass bulk extraction, fail-closed: `AlbumContainer.extract(names, EntrySink)` delivers every named entry's full
  bytes in packing order (default: a loop over `open`; `SevenZipContainer`: one pass like `heads`, so a solid archive
  never costs Θ(N²)); a single unreadable entry fails the item (unlike `heads`, which skips). The sink writes and
  hashes.
- **A directory album is only the files directly inside its directory** (`DirectoryContainer`): sub-folders and
  archives below it are albums of their own. Therefore: *pack* and *encrypt/decrypt* act on the album's own files;
  the directory itself is deleted only when nothing else is left in it (otherwise it stays beside `X.zip` as a
  plain folder, the preflight says "folder keeps N sub-folders"; the zip still takes over the album's identity
  because the re-index asserts it, §4.6). *Move*, *copy* and *delete* of a directory album take the **whole
  directory**, children included, and update the index like the folder operations (`rebase`/scoped scan/remove by
  prefix), which the preflight states ("contains 3 nested albums").
- **Containment**: archive entry names are normalized and refused when absolute, drive-prefixed or containing `..`;
  symlinks are never followed (a linked entry is skipped with a reason); a destination inside a selected source, a
  source inside another selected source, or a destination that changed between preflight and item start (gone, no
  longer a directory, another volume) fails the item.

### 4.5 Operations

| op | items | result | index update |
|---|---|---|---|
| **Move** | albums → target dir | same volume: `Files.move` (atomic; journaled as `installing` with `tmp = src`); other volume: copy to `<dst>.part`, verify (hash of every file while copying = hash of the read-back destination), **delete the source, then rename** `.part` to its final name | last path segment unchanged → `relocate(id, canonicalNewPath, root)` (same signature, nothing re-indexed; the root column follows the target); a renamed segment (`name (2)`) → re-index (§4.6) so the file-name extractor sees it; outside every root → `remove(id)` with tombstone ("leaves the library") |
| **Copy** | albums → target dir | copy as above, source kept | inside a root: scoped scan of the target → new albums with their own ids (a copy is a new album, as today); outside: nothing |
| **Delete** | albums | `Files.delete` of the album's files and, for a directory album, its whole directory (children included, the preflight says so); confirmed in a dialog that lists every item | `remove(id)` (by prefix for a directory) → tombstone, so a copy restored from a backup revives id, rating, edits, links, marks on the next scan |
| **Pack** | directory albums → `zip` \| `7z` \| `auto` | manifest = own files (raw); `<dir>.zip.part` written while hashing; verify = reopen, every manifest entry present with equal size and hash; **delete the own files (the dir too when empty), then rename** `.part` to `<dir>.zip`. Already an archive → skip; `<dir>.zip` exists → Skip or Rename (`<dir> (2).zip`) | re-index with base (§4.6) |
| **Unpack** | archive albums → directory | manifest = all entries (raw); `extract` into `<name>.part/` preserving structure (an archive whose only top-level entry is a directory of its own name is flattened, with a name map); verify by read-back hash; **delete the archive, then rename** the directory. Preflight says "becomes N albums" when the archive holds sub-directories with images, or "would not be an album" (fewer than `min-images` top-level images, nothing nested) and skips it | re-index with base for the top directory (when it is an album), then a scoped scan of it for nested albums |
| **Repack** | archive albums → another format | `extract` → `Packer` in one streaming pass into `<name>.<ext>.part` on the same volume (no temp directory), verify, **delete the source, then rename**; rar/iso → zip/7z only (7-Zip cannot write them; the vendored 7-Zip-JBinding writes zip, 7z, tar, gz, bz2, xz: probe 2026-10-18); the dialog says "the result is a zip" | re-index with base |
| **Encrypt / Decrypt** | albums (dir or archive) | dir: file by file, `<name>.cry.part` (`Cry.encrypt`, streaming, source mtime kept) → verify by decrypting the result with the same key and hashing (= plaintext hash) → delete the source → rename; archive: rewritten entry by entry into `<archive>.part` (rar/iso → zip) with the same verification, replaced in place. Already-encrypted / plain members skipped per file. **Key check**: the dialog asks for the password twice unless an existing `.cry` in the selection decrypts with the session key, and shows the key fingerprint | re-index with base + name map (`a.jpg ↔ a.jpg.cry`) |
| **Copy photos** | one album's selected entries → a configured copy target (§7.6) | `<target>/<plain name>` (a `.cry` entry is exported decrypted under its embedded name, §9 assumptions); existing file with equal size and hash → skipped; same name, different bytes → `name (2).ext` | none |
| **Rename folder** | a folder of the tree → new name | `Files.move`; a case-only rename goes through a temporary name (§4.3) | `rebase(oldDir, newDir, newRoot)`: `UPDATE album SET path = ? \|\| SUBSTRING(path, ?), root = CASE WHEN root = ? THEN ? ELSE root END WHERE path = ? OR path LIKE ? ESCAPE '!'` (reusing `UNDER`/`below`); when a **root** is renamed the database is updated first, then `settings.json`, under a journaled `rebasing` phase (`retainRoots` would otherwise tombstone everything); the renamed folder's own album (if it is one) is re-indexed |
| **Move / Copy folder** | a folder → target dir | the folder as one item (atomic rename, or recursive copy to `.part` with a manifest, verify, delete the source, rename) | `rebase`, or a scoped scan of the copy |
| **Delete folder** | a folder | `Files.delete` of the tree, confirmed with its album count | albums below removed with tombstones (`remove` by prefix) |

Two actions of the same menus are **not** operations (no preflight, no gate, no journal, index only): **Hide /
Unhide** (safe-delete) = `patch {hidden}`, and **Bulk assign** = `bulkUpdate {albumIds | query, patch}` (`patch` per
album in one request, ≤ 500, through the ordinary stripe-locked save).

`auto` format = **zip with STORED entries** for images (already compressed; the CRC is computed while hashing) and
DEFLATED for text: one pass, O(1) random access in the viewer, written by the JDK with no native writer; `7z` through
7-Zip-JBinding's `IOutCreateArchive7z` with method Copy and `solid = false`, so the result is random-access too (a
solid 7z would make every later thumbnail a pass). Zip entry times have 2 s resolution, so a repacked zip re-probes
its heads once (cheap); copies keep mtimes (`COPY_ATTRIBUTES`), since the signature and the oldest-mtime date
fallback depend on them.

### 4.6 Re-index with identity

`Reindexer.reindex(albumId, newPath, newKind, nameMap)` (public facade in `index`, beside `Reindexer.scan`; `ops`
depends on `index`, never the other way round): open the container at `newPath`, `AlbumIndexer.index(…, nameMap)`
with the stored entries as the reuse source (probes reused by `(name, bytes, mtime)`, names translated through the
map), then `AlbumService.commit(prior = Tombstone.of(stored album), indexed, entries, cover, nameMap)`: the
operation **asserts** the identity, so `commit` adopts `prior` whatever the paths say (the scan keeps using the
path-gone heuristic through the existing `commit(base, …)`). This is why a zip can take over the identity of a
directory that survives as a plain folder (§4.4). The order **verify → delete the source → install the result →
re-index** still holds, so a crash can never leave a result without its index entry for long (§4.7). In the same
transaction `commit` remaps the pinned cover and `photo.name` through `nameMap`. Encrypt and decrypt change
`contentId`; everything else keeps it, so a later scan sees every result as unchanged.

### 4.7 Manifest, journal and recovery

Per item: a manifest `{albumId, kind, src, dst, tmp, entries: [{name, bytes, hash}], nameMap}` in
`<library-dir>/ops/<opId>/<n>.json`; the journal `<library-dir>/ops.journal` holds one line per item `{op, item,
phase}` with phases `copying` → `verified` → `disposing` → `installing` → `indexing` → done (and `rebasing` for
folder operations). **In-place encryption of a directory journals per file** (the item's line carries the current
file and its phase, since every file has its own `tmp`); an atomic same-volume move journals `installing` with `tmp
= src`. The line is rewritten before each phase and removed after `indexing` **once H2 has checkpointed**
(`CHECKPOINT SYNC`; the URL's `WRITE_DELAY=1200` would otherwise let a committed re-index vanish behind a journal
that says done).

Recovery at start (`FileOps` constructor, before any scan), per journal line:

| phase | state on disk | action |
|---|---|---|
| `copying` | `tmp` partial | delete `tmp`; source untouched; report |
| `verified` | `tmp` complete and verified | continue at `disposing` |
| `disposing` | source partly or wholly deleted | if `src` exists: re-verify `tmp` against the manifest, then delete it; continue |
| `installing` | `tmp` renamed or not | if `tmp` exists rename it to `dst`; for an atomic move (`tmp = src`): `src` exists → not moved, redo; `dst` exists → moved; continue |
| `indexing` | files final, index unknown | run the re-index (idempotent: `commit` asserting the stored identity); checkpoint; remove the line. **Encrypted results without a key** (the session key is gone after a restart): a result at the album's own path keeps its line removed and is re-indexed by the next keyed scan (same path, `Cry.matches` fails → re-index with identity); a result at a new path keeps its line until a key is set, then re-indexes |
| `rebasing` | database or settings half-done | re-run the `rebase` (idempotent `WHERE` on the old prefix), then the settings write |

So a crash leaves either the source, or the verified result, or both — never neither, never a half-written file
under its final name, never a result without its index entry.

### 4.8 Conflicts and space

Conflict policy per operation from the dialog: **Skip** (default) or **Rename** (`name (2)`). Space: the manifest's
byte total (all files, not `album.bytes`, which counts images only) is the peak extra allocation per file system
(same-volume moves and renames: 0; in-place encryption of a directory: the largest single file; pack/repack and
the rewrite of an archive: the whole output);
checked against `FileStore.getUsableSpace(target)` with a 64 MiB margin in the preflight and again before each item
("No space" skips the item, the operation continues). A `Files.move(…, ATOMIC_MOVE)` that fails with
`AtomicMoveNotSupportedException` falls back to the copy path of the same item.

## 5. Plugins: extraction and cover contracts

The owner's "smart name parser" and "smart cover chooser" come later as separate code. The contract is fixed now:

```java
/** Everything an extractor or cover strategy may look at; built once per index by AlbumIndexer, shared by the chains. */
public record Extraction(String name,            // directory or archive base name
                         String path,            // canonical absolute path
                         Kind kind,
                         List<Entry> images,     // probed entries (name, bytes, width, height, shotAt, flags)
                         List<AlbumContainer.Item> items,  // every file incl. sidecars
                         String longestDir,      // longest directory name inside the archive, or null
                         String sampleImage,     // a typical image name (median by name order)
                         Map<String, String> texts,        // *.txt: ≤ 8 files, ≤ 64 KiB each, ≤ 256 KiB total, decoded (BOM, UTF-8, else CP1251)
                         AlbumContainer container) {       // may be null outside indexing (patch/commit choose covers from entries only)
    public static Extraction of(List<Entry> images) { … }   // entries only, no container
}

public interface MetadataExtractor { AlbumMeta extract(Extraction ctx) throws IOException; default int order() { return 100; } }
public interface CoverStrategy      { Optional<Entry> pick(Extraction ctx);           default int order() { return 100; } }
```

- `Metadata` and `CoverSelector` collect Spring beans **and** `ServiceLoader` providers on the application class
  path (`META-INF/services`), sorted by `order()`; a failing provider is skipped with one warning (as `Metadata` does
  today). A plugin is a jar on the class path that depends only on the SPI types, not on Spring. Drop-in loading of
  jars from a `plugins/` directory is deferred: the owner's plugins are subprojects of this build (below).
- `CoverSelector.candidates(entries, pinned)` keeps its signature for `commit`/`patch` by wrapping entries in
  `Extraction.of(entries)`; strategies that need the container check for null.
- `AlbumMeta` gains `genres`. `SidecarExtractor`, `FileNameExtractor`, `ExifExtractor`, `NAME_HINT`, `SMALLEST` are
  rewritten against the context (same behaviour, nothing read twice) and are the default providers.
- **Last step, mechanical** (after every track merged, no conflicts possible): the SPI types move into a Gradle
  subproject `:spi` (package `thumbnailer.spi`: `AlbumContainer` + `Item` + `EntrySink`, `Entry`, `AlbumMeta`,
  `Kind`, `Extraction`, `MetadataExtractor`, `CoverStrategy`). Known seams: `AlbumMeta.effective()` becomes public,
  `AlbumContainer`'s hashing defaults (`signature`, `contentId`, `archiveSignature`) move to a backend helper
  `Stamps`, and `Containers.normalize` is not referenced from the SPI. A sample `:plugins:names` subproject holds the
  current file-name parser as the first external plugin and is bundled into the jar.

## 6. Wire contract additions (ARCHITECTURE §3 when implemented)

Client → server:

| type | payload | reply |
|---|---|---|
| `entities` | `kind, text?: prefix over aliases, under?: id, sort: NAME\|COUNT, offset, limit ≤ 500` | `entityList {items: [{id, kind, name, rating, auto, albums, photos?}], total}` (counts exclude hidden) |
| `entity` | `id` | `entityCard {entity, aliases[], links: [{to: Entity, alias?}], derived: {albums, models[], studios[{entity, aliases[], genres[], albums}], photographers[], genres[], tags[]}}` (derived per kind, read-only) |
| `saveEntity` | `entity {id?, kind, name, rating, profile, aliases[], links: [{to, alias?, roles?}]}` | `entityCard` (+ `catalogChanged` when the name or the aliases changed); `error` when a name/alias is owned by another entity of the kind ("Merge instead?") |
| `deleteEntity` | `ids[]` | `ok` + `albumChanged` per unlinked album + `catalogChanged` |
| `mergeEntities` | `from[], into` (same kind) | `entityCard` (into) + `catalogChanged` |
| `confirmEntity` | `ids[]` | `ok` + `catalogChanged` |
| `creditAs` | `modelId, studioId, alias, roles?` | `entityCard` + `albumChanged` per rewritten album (§2.5 "Credited as") |
| `bulkUpdate` | `albumIds[] \| query: AlbumQuery (≤ 500 either way), patch: AlbumPatch` | `ok {count}` (then `albumChanged` each) |
| `link` / `unlink` | `albumIds[] \| query, entityId` | `ok {count}` (edited text ± the entity's name, §2.5; favorites lists likewise, but they are not text: a plain `album_link` row) |
| `markPhoto` | `albumId, name, rating?, lists?: ids[]` | `photoMarks` |
| `photos` | `listId, offset, limit` | `photoList {items: [{albumId, name, index, rating, album: {id, name, revision}}], total}` (backend in B4; the grid UI in F4) |
| `photoMeta` | `albumId, index` | `photoMeta [{dir, tag, value}]` (metadata-extractor over the head, else the file) |
| `albumText` | `albumId, name` | `text {name, text}` |
| `copies` | `albumId` | `copyList {albums: Album[]}` (§3.6) |
| `compareCopies` | `a, b` | `comparison {identical, differing: string[]}` (raw bytes, every entry) |
| `preflight` | an `op` payload | `preflight {items: [{path, action: do\|skip, reason?, note?}], bytes, free, warnings[], consequences[]}` |
| `op` | `{kind: MOVE\|COPY\|DELETE\|PACK\|UNPACK\|REPACK\|CRYPT\|COPY_PHOTOS\|FOLDER_RENAME\|FOLDER_MOVE\|FOLDER_COPY\|FOLDER_DELETE, albumIds? \| query?, paths?, target?, format?, encrypt?, names?, conflict: SKIP\|RENAME, password?: string}` | `ok {opId}`; `error` from the gate ("A scan is running", "An operation is running") |
| `cancelOp` | – | `ok` |

Server → client: `entityList`, `entityCard`, `catalogChanged {kinds[]}` (broadcast: grid, viewer, trees, facets and
open cards reload), `opProgress`, `opResult`, `photoMarks {albumId, marks: {[name]: {rating, lists[]}}}`, `photoList`,
`photoMeta`, `text`, `copyList`, `comparison`, `preflight`.

Changed: `Album` + `hidden, bio, stamp, genres, links, copies`; `albumDetails` + `marks`; `AlbumQuery` + `entities:
int[]` (all), `missing: kind`, `hidden: boolean`, `duplicates: boolean`, `genres: string[]`; `model/studio/
photographer` filters resolve through aliases; `Facets` + `genres`, with model/studio/photographer counts from
`album_link` (primary names, tags included); `AlbumPatch` + `date, genres, hidden, addTags, removeTags`; `Settings` +
`copyTargets: string[] (≤ 10)`, `ops: {packFormat: AUTO\|ZIP\|SEVEN_ZIP}`; `Entry` + `flags`; HTTP image routes
answer `503 Retry-After: 2` for an album an operation holds (§4.3).

## 7. UI

### 7.1 Infrastructure (one of each, data-driven)

- `ContextMenu` (service + one component at the app root): `open(x, y, items: MenuItem[])`, `MenuItem = {label,
  icon?, keys?, disabled?, danger?, checked?, children?: MenuItem[] | () => Promise<MenuItem[]>, run?}`; submenus,
  keyboard, closes on outside click/Esc/scroll. Menus are built by small pure functions per subject
  (`albumMenu(selection, catalog)`, `folderMenu(path)`, `entityMenu(e)`, `photoMenu(…)`) so they rebuild from
  current state on every open ("dynamic").
- `Confirm` (promise dialog like `FolderPicker.pick()`): title, affected items (≤ 10 shown + "and N more"; **Delete
  and Delete folder show the whole list, scrollable**), the `preflight` summary (bytes, free space, skipped items
  with reasons, consequences), option controls (conflict policy, format, password twice for encryption),
  primary/danger button. Every operation goes through it.
- `EntityPicker` (promise dialog): search box over `entities {kind, text}`, recent, "Create new …" when no match;
  used for assign-to, merge-into, add-to-favorites, credited-as.
- `Selection` (§3.7) and the keyboard additions (§7.7).

### 7.2 Sidebar

Tab strip: **Browse** (group-by selector in the header: icon button with a menu) · **Refine** · close. Folder tree
as today + right-click: Rescan · Open in Explorer · Copy path · ─ · Rename… · Move to… · Copy to… · Delete… · ─ ·
Albums below ▸ Pack ▸ · Unpack · Encrypt · Decrypt · Hide · Assign ▸ · Favorites ▸ (the folder's query). Entity
tree: row = name, count, auto dot, multi-selectable (§3.5); right-click: Open card · Rename… · Merge into… · Confirm
· Delete entity… (all three act on the selected rows) · ─ · Assign to selected albums · ─ · Its albums ▸ (the same
query sub-menu: Pack, Unpack, Encrypt, Decrypt, Hide, Assign ▸, Favorites ▸); the tree header has "Delete unused…"
(auto entities with 0 albums). Below the tree the fixed **Views** strip: Hidden · Duplicates (§3.4, §3.6). The
location bar shows the entity crumb; the search chip removes the scope.

### 7.3 Entity cards

The right panel (`DetailsPanel`) gets a second mode: **album** (today) or **entity**, with a back arrow; clicking a
model/studio/photographer/genre chip anywhere (details, tile hover, viewer header, tree) opens the card there; the
grid behind is unaffected unless the user also selects the entity in the tree. The panel widens to 400 px in entity
mode (≥ 1400 px inline, otherwise floating as today).

One `EntityCard` component: shared field controls (text, number, date, rating, chips) driven by a **field schema**
per kind, plus small dedicated relationship sections where a table is clearer than a chip list. Derived facts are
read-only and marked "from albums"; curated ones (`entity_link`, profile) are editable:

| kind | header | profile fields (JSON) | relationship sections |
|---|---|---|---|
| Model | name, aliases (chips), rating, cover of the newest album | born (year or date; *Age* computed when a year parses), yearsActive, birthplace, nationality, ethnicity, hair, eyes, height (cm, with ft/in), weight (kg, with lb), bodyType, measurements, tattoos, piercings, notes | **Studios table** (studio · alias · roles · albums; derived rows + curated `entity_link` rows with `alias`/`roles`, "Add studio…", "Credited as…"), genres (curated chips + derived), **hashtags** (her own, curated `entity_link` → tag chips, editable) and tags from albums (derived), photographers (derived + curated), albums count → "Show albums" |
| Studio / Agency | name, aliases | yearsActive, web, notes | genres (curated + derived), **models** (derived from albums; curated public roster marked "roster"), photographers (same two sources), albums count |
| Photographer | name, aliases | yearsActive, web, notes | studios, models, genres (derived + curated), albums |
| Genre | name, aliases | description | models, studios, albums (counts) |
| Favorites list | name | description | albums, photos (counts); "Show albums", "Show photos" |

Every field saves on change (`saveEntity`), like the album panel. Name collision → toast with "Merge into X?".

### 7.4 Album actions (grid context menu)

Open · Details · Show in folder · Copy path · ─ · **Assign ▸** Model… · Studio… · Photographer… · Genre… · Add tag…
· Remove tag ▸ · Rating ▸ 1–10 · Shot date… · ─ · **Favorites ▸** [lists with check marks] · New list… · ─ · **Pack
▸** auto · zip · 7z · Unpack · Repack ▸ · Encrypt (.cry) · Decrypt · ─ · Move to… · Copy to… · Hide · Delete… · ─ ·
Select all · Select first N… · Deselect. Items show the preflight count that will be skipped ("Pack (3 of 7 already
packed)"). The status bar shows the running operation like the scan (counts, Cancel); the result opens as a toast,
or as a dialog when something was skipped or failed.

### 7.5 Viewer

Header: back · title/meta as today (names from `links`) · **bio button** (when `album.bio`: side drawer with the
text) · sort menu (name · size · date · dimensions · **rating** · **random**, each with a direction toggle; random
has a reshuffle action) · filter (min longer side px with presets 0/800/1200/1800; min bytes) · scale menu · full
screen · rating · show in folder · details. Grid: tiles get a rating badge and a heart when marked; hover actions:
cover, view, favorites, rate (1–10 strip). Right-click on a photo: View · Use as cover · Rate ▸ · Favorites ▸ ·
**Copy to ▸** targets 1–0 (+ "Edit targets…") · Show metadata (disabled only when `META_KNOWN && !META`; unknown =
enabled and the dialog may say "No metadata") · Show in folder · Copy path. Multi-select (the same `Selection`, keyed
by entry name) so Copy to and Favorites apply to several photos.

Lightbox: scale modes **fit** (fill the viewport, upscaling), **fit large** (downscale only), **1:1**, **fit width**,
**fit height**; panning by scroll/drag when larger than the viewport; click toggles fit ↔ 1:1. Full screen (`F11` /
button, `requestFullscreen` on the lightbox) with slideshow (`Space` start/pause, interval `Prefs.slideshowSeconds`,
order = current sort incl. random, wraps, a thin progress line); the caption shows rating, heart, copy-to keys,
metadata button. `Prefs`: `viewerScale` (default), `viewerSort`, `viewerMinSide`, `slideshowSeconds`. The viewer
reloads on `catalogChanged` as well as on a newer `revision`.

Metadata dialog: `photoMeta` → table (directory · tag · value) with a filter box; "Copy all".

### 7.6 Settings

- **Operations page** (new): copy targets (ordered list of ≤ 10 folders via the picker, keys 1–0), default pack
  format, the `.cry` password card (moved here from Folders: it is now also the encryption key) with the fingerprint
  shown.
- Appearance page: viewer defaults (scale, slideshow seconds, min side), group-by default.
- Keyboard page and README: the new keys.

### 7.7 Keyboard

Library: `Ctrl+A` select all · `Ctrl/Shift+click` · `Esc` clear selection · `Delete` delete… · `H` hide · `Shift+F10`
= menu for the focused tile. Viewer: `F11` full screen · `Space` slideshow · `R` reshuffle · `S` scale mode cycle ·
`1`–`9`,`0` copy to target · `Ctrl+1…0` rating · `F` favorites ▸ · `M` metadata · `B` bio.

## 8. Work breakdown and parallelization

Owners by the owner's routing (goals.txt): concept/UI = Fable xhigh (`t6`), algorithms/backend = Opus xhigh (`t4`),
mechanical = `t2`/`t3`; review = Codex: hard tracks `gpt-6-astra --effort high`, simpler `gpt-6.1-sol --effort xhigh`.
Every track is its own session in its own worktree; the main thread owns the backbone, merges and integration.

### Phase 0 — backbone (sequential, one session, blocks everything)

Every shared file is created or changed **here**, so the tracks touch only files they own. Compiling and green,
merged to `main` before any track branches:

- `schema.sql`: tables of §2 (`entity`, `alias`, `album_link`, `entity_link`, `photo`, `photo_link`, `meta`);
  `album.seq` (identity, unique; verify that H2 fills existing rows, else the guarded one-time `UPDATE`),
  `album.hidden/bio`; `removed` → PK `(content_id, id)` + `seq` + `hidden` (guarded by an INFORMATION_SCHEMA check
  like `packLegacyEntries`).
- Records: `AlbumMeta + genres`, `AlbumPatch + date/genres/hidden/addTags/removeTags`, `AlbumQuery + entities/
  missing/hidden/duplicates/genres`, `Album + hidden/bio/stamp/genres/links/copies` (+ `Internal.seq`) and
  `withEdits` applying `date`, `Entry + flags`, `EntryCodec` `0x00, version` prefix, `Tombstone + seq/hidden`,
  `EntityKind` codes.
- `AlbumRepository` + JDBC: `save(album, entries, aliasText)`, `relocate(id, path, root)`, `rebase(oldDir, newDir,
  newRoot)`, `removeBelow(dir)`, `setHidden`, `setSeq`, `copies(stamps)`, `links(seqs)`, `recall` newest, purge,
  hidden excluded from `folders`/`rootStats`/`stats` (not from `UNDER`); `Where` with `hidden`/`duplicates` (entity
  filters are B1); the `seq` upgrade in the constructor. Tests for each.
- `catalog/`: `Entity`, `EntityKind` (codes), `Names` (parse/join, spec-tested), `Catalog` (read-write lock + CRUD
  + counter rules + stubs for resolution/merge/refresh), `CatalogRepository` JDBC CRUD.
- `index/Gate` (§4.2) wired into `ScanService`, `Library`, `AlbumSocket.setSettings`; `index/Reindexer` facade
  (`reindex` stub, `scan` passthrough); `AlbumIndexer.index(…, nameMap)`; `AlbumService.quiesce/release`,
  `commit(prior, …, nameMap)` overload (asserted identity) beside the scan's `commit(base, …)`.
- `source`: `Containers.openRaw`, `AlbumContainer.extract(names, EntrySink)` (default impl), `Cry.encrypt`/
  `plainName` signatures (B3 fills them).
- `ops/`: `Op` sealed record, `OpProgress`, `OpResult`, `Preflight`, `Manifest`, `Journal` records, `Packer`/
  `Unpacker` interfaces, `FileOps` with `submit/cancel/running` and a worker that reports "not implemented".
- `web/ws`: all `Request`/`Event` records of §6; `AlbumSocket.handle` delegates to `ws/CatalogApi`, `ws/OpsApi`,
  `ws/PhotosApi` (stubs); `ImageController` 503 for busy albums. `AlbumSocket` is not touched afterwards.
- Frontend: `core/model.ts`, `Gateway` methods, `WsGateway`, `MockGateway` split into `mock-gateway.ts` (albums),
  `mock-catalog.ts`, `mock-ops.ts`, `mock-photos.ts` (seeded entities from its STUDIOS/MODELS/PHOTOGRAPHERS,
  simple working emulation of every message); `Prefs` keys; `core/selection.ts` (complete, spec-tested);
  `core/catalog-store.ts`, `core/ops-store.ts` (state + event routing from `store.ts`, which is then not touched);
  `ui/shared/context-menu.ts`, `confirm.ts`, `entity-picker.ts` shells with typed APIs mounted in `app.html`;
  `album-card.ts` badges for `selected`/`copies`/`hidden` and a `copies` output; `settings-dialog.ts` with the
  Operations page shell; `details-panel.ts` mode switch shell.
- Docs: ARCHITECTURE §1/§3 additions marked "(catalog, in progress)", map lines, INTENT/CLAUDE sentences of §0.

Owner: this session (Fable) with `t3` for the mechanical JDBC/mock parts. Check: `:backend:test`, `ng build`, `ng test`.

### Phase 1 — parallel tracks (worktrees)

| track | scope | owner | files owned (only these) | depends on |
|---|---|---|---|---|
| **B1 catalog** | resolution rule (§2.5) in `Catalog` + its call in `commit`/`patch`, backfill, merge/rename/alias/credit/refresh, entity filters and alias-resolved `model:` in `Where`, facets from links, `entities` paging, `entityCard` derived queries, `link/unlink/bulkUpdate` incl. `query` enumeration, `copies`/`compareCopies`, `CatalogApi`, `CatalogTest` (races: two albums one name) | t4 | `catalog/*`, `web/ws/CatalogApi`, `JdbcAlbumRepository.Where` (entity clauses only) | phase 0 |
| **B2 ops core** | worker, gate use, manifest + journal + recovery, lanes, quiesce, containment, move/copy/delete/folder ops, preflight, space, conflicts, `Reindexer` implementation, `OpsApi`, `FileOpsTest` (phase-injection hooks that throw at each journal transition, then recovery; forced copy path for "cross volume"; locked source; existing destination; nested albums; encrypt + flatten with a pinned cover and marked photos) | t4 | `ops/*` except `ops/pack`, `index/Reindexer`, `web/ws/OpsApi` | phase 0; B3's `Packer`/`Unpacker`/`Cry.encrypt` behind the phase-0 interfaces |
| **B3 packers + cry** | `ZipPacker` (STORED/DEFLATED), `SevenZipPacker` (JBinding, Copy, non-solid), `Unpacker` over `extract`, `SevenZipContainer.extract` one pass, flatten rule + name map, archive rewrite with `.cry` entries, streaming `Cry.encrypt`/`plainName`, hashing sink, `copyPhotos` naming; pure functions + `PackersTest`, `CryTest` additions | t3 | `ops/pack/*`, `source/Cry` (encrypt only), `source/SevenZipContainer` (`extract` only) | phase 0 |
| **B4 extraction + photos** | `Extraction` context, `Metadata`/`CoverSelector` over beans + `ServiceLoader`, extractors and strategies rewritten, `bio`, text decoding + budget, `Entry.flags` from `Header`, `photoMeta`, `albumText`, `markPhoto`, `photos`, `PhotosApi`, tests | t3 | `metadata/*`, `cover/*`, `index/AlbumIndexer` (context build), `image/Header` (flags), `web/ws/PhotosApi` | phase 0 |
| **F1 UI infra** | `ContextMenu`, `Confirm`, `EntityPicker` implementations; `Selection` integration in grid and cards (check corner, Ctrl/Shift, Ctrl+A, first N), selection chip in the location bar, keyboard | t6 | `ui/shared/context-menu.ts`, `confirm.ts`, `entity-picker.ts`, `ui/tile-grid.ts`, `ui/location-bar.ts`, `app.ts` | phase 0 |
| **F2 viewer** | §7.5 against the mock | t6 | `ui/album-viewer.ts` (may split into `ui/viewer/*`), `ui/shared/rating.ts`, `ui/settings/appearance-page.ts` | phase 0 |
| **F3 trees + cards** | Browse tab + group-by, `EntityTree` (incl. Hidden/Duplicates nodes), entity crumb, `DetailsPanel` entity mode + `EntityCard`, chips → card, details inputs as name lists + date input, copies popover, `CatalogStore` logic, Refine additions (genre, hidden) | t6 | `ui/sidebar.ts`, `ui/entity-tree.ts`, `ui/entity-card.ts`, `ui/details-panel.ts`, `core/catalog-store.ts`, `ui/filters-panel.ts` | phase 0; the entity crumb in `location-bar.ts` is a 10-line change F3 hands to F1 as a request (F1 owns the file) |

Shared-file matrix (every file a track may touch that another track also needs):

| file | owner | others |
|---|---|---|
| `AlbumService` | phase 0 (hooks: `quiesce`, `commit(nameMap)`, resolution call site) | B1 fills the resolution call body in `Catalog`, not here; B2 calls, does not edit |
| `JdbcAlbumRepository` | phase 0 (new methods, hidden) | B1 edits `Where` only |
| `AlbumIndexer` | B4 | B2 calls through `Reindexer` |
| `SevenZipContainer` | B3 (`extract`) | — |
| `location-bar.ts` | F1 | F3 requests the crumb change |
| `album-card.ts`, `app.html`, `store.ts`, `settings-dialog.ts` | phase 0 | nobody in phase 1 |
| `model.ts`, `gateway.ts`, `ws-gateway.ts`, `mock-*.ts`, `Request/Event`, `AlbumSocket` | phase 0 | nobody in phase 1 (a missing field is a phase-0 bug fixed on `main` and rebased) |

### Phase 2 — wiring (after F1, F2, F3, B1, B2 merged)

| track | scope | owner |
|---|---|---|
| **F4 actions** | album/folder/entity menus built from §7.2/§7.4, preflight → `Confirm`, `OpsStore` (status bar, result dialog), assign/favorites/credited-as through `EntityPicker`, Operations settings page, copy-targets editor, photo list view for a favorites list | t6 |
| **B5 integration** | live smoke on `backend-demo` (`?live`): scan → auto entities → merge → rename → tag merge → pack → unpack → encrypt (demo key) → move → hide → delete → restore a copy from a backup folder → rescan (tombstone revival); fix what the smoke finds; `ScanTest` additions | t4 |

### Phase 3 — hardening and docs

- Codex review per backend track (B1, B2 `gpt-6-astra high`; B3, B4 `gpt-6.1-sol xhigh`), one UI review of F1–F4
  (`gpt-6.1-sol xhigh`); fix findings in the track's session.
- `:spi` module split + `:plugins:names` sample (§5), `t2`.
- Docs: INTENT and CLAUDE sentences (§0), SPEC (new sections **K — Catalog**, **O — Operations**, viewer rows under
  U), ARCHITECTURE §1–§4, maps, README (keys, settings, operations), PLAN state + decisions, this file.

### Order and critical path

```
phase 0 (backbone) ──┬── B1 catalog ──────┐
                     ├── B3 packers ─┬─ B2 ops core ─┼── F4 actions ── B5 integration ── phase 3
                     ├── B4 extraction┘ (interfaces fixed in phase 0; B2 starts at once, links B3 when it lands)
                     ├── F1 UI infra ──────┤
                     ├── F2 viewer ────────┤
                     └── F3 trees + cards ─┘
```

Seven sessions in phase 1, two in phase 2. Each track: tests for its contract (sharp, few), `ng build`/`ng test` or
`:backend:test` green, its own doc lines, a short report naming what is unverified. Merge order: B3 → B1 → B4 → B2
→ F1 → F2 → F3 → F4 → B5.

## 9. Decisions, assumptions, review outcome, open questions

Decisions (why):

| decision | reason |
|---|---|
| One `entity` table for six kinds + `album_link` with `role` | one CRUD, one tree component, one picker; cards differ in a field schema and small relationship sections |
| Text is the truth; links are derived on every save; display names from links, inputs show text | one source of truth (what the user typed or the extractor found), aliases resolve automatically, re-index can never disagree with the catalog, an edit never erases an alias credit |
| Auto entities with an `auto` flag | a scanned library is browsable by model/studio at once; curation = merge/confirm, not typing |
| Normalized name unique per kind | unambiguous resolution; a shared stage name costs one rename/alias |
| Entity ids `INT`, kinds and roles `TINYINT` codes (`EntityKind`), ratings `TINYINT`, profile as JSON (rating shown for models only; the column costs one byte for the rest) | economical where rows are many, flexible where fields are rarely queried |
| One H2 database | joins for filters and counts; no second datasource, backup or optimize path |
| Catalog changes announce `catalogChanged`, never bump `revision` | `revision` keys image caches and per-album state; a rename changes neither; one broadcast instead of N `albumChanged` |
| Safe-delete = the `hidden` flag; Delete = plain confirmed deletion; transformation sources are deleted only after byte-for-byte verification; no Recycle Bin, no "keep sources" | the owner's definition: safe-delete is a virtual deletion in the index and nothing more; verification, not a bin, is what makes pack/encrypt safe; the tombstone revives state when a backup copy comes back |
| Albums get an `INT` surrogate `seq`; link and mark tables key on it, the UUID stays external | 9-byte composite keys instead of 41; indexes shrink alike; the tombstone carries `seq` so revived albums keep their rows |
| Tags are entities (kind 5) linked like models | one indexed filter and one facet query for every categorical field instead of `LIKE '% tag %'` scans and Java counting; rename/merge of tags for free; a model's hashtags are ordinary entity links |
| `entity.albums` stored, maintained in the link transactions | trees open with one indexed read; `recount()` guards against drift |
| Manifest + journal with an `indexing` phase and a checkpoint | recovery restores the index, not only the files |
| `openRaw` for preservation, wrapped reader for decrypt/export | a lossless rewrite must copy ciphertext; the `Cry` wrap would write plaintext under `.cry` names |
| `auto` pack = zip STORED, 7z non-solid Copy | random access in the viewer, no recompression of JPEGs, JDK writer for the default path |
| Entity card in the right panel, not a route | compact; the grid stays the album surface; the tree already scopes it; no second result store |
| One admission gate (scan, operation, restart, root settings) | two `running()` checks are not mutual exclusion; the gate is also the operation's settings/key snapshot point |
| Favorites lists are entities of kind 6 (`LIST`), shared by albums and photos | no new table or picker; "add to list" is one mechanism |
| Plugins as build subprojects on the class path (ServiceLoader), drop-in jars deferred | the owner's plugins live in this repo; a class loader per directory is cost without a user |

Assumptions taken (say so if wrong):

- Album text fields are name lists (`A, B`); duos found in a file name become one auto entity until split.
- Viewer defaults (scale, slideshow interval, sort, min size, group-by) are browser preferences (`Prefs`, set in
  Settings → Appearance); copy targets and the pack format are server settings (`settings.json`), since the server
  performs them.
- Encrypting or repacking a RAR/ISO album writes a zip (7-Zip cannot write them); the dialog says so.
- Random sort exists in the viewer only (client side); the main grid keeps its stable sorts.
- `born` is free text (year or ISO date); *Age* is computed when a year parses.
- Hidden albums are excluded from every count except the copies count; the Hidden view lives under Favorites.
- "Copy photos" of a `.cry` entry exports the decoded image under its embedded original name (the purpose is to use
  the photo elsewhere); copying ciphertext is not offered.
- Unpacking an archive with sub-directories preserves its structure and yields as many albums as the discovery rule
  finds; the preflight names the count.
- The second tree level is phase 2 if wanted; the component allows it.

Review outcome (v1 → v2): Codex (gpt-6-astra xhigh) and Opus found the same five costly holes, all fixed above:
lossless rewrites through the `Cry`-wrapped reader (§4.4 `openRaw`), size-only verification and a journal that
forgot the index (§4.1, §4.7 manifests + `indexing` + checkpoint), directory albums that own only their direct files
(§4.4), two sources of truth in the text ↔ link model (§2.5: text is the truth, display from links, id-based
assign/unassign, aliases kept on rename, merge with collision deletes), and `running()` checks instead of a gate
(§4.2). Also folded in: `removed` PK per `(content_id, id)`; codec `0x00` marker and two metadata bits; `shotDate`
through `extracted.date`; additive bulk tags; model's own tags; curated relationships; viewer rating sort and
directions; `entities` paging and node-query actions; `photos` for list photos; containment; space from manifests;
conflict policy consistency (Skip/Rename everywhere, no Replace); open-handle quiescing; `Reindexer` facade
(`AlbumIndexer` is package-private and depends on `AlbumService`); case-only renames; root rename ordering; the
explicit shared-file matrix. Probes run on this machine the same day settled the rest: Windows share locks per JDK
reader and the directory-rename rule (§4.3), `Files.move` case-only renames being a no-op and `ATOMIC_MOVE`
replacing files silently (§4.3), the vendored 7-Zip-JBinding writing zip/7z/tar/gz/bz2/xz (§4.5), and the Recycle
Bin being unusable under Spring's headless default — moot after the owner's direction (v2.1): safe-delete is the
`hidden` flag, Delete deletes, no bin. v2.1 also tightened the data model on the owner's request (§2.1: surrogate
`seq`, `TINYINT` codes, tags as entities, stored counts, space budget). A last pass of three independent critics
(contradictions, model ↔ flows ↔ ops, requirement coverage) over v2.1 found and this version fixes: the op rows
installing results before deleting sources (now verify → delete → install everywhere, §4.5), identity adoption
unreachable when a packed directory survives (now asserted by the operation, §4.6), credit/delete flows inverting
the lock order (now two steps, §2.5/§2.7), `NOT hidden` inside the clause reconciliation uses (§2.6), the
`entity.albums` delta rule and its suspension during scans (§2.1), `KEY`/`VALUE` as column names (reserved in H2),
per-file journaling of in-place encryption and key-less recovery (§4.7), `seq` kept out of `MERGE`, the one-time
cost of adding `seq`, `photo` keyed by its own id, per-studio roles on curated credits, batch actions on entity rows,
year-only dates, the Views strip for Hidden/Duplicates, `saveEntity`/`creditAs` broadcasts, and the stale v2
wording (`CHAR(1)`, "kind L", "five kinds", tags in profile JSON).

Open questions for the owner:

1. Auto-created entities in the trees by default (dimmed, as planned) or only after confirmation?
2. Encrypted file naming for *new* files: `photo.jpg.cry` (reversible, collision-free, planned) or xanderView's
   own convention if it is `photo.cry`? The golden tests contain both shapes.
3. Per-photo ratings: informational in the viewer only (planned), or should they feed an album-level "best photo"
   anywhere?
4. Copy targets: 10 fixed slots with keys 1–0 (planned) or an unbounded list?
5. Duplicates: is "same names and sizes, Compare to prove" the right bar, or should the indexer compute a stronger
   stamp from the heads it already reads (cost: one hash per probe)?

## 10. Traceability: goals.txt → plan → track (what is visible when)

Phase 0 (landed 2026-10-18) is the backbone only: schema, records, wire contract, mock, UI shells. **No requirement of
goals.txt is user-visible yet by design**; every one lands with the track in the last column.

| goals.txt | plan | track (visible after) |
|---|---|---|
| five linked kinds (gallery, model, studio, photographer, genre), auto + manual linking, batch processing | §2, §2.5, §3.5 | B1 (auto links from text), F3 (trees, batch confirm/merge), F4 (assign menus) |
| model card = ModelCard.md (name, aliases, born/age, years, birthplace, nationality, ethnicity, hair, eyes, height, weight, body, measurements, tattoos, piercings, roles, hashtags, rating, studios table with alias + role) | §7.3, §2.3 (`entity_link.alias/roles`) | F3 |
| studio card = StudioCard.md (name, aliases, years, web, genres, indexed galleries, indexed models, public roster, photographers) | §7.3 | F3 (derived counts: B1) |
| photographer and genre cards | §7.3 | F3 |
| main window: select one / many / all ≤ 500 / first N | §3.7 | F1 |
| right-click on galleries: assign model, studio, photographer, genre, hashtag, date, rating | §7.4, `bulkUpdate` (works now) | F4 |
| pack / unpack / repack, zip \| 7z \| auto, skip already packed | §4.5 | B2 + B3, menu F4 |
| move (reliable: crash, same name, no space), delete, hide (safe-delete = index only) | §4.5, §4.7, §4.8; hide works now | B2, menu F4 |
| encrypt / decrypt `.cry`, on disk and inside archives | §4.5 | B3 (writer), B2 (op), menu F4 |
| favorites with named categories, add/remove | §3.3 (lists work now on the backend) | F4 (menu), F3 (tree) |
| sidebar "E": group by Folders / Studio / Models / Photograph / Genre / Favorites | §3.5, §7.2 | F3 |
| tree right-click: IDE-like folder refactoring (move, rename, delete, copy, pack/unpack, open in Explorer); entity nodes: assign + pack/crypt/hide | §7.2 | F4 (ops: B2) |
| menus rebuild dynamically; dangerous operations confirmed in a dialog | §7.1 (`ContextMenu`, `Confirm`) | F1 shells now, F4 wiring |
| viewer: sort like the main window + random | §7.5 | F2 |
| viewer: filter out small images | §7.5 | F2 |
| viewer: fit screen / 1:1 / fit only big / fit width / fit height, default configured | §7.5, §7.6 | F2 |
| viewer: full screen, slideshow with interval, follows the sort | §7.5 | F2 |
| viewer: per-photo favorites by category, rating 1–10 | §2.4 (works now: `markPhoto`) | F2 |
| viewer: copy to 1 of 10 configured folders, skip identical, auto-rename | §4.5 Copy photos, §7.6 (settings page shell now) | B3 + B2, F2 |
| viewer: right-click menu; photo metadata (button inactive without metadata) | §7.5, `Entry.flags` | B4 (`photoMeta`, flags), F2 |
| `*bio*.txt` / `*model*.txt` button | §2.6 `bio`, `albumText` | B4, F2 |
| economical types, boolean where boolean, DB design first | §2.1 (done) | phase 0 |
| fast, modular, plugin-ready; name parser gets a–f (name, path, longest subdir, sample image, text, container) | §5 `Extraction` | B4 (contract), phase 3 (`:spi`) |
| cover strategy as a separate module | §5 | B4, phase 3 |
| roles: Fable for concept/UI, Opus for algorithms, Codex for review (astra high / sol xhigh) | §8 | every track |

## 11. Out of scope (explicitly)

The smart name parser and smart cover strategy themselves (only their contracts), drop-in plugin jars, multi-volume
archives, the test gaps backlog of PLAN Next 2–3 (touched only where a track's tests cover them), a photo-level
search across albums (only favorites lists of photos), any multi-user or remote feature.
