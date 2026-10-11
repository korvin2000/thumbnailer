# Backend source map — `backend/src/main/java/thumbnailer/` (Java 26, Spring Boot 4.1)

[↑ INDEX](../../INDEX.md) · rules: [backend/CLAUDE.md](../../backend/CLAUDE.md) · design: [ARCHITECTURE §2](../ARCHITECTURE.md#2-backend-package-thumbnailer) · wire: [§3](../ARCHITECTURE.md#3-transport)

Legend: **port** = domain interface, **SPI** = extension point (impls collected as beans), `@C` = Spring bean.
Most classes are package-private; public = used across packages.

## Pipeline

```
ScanService.start ─▶ AlbumDiscovery.walk(root) ─Found(path,kind)─▶ Reconciler.Pass.reconcile (pool "index-N")
   └▶ Containers.open ─▶ AlbumIndexer.index (ImageProbe, Metadata chain, CoverSelector, Thumbnailer)
        └▶ AlbumService.commit ─▶ AlbumRepository.save + BlobStore.put ─▶ AlbumEvent ─▶ AlbumSocket broadcast
WS /ws: AlbumSocket.handle(Request) ─▶ AlbumRepository (search/find/facets) | AlbumService.patch | ScanService | SettingsStore
HTTP /img/{id}/…: ImageController ─▶ AlbumService.cover/thumb (lazy render, blob cache) | Containers.open (original)
```

## [album/](../../backend/src/main/java/thumbnailer/album/) — domain records, ports, single mutation owner

- [Album.java](../../backend/src/main/java/thumbnailer/album/Album.java) — wire/store record (+ server-only `Internal(contentId, signature, extracted, edited)`); `withEdits()` recomputes effective fields.
- [AlbumMeta.java](../../backend/src/main/java/thumbnailer/album/AlbumMeta.java) — one metadata layer; `over()` (edit merge, null falls through), `orElse()` (extractor merge, blank falls through, tags union), `effective()`, `tag()` normalizer.
- [AlbumPatch.java](../../backend/src/main/java/thumbnailer/album/AlbumPatch.java) — user edit; null = unchanged, `""`/`[]` = explicit empty, `cover:""` unpins. No date field.
- [AlbumQuery.java](../../backend/src/main/java/thumbnailer/album/AlbumQuery.java) — search criteria + nested `enum Sort` (10 values); clamps limit ≤1000 (default 100), sort default NEWEST.
- [AlbumRepository.java](../../backend/src/main/java/thumbnailer/album/AlbumRepository.java) — **port**: `search count facets find byRoot byContentId rootStats entries entry save delete`; `save(a, null)` keeps entries.
- [BlobStore.java](../../backend/src/main/java/thumbnailer/album/BlobStore.java) — **port**: `put get remove(prefix)`.
- [AlbumService.java](../../backend/src/main/java/thumbnailer/album/AlbumService.java) — `@C` **only writer**: `commit patch remove retainRoots cover thumb`; 64 striped locks; publishes `AlbumEvent` after the lock.
- [AlbumEvent.java](../../backend/src/main/java/thumbnailer/album/AlbumEvent.java) (sealed `Changed|Removed`) · [Entry.java](../../backend/src/main/java/thumbnailer/album/Entry.java) (`index name bytes mtime width height shotAt`) · [Facets.java](../../backend/src/main/java/thumbnailer/album/Facets.java).

## [source/](../../backend/src/main/java/thumbnailer/source/) — where albums live

- [AlbumContainer.java](../../backend/src/main/java/thumbnailer/source/AlbumContainer.java) — `Closeable`: `items() images() open(name)`; `signature()` (all files incl. sidecars, with mtime) vs `contentId()` (images, name+bytes); `IMAGE_EXTENSIONS`.
- [ContainerFactory.java](../../backend/src/main/java/thumbnailer/source/ContainerFactory.java) — **SPI** `kind() accepts(path) open(path, exclusions)`; impls nested `DirectoryContainer.Factory`, `ZipContainer.Factory`.
- [Containers.java](../../backend/src/main/java/thumbnailer/source/Containers.java) — `@C` factory registry: `archive(path)`, `open(kind, path, excl)`; static `normalize()` (abs, `/`) and `prefix()`.
- [DirectoryContainer.java](../../backend/src/main/java/thumbnailer/source/DirectoryContainer.java) · [ZipContainer.java](../../backend/src/main/java/thumbnailer/source/ZipContainer.java) · [Kind.java](../../backend/src/main/java/thumbnailer/source/Kind.java) (`DIRECTORY|ZIP`).
- [Exclusions.java](../../backend/src/main/java/thumbnailer/source/Exclusions.java) — `*` globs (match across `/`, case-insensitive) on normalized absolute paths.

## [image/](../../backend/src/main/java/thumbnailer/image/)

- [ImageProbe.java](../../backend/src/main/java/thumbnailer/image/ImageProbe.java) — header-only `probe()` (w/h/shotAt, orientation applied), `exif()`.
- [Thumbnailer.java](../../backend/src/main/java/thumbnailer/image/Thumbnailer.java) — `@C` `render()` → 16:9 / 9:16 JPEG, longer side `thumb-width`; crop-region subsampled decode; EXIF 2–8.

## [metadata/](../../backend/src/main/java/thumbnailer/metadata/) — extractor chain

- [MetadataExtractor.java](../../backend/src/main/java/thumbnailer/metadata/MetadataExtractor.java) — **SPI** `extract(container, entries)→AlbumMeta`; `date(text)`.
- [Metadata.java](../../backend/src/main/java/thumbnailer/metadata/Metadata.java) — `@C` folds extractors with `orElse` in `@Order`; failing extractor skipped.
- [SidecarExtractor.java](../../backend/src/main/java/thumbnailer/metadata/SidecarExtractor.java) `@Order(1)` (album.json / album.txt / single *.txt) · [FileNameExtractor.java](../../backend/src/main/java/thumbnailer/metadata/FileNameExtractor.java) `@Order(2)` (studio-release names) · [ExifExtractor.java](../../backend/src/main/java/thumbnailer/metadata/ExifExtractor.java) `@Order(3)` (earliest date, Artist).

## [cover/](../../backend/src/main/java/thumbnailer/cover/)

- [CoverStrategy.java](../../backend/src/main/java/thumbnailer/cover/CoverStrategy.java) — **SPI** `pick(entries)`; constants `NAME_HINT` (cover|poster|logo|_cv), `SMALLEST`.
- [CoverSelector.java](../../backend/src/main/java/thumbnailer/cover/CoverSelector.java) — `@C` lazy `candidates()`: pinned → NAME_HINT → SMALLEST → rest. Chain hardcoded (not beans).

## [index/](../../backend/src/main/java/thumbnailer/index/) — scanning

- [AlbumDiscovery.java](../../backend/src/main/java/thumbnailer/index/AlbumDiscovery.java) — `@C` `walk()`: dir with ≥`min-images` images, any archive; skips exclusions/AccessDenied; returns `complete`.
- [AlbumIndexer.java](../../backend/src/main/java/thumbnailer/index/AlbumIndexer.java) — `@C` `index()` → `Indexed(album, entries, cover)`; reuses stored probes for unchanged `(name,bytes,mtime)`.
- [Reconciler.java](../../backend/src/main/java/thumbnailer/index/Reconciler.java) — `@C` `begin()→Pass{load, reconcile, prune}`: unchanged/changed/moved/new/removed.
- [ScanService.java](../../backend/src/main/java/thumbnailer/index/ScanService.java) — public `@C` `start cancel lastScan`, `scanOnStart`, sync `scan()` (tests); one scan at a time; `close()` on context close.
- [ScanProgress.java](../../backend/src/main/java/thumbnailer/index/ScanProgress.java) — event record, throttled ≤4/s.

## [settings/](../../backend/src/main/java/thumbnailer/settings/)

- [Settings.java](../../backend/src/main/java/thumbnailer/settings/Settings.java) — `roots[{path, exclusions}] exclusions scanOnStart`; `requireDisjointRoots()`.
- [SettingsStore.java](../../backend/src/main/java/thumbnailer/settings/SettingsStore.java) — `@C` `settings.json` in data dir (tmp + atomic move).

## [store/](../../backend/src/main/java/thumbnailer/store/) — adapters, chosen by `thumbnailer.store.*`

- [jdbc/JdbcAlbumRepository.java](../../backend/src/main/java/thumbnailer/store/jdbc/JdbcAlbumRepository.java) — `JdbcClient`; private `Where` builds the dynamic filter; tags stored `" a b "`; `save` = delete + insert (tx).
- [mvstore/MvStoreBlobStore.java](../../backend/src/main/java/thumbnailer/store/mvstore/MvStoreBlobStore.java) — `thumbs.mv`, map `blobs`; prefix removal via sorted keys.

## [web/](../../backend/src/main/java/thumbnailer/web/)

- [ws/AlbumSocket.java](../../backend/src/main/java/thumbnailer/web/ws/AlbumSocket.java) — `/ws` handler: `handle(Request)` switch, `id` echo, loopback origins, per-session send decorator, `ws-broadcast` thread, `settingsInfo()`, OS `reveal()`.
- [ws/Request.java](../../backend/src/main/java/thumbnailer/web/ws/Request.java) · [ws/Event.java](../../backend/src/main/java/thumbnailer/web/ws/Event.java) — sealed wire messages (`@JsonTypeInfo` `type`).
- [ImageController.java](../../backend/src/main/java/thumbnailer/web/ImageController.java) — `/img/{id}/cover`, `/{index}/thumb`, `/{index}`; ETag = revision.
- [SpaFallback.java](../../backend/src/main/java/thumbnailer/web/SpaFallback.java) — static resources, `index.html` for dot-less paths.

## Other

- [demo/DemoAlbums.java](../../backend/src/main/java/thumbnailer/demo/DemoAlbums.java) — `thumbnailer.demo=true`: 40 seeded albums under `<dataDir>/demo`, registered as root if none.
- [ThumbnailerApplication.java](../../backend/src/main/java/thumbnailer/ThumbnailerApplication.java) — main; `ImageIO.scanForPlugins()` (fat-jar plugins).
- [application.yml](../../backend/src/main/resources/application.yml) · [schema.sql](../../backend/src/main/resources/schema.sql) (tables `album`, `entry`; no FK) · [build.gradle.kts](../../backend/build.gradle.kts).

## Tests — [backend/src/test/java/thumbnailer/](../../backend/src/test/java/thumbnailer/) (JUnit 5 + AssertJ)

- [index/ScanTest.java](../../backend/src/test/java/thumbnailer/index/ScanTest.java) — full `@SpringBootTest`: add/change/move/remove, rename, exclusions, overlapping roots, edits survive rescan, WS id echo + hidden fields.
- [store/jdbc/JdbcAlbumRepositoryTest.java](../../backend/src/test/java/thumbnailer/store/jdbc/JdbcAlbumRepositoryTest.java) — plain H2: filters, sorts, paging, facets, JSON layers.
- [image/ThumbnailerTest.java](../../backend/src/test/java/thumbnailer/image/ThumbnailerTest.java) · [cover/CoverSelectorTest.java](../../backend/src/test/java/thumbnailer/cover/CoverSelectorTest.java) · [metadata/FileNameExtractorTest.java](../../backend/src/test/java/thumbnailer/metadata/FileNameExtractorTest.java) · [source/ExclusionsTest.java](../../backend/src/test/java/thumbnailer/source/ExclusionsTest.java) — plain unit tests.
- Not covered: ImageController, ZipContainer/Containers, AlbumDiscovery, MvStoreBlobStore, SettingsStore, DemoAlbums, non-search WS requests.
