# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

<!-- Keep under ~100 lines. Area rules live in backend/CLAUDE.md and frontend/CLAUDE.md (loaded on demand) and
     .claude/rules/ (path-scoped). Facts that go stale (file lists, symbols) belong in docs/map/, not here. -->

Thumbnailer: local, album-centric organizer for studio photo shoots. One Spring Boot jar (Java 27, Undertow) serves an
Angular 22 UI, a WebSocket data channel and HTTP image endpoints; albums are directories or zips on disk.

## Navigate first (token budget)

1. [INDEX.md](INDEX.md) — doc hierarchy + **task router** (which files a given change touches).
2. [docs/map/backend.md](docs/map/backend.md), [docs/map/frontend.md](docs/map/frontend.md) — every source file with role
   and key symbols. Read the map, then open only the files it names; don't explore the tree blind.
3. Behaviour/contract questions: [SPEC.md](SPEC.md) (requirements + status), [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
   (domain model, wire protocol §3, layout engine §4). Why: [INTENT.md](INTENT.md). Next work: [PLAN.md](PLAN.md).

## Toolchain

- **Node.js and npm are installed globally** at OS level (`C:\Program Files\nodejs`, on the system PATH): call
  `node`, `npm`, `npx` directly in any shell — no setup, no local copy in `devtools/`.
- **JDK 27 is not on PATH**; it lives in `devtools/` (gitignored; Gradle 9.8.1 too, the wrapper downloads its own). Set `JAVA_HOME` before Gradle:
  Bash `export JAVA_HOME=/c/work/thumbnailer/devtools/jdk-27` · PowerShell
  `$env:JAVA_HOME='C:\work\thumbnailer\devtools\jdk-27'`.

## Commands (repo root)

| task | command |
|---|---|
| full build (UI + backend + tests → `backend/build/libs/thumbnailer-0.1.0.jar`) | `./gradlew build` |
| backend tests (skip UI build) | `./gradlew -PskipFrontend :backend:test` |
| one backend test class / method | `./gradlew -PskipFrontend :backend:test --tests 'thumbnailer.cover.CoverSelectorTest'` (append `.method`) |
| run backend on :8080 | `./gradlew -PskipFrontend :backend:bootRun` (add `--args='--thumbnailer.demo=true'` for sample albums) |
| run packaged jar | `java --add-modules jdk.incubator.vector -jar backend/build/libs/thumbnailer-0.1.0.jar [--thumbnailer.demo=true]` (without the module the resampler runs scalar) |
| UI dev server :4200 (proxies `/ws`, `/img` → :8080) | `npm --prefix frontend start` |
| frontend tests (once) | `npm --prefix frontend test -- --watch=false` |
| one spec | `npm --prefix frontend test -- --watch=false --include src/app/core/layout.spec.ts` |
| frontend build | `npm --prefix frontend run build` |
| resampler quality/speed benchmark (standalone build, see [scalebench/README.md](scalebench/README.md)) | `./gradlew -p scalebench run --args="all"` (`--only=A,B`, `--quick`) |
| render pipeline timings (probe, cover, thumb, throughput) | `THUMBNAILER_BENCH=1 ./gradlew -PskipFrontend :backend:test --tests '*RenderBench'` → `backend/build/render-bench.md` |
| KV store benchmark (standalone build, see [kvbench/README.md](kvbench/README.md)) | `./gradlew -p kvbench installDist`, then in `kvbench/`: `build/install/kvbench/bin/kvbench all 100000` / `report` |

Preview servers for the browser pane are in `.claude/launch.json`: `frontend` (:4200), `backend` (:8080, sets
`JAVA_HOME` itself) and `backend-demo` (:8081, the packaged jar on a scratch library `backend/build/smoke-home` with demo albums; never the real `~/.thumbnailer`), `backend-hdd` (:8082, the packaged jar on the scratch library `backend/build/hdd-home` whose only root is the owner's HDD test folder `D:/TESTS/albums`, read only; rules for that disk in [hdd_ssd_fix_goals.md](hdd_ssd_fix_goals.md)). The dev UI uses the **in-browser mock** by default; open `http://localhost:4200/?live` to talk to
the backend, `?mock` forces the mock in any build. `ScanTest` is a full `@SpringBootTest` (slowest); the other backend
tests are plain unit tests. No linter is configured.

## Architecture in five lines

- Backend = framework-free domain (`album`: records + ports `AlbumRepository`, `BlobStore`) with adapters at the edge
  (`store/jdbc` H2, `store/tupl` TuplDB covers, `store/mvstore` alternative), SPIs for containers, metadata extractors and cover strategies.
- Scan pipeline: `ScanService` → `AlbumDiscovery` → `Reconciler` (unchanged/changed/moved/new/removed) →
  `AlbumIndexer` → `AlbumService.commit` → Spring event → `AlbumSocket` broadcast.
- Transport: WS `/ws` JSON messages `{type, id?}` (sealed `Request`/`Event`); HTTP `/img/{id}/…?v={revision}` for bytes.
- Frontend: `AlbumStore` (signals) is the single state holder; it talks only to the abstract `Gateway`
  (`WsGateway` live, `MockGateway` dev); `layout.ts` is a pure brick/grid engine feeding the virtualized `TileGrid`.
- Hot path is native ([docs/natives.md](docs/natives.md)): libjpeg-turbo over FFM, libwebp (webp4j JNI), 7-Zip (7-Zip-JBinding) for
  RAR/7z/tar/ISO/tar.gz; all loaded by `natives.LibraryLoader` from a persistent cache. One `Pixels` layout from decode to encode.
- Settings (`settings.json`), H2 DB and the cover store (`covers/`, covers only: other images are read from disk) live in the library dir `thumbnailer.library-dir`; `thumbnailer.data-dir` (default `~/.thumbnailer`) is the home with `libraries.json` and the default library.

## Rules that span both halves

- **Wire contract is mirrored in four places**: backend records (`web/ws/Request`, `Event`, `album/*`), frontend
  `core/model.ts`, `core/mock-gateway.ts` semantics, and ARCHITECTURE §3. Change them together.
- **`revision`** is the HTTP cache key end to end (ETag, `?v=` in image URLs, stale-update guard in the UI).
  Every album save must bump it; never reuse a revision for different bytes. Stored covers are keyed by album id and
  tagged with the album state they show (see `AlbumService`), so a stale value is a miss, never a hit.
- All album mutation goes through `AlbumService` (backend) and `AlbumStore` (frontend); nothing else writes.
- The library on disk is read-only to the app. Demo data goes only under `<library-dir>/demo`.
- Java: follow the project skills `compact-code` and `god-style` (small, dense, records, no god classes, no
  speculative abstraction). Match the existing dense style on both sides; comments state intent, sparingly.

## Docs are part of the change (mandatory)

Any change to code, config or build must leave the docs true in the **same change**: source maps (`docs/map/*.md`),
SPEC status, ARCHITECTURE contract, README/CLAUDE commands, PLAN state. Full change→doc table:
[.claude/rules/docs-sync.md](.claude/rules/docs-sync.md) (auto-loads when touching source). A task is not done while a
map line, SPEC row or command here is stale. Link between docs; never copy content.
Enforced by a Stop hook ([.claude/hooks/docs-sync-check.mjs](.claude/hooks/docs-sync-check.mjs), registered in
`.claude/settings.json`): when uncommitted code/config files are newer than every doc, the stop is blocked once per
change set — update the docs, or say why none is needed.
