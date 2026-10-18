# INTENT — why Thumbnailer exists

<!-- Distilled from the original brief in goal.md (mixed RU/EN). Change only when the product direction changes. -->

**One line:** a local, album-centric organizer for professional studio photo shoots that makes thousands of albums
(folders and zip archives) browsable, searchable and curatable from one self-contained jar.

## Who and what for

Owners of large libraries of studio-made shoots (portrait, wedding, outdoor, sport, advertising…). The unit of search
and layout is the **album** (a shoot / gallery): users look for *a shoot* by model, studio, photographer, date, style
tags, size or quality, then open it, rate it, fix its metadata or pick a better cover. Around the albums grows a
**catalog** of the people and places behind them — models, studios/agencies, photographers, genres — with cards,
aliases, curated relationships and named favorites lists; single photos can be rated, listed as favorites and copied
out, but never searched for across the library ([catalog_plan.md](catalog_plan.md)).

## Outcomes (in priority order)

1. **Find any album fast** — live search-as-you-type plus refine filters (people, studio, photographer, description,
   tags, size, photo count, average resolution, date range, rating) and sort (newest, largest, best quality…).
2. **See the library at a glance** — 16:9 / 9:16 covers packed like bricks with no gaps, name and size on each tile,
   hover meta, a floating details panel; configurable tiles per row; dark/light; responsive; keyboard driven.
3. **Trust the index** — scanning is incremental (only added/changed/moved/removed albums are touched), survives
   restarts and unplugged disks, honours `*` exclusions, and never loses user edits.
4. **Curate** — rating 1–10, editable name/model/studio/photographer/description/tags/genres, user-chosen cover,
   reveal the album in the OS file manager, browse its photos in a viewer; link albums to catalog entities (auto from
   the text, merged and confirmed by hand), group the library by studio, model, photographer, genre or list.
5. **Organize the files** — explicit, confirmed, journaled operations on the library (move, delete, hide, pack/unpack/
   repack, encrypt/decrypt, copy photos out) that verify every result byte for byte before a source goes.
6. **Run anywhere, simply** — one jar serves backend and compiled UI; local only (binds 127.0.0.1, no auth).

## Quality bar

- Maintainable, readable, extensible OOP: feature packages, small SPIs (container kinds, metadata extractors, cover
  strategies, storage adapters), no god classes, no over-engineering. Compact code (`compact-code` + `god-style` skills).
- Storage is swappable: metadata DB and thumbnail KV store sit behind domain ports, chosen by configuration.
- Async: WebSocket for data and live updates, HTTP for image bytes; scans never block the UI.
- UI: elegant, polished, modern, space-efficient, usability first.

## Extension directions (designed for, not built)

Other archive formats (7z, rar), other image formats, more metadata sources (text sidecars, embedded tags), other
databases / KV stores.

## Non-goals

Photo editing, a photo-level search across albums, multi-user / remote access / authentication, cloud sync. The
library on disk changes only through the explicit operations of outcome 5 — never while scanning or browsing, and
never without a confirm dialog; safe-delete is the `hidden` flag in the index and touches no file.
