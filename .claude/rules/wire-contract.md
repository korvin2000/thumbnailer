---
paths:
  - "backend/src/main/java/thumbnailer/web/**"
  - "backend/src/main/java/thumbnailer/album/{Album,AlbumMeta,AlbumPatch,AlbumQuery,Entry,Facets}.java"
  - "backend/src/main/java/thumbnailer/settings/Settings.java"
  - "backend/src/main/java/thumbnailer/index/ScanProgress.java"
  - "frontend/src/app/core/{model,gateway,ws-gateway,mock-gateway}.ts"
---

# Wire contract — change all mirrors together

The WS/HTTP contract is defined once in `docs/ARCHITECTURE.md` §3 and mirrored in code. A change to any of these
must be applied to all that apply, in the same change:

1. Backend records: `web/ws/Request.java` / `Event.java` (`@JsonTypeName` = wire `type`), `album/*` records,
   `settings/Settings.java`, `index/ScanProgress.java`; dispatch in `web/ws/AlbumSocket.handle`.
2. Server-only fields stay hidden: `Album.internal`, `Entry.mtime` (mixins in `AlbumSocket`).
3. Frontend types in `core/model.ts`; methods in abstract `core/gateway.ts`, `core/ws-gateway.ts`, and the
   emulation in `core/mock-gateway.ts` (same filter/sort semantics, same `revision` bumps).
4. HTTP image routes: `web/ImageController.java` ↔ `coverUrl/thumbUrl/originalUrl` in `ws-gateway.ts`.
5. `docs/ARCHITECTURE.md` §3 tables; SPEC row if behaviour changed.
6. Verify: `ScanTest.socketEchoesIdsHidesServerFieldsAndDropsRemovedRoots` (backend) and a `?live` smoke run.
