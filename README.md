# Thumbnailer

Album-centric organizer for studio photo galleries: albums are folders or zip archives of photos; each one gets a
16:9 / 9:16 cover thumbnail, extracted metadata (model, studio, photographer, date, tags…), a rating and editable
notes. Search is live, incremental scanning keeps the index current, and the whole thing ships as one jar.

- **Backend**: Java 26, Spring Boot 4.1 — WebSocket for data, HTTP for images, H2 (SQL) for metadata, H2 MVStore as
  the thumbnail key-value store. Architecture and wire contract: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- **Frontend**: Angular 22, signals, zoneless, SCSS. Virtualized brick/grid layout, slide-out filter panel, floating
  details panel, album viewer with lightbox and cover selection, light/dark themes, keyboard driven.

## Build and run

Requires JDK 26 on `JAVA_HOME` (ships under `devtools/` in this workspace) and Node.js 22+ with npm installed globally
(OS level, on the system `PATH`; `node`, `npm`, `npx` are called directly by the build).

```bash
./gradlew build                      # UI (npm ci + ng build) + backend + tests → backend/build/libs/thumbnailer-*.jar
java -jar backend/build/libs/thumbnailer-0.1.0.jar            # http://localhost:8080
java -jar backend/build/libs/thumbnailer-0.1.0.jar --thumbnailer.demo=true   # with generated sample albums
```

Useful switches (`application.yml` keys, overridable as `--key=value` or environment variables):

| key | default | meaning |
|---|---|---|
| `thumbnailer.data-dir` | `~/.thumbnailer` | settings.json, H2 database, thumbnail store |
| `thumbnailer.thumb-width` | `640` | longer side of thumbnails |
| `thumbnailer.min-images` | `2` | images a folder needs to count as an album |
| `thumbnailer.demo` | `false` | generate sample albums under the data dir |
| `server.port` / `server.address` | `8080` / `127.0.0.1` | bind address |

Folders to scan, exclusions (`*` globs) and scan-on-start are configured in the UI (Settings → Library) and stored in
`settings.json` in the data dir.

## Development

```bash
./gradlew -PskipFrontend :backend:bootRun     # backend only, no UI build
npm --prefix frontend start                    # Angular dev server on :4200, proxies /ws and /img to :8080
```

The dev server defaults to an **in-browser mock dataset** (`?mock`), so the UI can be developed without the backend;
open `http://localhost:4200/?live` to talk to a running backend. Frontend unit tests: `npm --prefix frontend test`.

## Keyboard

`/` search · `F` refine panel · `I` details panel · `T` theme · `[` `]` albums per row · arrows move · `Enter` open ·
`C` use photo as cover · `Esc` close
