# Thumbnailer: critical engineering review

Reviewed **2026-10-22**, at commit `057109334a5d050c787f2a728c905985a74e1978`.

## Assessment

Thumbnailer has a credible architecture and substantial defensive engineering. The separation between source files, authoritative catalog data, and regenerable covers is useful. Incremental scanning, packed image metadata, native scaled JPEG decoding, disk scheduling, and a sequential-archive gallery address real workload costs. The existing tests exercise significantly more than happy paths.

Nevertheless, **destructive operations and asynchronous state changes need another correctness pass before this application should be trusted with the only copy of a photo library**. This review reproduced two recovery paths that delete source changes, a Windows destination-overwrite defect, a queued edit targeting the wrong entity, a persisted hierarchy cycle, and a cover-cache race. Existing passing tests do not cover these cases.

The best next investment is to repair identity, recovery, and asynchronous ownership boundaries, then remove repeated metadata decoding and impose memory budgets. Replacing the frameworks, database, layout engine, or native resampler would not address the most important findings.

This is a review, not an implementation change. Production sources were not modified. Scratch probes and generated evidence are in ignored build directories; the requested deliverable is this file.

Reading guide: [evidence and limits](#scope-evidence-and-limitations), [prioritized findings](#findings-at-a-glance), [data model](#data-model-assessment-and-recommended-evolution), [optimization plan](#performance-and-optimization-plan), [missing capabilities](#missing-capabilities-and-workflow-improvements), [remediation sequence](#remediation-sequence-and-acceptance-criteria), [reproduction evidence](#evidence-appendix).

## Scope, evidence, and limitations

The review covered backend domain records, H2/JDBC and cover stores, catalog relations, scanning/reconciliation, filesystem operations and recovery, archive adapters, native image bindings, settings/libraries, HTTP/WebSocket transport, Angular state and important user workflows, build configuration, tests, and project documentation.

Parallel reviews used **GPT-6** for native/I/O/operations and **GPT-6.1** for database/model and frontend/transport. The primary review checked important claims against their call sites and ran additional probes. The database reviewer independently checked the destructive recovery findings; the native/I/O reviewer independently checked the tombstone cover race. Agreement alone was not treated as proof.

Evidence terminology:

- **Reproduced:** observed in an isolated executable probe. The specific harness and limits are stated.
- **Source-confirmed:** the implementation establishes the behavior or missing invariant; no complete live reproduction is claimed.
- **Risk/design gap:** a plausible failure mode, deliberate tradeoff, or missing capability requiring further measurement or product judgment.
- **Optimization candidate:** a measurable opportunity, not a claimed speedup.

Priorities:

- **P1:** source-file loss, wrong-target mutation, or another serious integrity/availability risk.
- **P2:** functional defect, scalability problem, or recovery/workflow weakness.
- **P3:** maintainability, diagnostics, or lower-impact hardening.

No real user library was opened or modified. Destructive-operation probes used generated files in temporary test directories. No production-size benchmark, power-cut test, native fuzzing campaign, sustained heap/native-memory profile, browser visual audit, or screen-reader session was performed. Linux/macOS and another physical volume were not exercised. A clean dependency audit of Java and all bundled native binaries was not performed.

### Verification performed

| Check | Result | Interpretation |
|---|---|---|
| `gradlew.bat -PskipFrontend :backend:test :plugins:names:test --console=plain` | Successful; backend 172 tests reported, 1 skipped, 0 failures/errors; names plugin 4 tests passed | Existing baseline is green; backend compilation was already current, test execution ran |
| `npm --prefix frontend test -- --watch=false` | 15 files, 103 tests passed | Existing frontend unit suite is green |
| `npm --prefix frontend run build` | Successful, no warnings; 573.00 kB initial, estimated transfer 143.38 kB | Production UI compiles; not a browser runtime test |
| Move recovery source-preservation probe | Failed the desired safety assertion: newly added source file disappeared | Confirms F01 |
| Encryption recovery source-preservation probe | Failed the desired safety assertion: changed original disappeared | Confirms F02 |
| Windows existing-destination probe | Expected conflict exception was not thrown | Confirms F03's case-insensitive destination problem |
| Transactional H2 catalog/cover probes | Both assertions of the defective behavior passed | Confirms F06 and F08; these are diagnostic probes, not tests asserting correct behavior |
| Frontend deferred-response probes | Wrong-card mutation, stale cache repopulation, stale deleted entity, and mark-state overwrite observed | Confirms the service behavior described below; uses real TypeScript with small framework/transport stubs |
| WebSocket reconnect probe | Queued destructive request sent before connected event/bootstrap | Confirms F05's ordering; no real second library was changed |
| `npm audit --json` | Exit 1; 2 affected dependency nodes, 1 high and 1 low, with 6 advisories | Development-tool dependency findings; see F28 |

The focused tests do **not** indicate that production sources were changed to break the original suite. They add scenarios the original suite did not exercise. Details and reproduction commands are in the evidence appendix.

## Findings at a glance

| ID | Priority | Finding | Evidence |
|---|---|---|---|
| F01 | P1 | Recovery from DISPOSING can delete newly changed source files | Reproduced, independently reviewed |
| F02 | P1 | Per-file encryption can delete a modified original | Recovery reproduced; normal path source-confirmed |
| F03 | P1 | Windows move can replace an unrelated destination | Helper reproduced; implementation checked |
| F04 | P1 | Queued card edit can save into another entity | Reproduced |
| F05 | P1 | Offline writes can be sent into another library | Transport ordering reproduced |
| F06 | P1 | Tombstone revival can tag the wrong cover bytes as valid | Reproduced, independently reviewed |
| F07 | P2 | Unreadable subfolders can be pruned from the index | Source-confirmed |
| F08 | P2 | Entity merge can persist a parent cycle | Reproduced |
| F09 | P2 | Candidate fingerprints are trusted as identity | Design weakness with deterministic ambiguity |
| F10 | P2 | HTTP revision URLs can return another revision's image | Source-confirmed |
| F11 | P1 | Image/archive work is bounded by task count, not memory | Source-confirmed capacity risk; no OOM induced |
| F12 | P2 | Broadcast queue is unbounded behind synchronous sends | Source-confirmed resource risk |
| F13 | P2 | Serving N images can decode N packed entry lists | Source-confirmed quadratic work |
| F14 | P2 | Legacy entry migration materializes the whole library | Source-confirmed capacity risk |
| F15 | P2 | Photo marks lack a shared version/event/concurrency model | Source-confirmed; stale-load probe reproduced |
| F16 | P2 | Queued photo marks can change ownership after navigation | Source-confirmed |
| F17 | P2 | Reconnect does not reconstruct active views or jobs | Source-confirmed |
| F18 | P2 | Catalog invalidation leaves stale async state and deleted IDs | Reproduced |
| F19 | P2 | Favorites updates depend on object identity across reloads | Source-confirmed |
| F20 | P2 | Albums are silently truncated to 5,000 viewer entries | Source-confirmed |
| F21 | P2 | Temporary image failures and slow slideshows lack recovery | Source-confirmed; visual behavior not tested |
| F22 | P2 | Duplicate suppression can remove all matches for a filter | Confirmed query policy; UX design issue |
| F23 | P2 | Corrupt serialized data has inconsistent validation/recovery | Source-confirmed gaps |
| F24 | P2 | Durability guarantees exceed the demonstrated evidence | Risk/tradeoff |
| F25 | P2 | Unresolved recovery and some failures are poorly surfaced | Source-confirmed |
| F26 | P2 | Read quiescence/shutdown can finish while work remains | Source-confirmed invariant gap |
| F27 | P3 | Native cache recovery and compatibility diagnostics differ | Source-confirmed; support limits documented |
| F28 | P2, dev scope | Nested development Vite remains vulnerable | Audit and official advisories |
| F29 | P2 | Visual keyboard focus lacks accessible state relationships | Source audit |
| F30 | P3 | Contract/type/documentation drift increases maintenance risk | Source-confirmed |

## Destructive operations and identity integrity

### F01 — Recovery from DISPOSING deletes changes absent from the verified result

**P1 · Reproduced.** Locations: `backend/src/main/java/thumbnailer/ops/Steps.java:177–190,213–223,305–326`.

`resume()` accepts both VERIFIED and DISPOSING, verifies the staged result, and resumes `finish()`. However, the source-change check runs only when `from == VERIFIED`. A restart from DISPOSING skips it and calls `dispose()`, which deletes the source tree or the selected source members.

Reproduced sequence:

1. Start a move through the verified-copy path.
2. Simulate a crash immediately after DISPOSING is journaled, before disposal.
3. Add `new.jpg` to the source directory while the application is down.
4. Recover. The staged result verifies against the old snapshot, and recovery deletes `new.jpg` with the source.

The added file never existed in the staged result. This is actual source-data loss, not a stale database record.

**Fix carefully:** merely adding a stamp check followed by `rollback()` is unsafe. Disposal may already have deleted some originals; their only surviving copies may be inside `.part`, and rollback deletes that result. On ambiguity, preserve remaining source, verified result, and journal. Persist enough per-member source identity/fingerprint information to resume only proven-safe deletions. Old journal records without that evidence must not authorize destructive recovery automatically.

**Required tests:** changes before disposal, changes after partial disposal, crash after full disposal but before installation, and a recovery refusal that preserves the sole remaining copy of already deleted members.

### F02 — Encryption verifies the output but does not revalidate the original

**P1 · Recovery reproduced; normal-run gap source-confirmed.** Location: `backend/src/main/java/thumbnailer/ops/Steps.java:395–445`, methods `perFile()` and `resumeFile()`.

Per-file CRYPT writes a transformed temporary file, verifies it, journals DISPOSING, and deletes the original. The result hash establishes what was written; it does not establish that the original is still the file that was read. Recovery likewise verifies the temporary result and then deletes the current original without checking its identity or contents.

A scratch test interrupted encryption at DISPOSING, changed `a.jpg`, and recovered. The safety assertion that the changed original still exists failed. The normal path also has an external-edit window between reading the input and deleting it.

Persist a source fingerprint before transformation, revalidate before deletion, and retain both versions when they differ. Treat file replacement, same-size edits, timestamp-preserving edits, and interruption during deletion separately. Do not infer unchanged input from a valid output hash.

### F03 — Atomic move is not a no-replace move on Windows

**P1 · Reproduced at the filesystem helper.** Locations: `backend/src/main/java/thumbnailer/ops/Trees.java`, `move()` and `present()` around lines 135–150; `ops/Steps.java:329–349`, `install()`.

`Trees.move()` checks `present(to)` and then uses `Files.move(..., ATOMIC_MOVE)`. Two problems combine:

- The existence check and rename are separate operations, so another writer can create a destination between them.
- `present()` deliberately requires exact filename case. On a case-insensitive filesystem, an unrelated `Existing.txt` makes `present(existing.txt)` false even though both paths address the same existing file.

The isolated Windows test created generated source and destination files, then requested a move to the differently cased destination. No `FileAlreadyExistsException` was thrown. The bundled JDK 27 Windows implementation uses `MOVEFILE_REPLACE_EXISTING` for ATOMIC_MOVE. The Java API also explicitly leaves replacement behavior implementation-dependent for an existing atomic-move target. [Java Files.move contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).

The planner already uses `Files.exists()` for ordinary initial conflicts. The application-level exposure is a destination appearing after planning or while an unfinished operation awaits recovery; the helper test does not claim the initial planner misses every ordinary collision.

Separate “exists under this exact spelling” from “destination is occupied.” Reserve exact-case checks for recognizing the operation's own case-only rename. Use a platform-appropriate no-replace installation primitive and a recovery protocol for collisions. A second precheck alone does not close the race.

### F04 — Queued entity edits can target the next card

**P1 · Reproduced.** Locations: `frontend/src/app/core/catalog-store.ts:109–116`, `trySaveCard()`; `frontend/src/app/ui/entity-card.ts:392`.

The queued callback reads `this.card()` when it starts executing, rather than binding the user's edit to the card identity at enqueue time. With one save pending, queue a rename on card 1, navigate to card 2, then resolve the first save. The probe recorded a rating save for entity 1 followed by the queued rename sent to entity 2.

Capture library generation and entity ID at enqueue time. Serializing edits is useful, but rebuild the payload from the latest state of that **same entity**, never whichever card is now visible. Choose an explicit navigation policy: complete its pending saves in the background, or cancel unsent edits visibly. Test navigation, close/reopen, merge/delete, and library switching while saves are queued.

### F05 — Queued writes can cross the library boundary

**P1 · Transport ordering reproduced; cross-library effect follows the current contract.** Locations: `frontend/src/app/core/ws-gateway.ts:88–104`; `frontend/src/app/core/store.ts:175–180,265–267`.

When a WebSocket opens, the gateway sends all unsent requests before announcing reconnection. Only afterward does the store fetch settings and detect a changed library. Reads and mutations share this queue. A queued `deleteEntities([7])` therefore reaches the server before library identity is checked; integer entity 7 can refer to a completely different entity after another tab switches libraries. Settings and query-based bulk operations have a similar ownership problem.

The probe demonstrated send-before-bootstrap using the actual gateway. It did not execute a real deletion in a second library.

Add a server-issued library identity and session/connection generation. Establish it before sending writes, include it in mutation requests, and reject mismatches server-side. Automatically retrying reads is reasonable; replaying unscoped writes is not. Reject or explicitly reconfirm writes queued across a library change. Also distinguish “not sent” from “sent but reply lost”; blindly retrying the latter needs idempotency support.

### F06 — Tombstone revival can make wrong cover bytes a valid cache hit

**P1 · Reproduced with transactional H2; independently reviewed.** Locations: `backend/src/main/java/thumbnailer/album/AlbumService.java:124–132,153–177,191,485–495`.

A new scan result locks the content-ID stripe when `base == null`. It may then adopt a tombstone's different album ID. Ordinary patches lock the album-ID stripe. Those locks need not coincide.

The reproduction interleaved a patch selecting `b.jpg` after the revival SQL save but before `save()` refetched the album. The refetch returned the patched album, and `store()` tagged bytes already rendered from `a.jpg` using `b.jpg`'s cover tag. The cache consequently accepts the wrong bytes instead of treating them as a miss. This directly breaks the stated cache-state invariant.

Resolve the final identity, acquire the final album stripe, and recheck adoption under that lock. Avoid acquiring content and ID stripes in opposite orders. Independently bind rendered bytes to an immutable render-source snapshot; a later database refetch must never supply their identity tag. Test revival versus patch, removal, another adopter, and pin changes.

### F07 — An unreadable subtree can disappear from the index

**P2 · Source-confirmed.** Locations: `backend/src/main/java/thumbnailer/index/AlbumDiscovery.java:60–71`; `ScanService.java:195–202`; `Reconciler.java:93–103`.

`visitFileFailed()` treats AccessDenied on a non-root directory as a successful skip. The enclosing walk can remain complete. Pruning then removes stored albums below that scope which were not seen. Thus a previously indexed folder becoming unreadable can remove its albums from search even though its files still exist.

Tombstones may preserve curation for later restoration, so this is not direct filesystem deletion. It nevertheless violates the user expectation that temporary access failures preserve the index. The special treatment of system directories should not imply that all denied directories are empty.

Record incomplete subtrees and protect their known albums during pruning, or conservatively prevent pruning for that scope. Use a deterministic failing filesystem/visitor test, plus a platform ACL integration test. Do not alter permissions on an actual library to test it.

### F08 — Merge can create a cyclic parent hierarchy

**P2 · Reproduced.** Locations: `backend/src/main/java/thumbnailer/catalog/Catalog.java:171`, `merge()` versus `refuseParent()`; `store/jdbc/JdbcCatalogRepository.java:304–320`.

Ordinary entity saves reject parent cycles; merging does not apply an equivalent check. Create studio A, B with parent A, and C with parent B. Merge A into C. The persisted result has B pointing to C and C pointing to B.

Traversal guards can prevent an infinite loop, but they do not make this a valid hierarchy. Under the catalog write lock, validate the graph after merging identities. Either reject the merge with an explanation or apply a documented parent-selection policy that preserves acyclicity. Include non-adjacent ancestors, multiple source entities, and existing malformed data in tests.

### F09 — The content ID is a candidate fingerprint, not content identity

**P2 · Architectural weakness.** Locations: `backend/src/main/java/thumbnailer/source/Stamps.java:28–31`; `index/Reconciler.java`, `moved()`; `album/AlbumService.java:129`.

The fingerprint hashes image names and byte lengths, not image contents. Different images with the same names and lengths deterministically share it. This is not the small probability of a 64-bit hash collision. For example, identically named fixed-dimension uncompressed BMP files can have identical lengths while depicting entirely different images.

The copies UI appropriately calls these candidates and provides stronger comparison. However, reconciliation and tombstone adoption use that same weak evidence to transfer album identity, edits, rating, hidden state, pins, and photo marks. An unrelated album can inherit another album's curation or become hidden. Timestamp/size-based scan signatures also miss same-size edits whose mtime is preserved; archive signatures have the analogous limitation.

Keep cheap fingerprints for candidate lookup and unchanged fast paths. Before automatic identity transfer, use a stronger stored content fingerprint or require an unambiguous match with additional evidence. The prior fingerprint must have been persisted while the original bytes were available: hashing a new candidate after the old source disappears cannot retroactively prove identity. Without sufficient prior evidence, preserve the ambiguity or request an identity decision. Calculate hashes during initial/idle verification and when comparing available candidates, rather than hashing every image on every scan. Name the concepts distinctly: source-change stamp, candidate fingerprint, verified content identity, and user-facing album ID.

## Resource use, database access, and corruption handling

### F10 — Image URLs advertise versions the server does not enforce

**P2 · Source-confirmed by backend and frontend reviews.** Locations: `frontend/src/app/core/ws-gateway.ts:79–81`; `backend/src/main/java/thumbnailer/web/ImageController.java:56–109,130–138`.

URLs carry `v=revision` and rendered images carry `r=renderEpoch`, but the controller does not validate either request parameter. It loads the current album and current entry at the requested numeric index. After a reindex changes positions, a delayed old URL for index 3 can return another photo and cache it for a day under the old URL. The response's newer ETag does not change the URL the browser caches.

Validate the requested revision before resolving the index, return an explicit stale-state response, and have the viewer reload metadata. Consider stable entry-name/ID addressing plus validated revision. Snapshot the render specification consistently too; do not cache bytes produced under a new specification under an old requested epoch. Test requests issued before an index change but handled afterward.

### F11 — Task-count limits do not establish a memory budget

**P1 capacity risk · Source-confirmed; no exhaustion attack or OOM test was run.** Locations: `album/AlbumService.java:462–477`; `source/SevenZipContainer.java:237–250,283–317`; `ops/pack/ZipPacker.java:46–65`; `image/TurboJpeg.java:71–90`; `image/WebP.java`, `decode()`; `index/AlbumIndexer.java:135–149`.

There are good limits on simultaneous renders and gallery lookahead, but several individual units can be huge:

- ZIP packing reads an image entirely into a byte array to obtain its STORED CRC, allowing sizes close to 2 GiB.
- 7-Zip extraction buffers a full member before passing an InputStream to the consumer. Array growth can temporarily retain both old and new arrays.
- Full-head fallbacks and gallery reads can request an entire member. A malicious or merely oversized listing can request a very large allocation before useful decoding.
- Native input buffers remain resident while waiting for decode slots. Queue capacity is `2 × permits`, not a byte limit.
- Still WebP is decoded at full pixel dimensions. Lossless JPEG ignores ordinary DCT scaling; JPEG decoding can hold both native and Java pixel buffers.

For illustration, 32 queued 100 MiB inputs require about 3.1 GiB just for encoded data, before pixels, native decoder workspaces, heap duplication, database caches, or gallery buffers. This is a capacity calculation, not a measured workload.

Introduce checked per-file and per-image-pixel limits, checked long arithmetic before allocation, and a shared in-flight byte budget. Reject oversized work with a useful item-level error. For archive transformations, stream through bounded buffers or a quota-controlled temporary spool. For STORED ZIP, a bounded CRC pass/spool or an explicitly chosen streaming alternative is preferable to arbitrarily large `readAllBytes()` calls. Retain one-pass behavior for solid archives when designing that spool.

### F12 — Broadcast backpressure stops at the wrong queue

**P2 · Source-confirmed resource risk.** Locations: `backend/src/main/java/thumbnailer/web/ws/AlbumSocket.java:66–67,121–123,185–191,300–302`.

The single-thread executor has an unbounded task queue. Each task retains its event until all synchronous sends complete. The per-session decorator limits its own buffer, but does not bound events waiting in the executor. A slow session or expensive serialization can delay all clients while scans continue enqueueing album events. The comment that scans never wait is true only because the backlog is retained elsewhere.

Bound/coalesce pending events: keep the latest album state by ID, latest progress by operation, and explicit terminal events. Isolate slow-client delivery and disconnect clients that cannot catch up. Send a resynchronization marker when intermediate events are discarded. Record queue depth, oldest-event age, coalesced count, and disconnected slow clients. Preserve ordering where it actually matters, especially removal versus recreation.

### F13 — Individual image lookup repeatedly unpacks the whole album

**P2 · Source-confirmed algorithmic cost.** Locations: `backend/src/main/java/thumbnailer/store/jdbc/JdbcAlbumRepository.java:203–220`; `EntryCodec.java:58–78`; `web/ImageController.java:69,80,123`.

`entry(albumId, index)` calls `entries(albumId)` and unpacks every Entry and filename. Viewing N photos in an N-entry album therefore performs roughly N² entry decodes across requests. A 5,000-photo gallery can create about 25 million Entry instances just for those lookups. The sequential archive gallery avoids repeated decompression passes, but does not avoid this preceding database work.

Keep the compact database representation initially. Add a bounded cache of immutable decoded entries, with invalidation on full album save, `saveEntries()`, and delete. Its key must account for changes to metadata flags, which currently do not bump album revision. Bound by estimated bytes, not just album count. If that is insufficient, compare chunked/indexed packed data against normalized rows using realistic libraries.

Also coalesce metadata-flag updates: learning one photo's flags currently rewrites the album's packed list. Do not turn a compact representation into repeated whole-value write amplification unnecessarily.

### F14 — Legacy migration has library-sized peak memory

**P2 · Source-confirmed.** Location: `backend/src/main/java/thumbnailer/store/jdbc/JdbcAlbumRepository.java:100–111`.

The legacy entry-table migration collects all albums' entries into a `LinkedHashMap<String,List<Entry>>` before writing packed values. A library large enough to motivate packed storage can fail to upgrade because migration retains millions of objects at once. The existing small migration fixture does not establish capacity safety.

Read the ordered result one album at a time, pack/write that group, then release it. Make progress restartable and only remove the legacy table after successful completion. Test interruption between groups and migration under a constrained heap. A general schema-version ledger would make future upgrades easier to reason about, but this particular fix does not require adopting a large migration framework.

### F23 — Corruption and schema validation are inconsistent

**P2 · Source-confirmed gaps; not a demonstrated remote exploit.** Locations: `store/jdbc/EntryCodec.java:58–78,96–105`; `settings/SettingsStore.java:27–44`; `settings/Libraries.java:114–121`; `catalog/Catalog.java:105–125`; `frontend/src/app/core/prefs.ts`, `persisted()`.

- Entry decoding trusts count, prefix length, suffix length, and unbounded varints. Corrupt data can produce excessive allocation, wrapped values, or opaque exceptions. Validate counts against remaining bytes and a supported maximum, bound varints, validate shared prefixes, require full suffix reads, and reject trailing/invalid data deliberately.
- Malformed `settings.json` prevents normal startup. Failing rather than silently discarding settings is sensible, but there is no guided recovery path or last-known-good backup.
- Unreadable `libraries.json` is treated as absent. This keeps startup possible but can make the application appear to have lost its library, and a later save can replace the unreadable manifest. Preserve a quarantined copy and show the fallback reason.
- `SettingsStore` validates root disjointness on save, but loaded settings are not passed through `requireDisjointRoots()`. Hand-edited or older invalid files can bypass that invariant on startup.
- Entity profiles are opaque JSON strings through the API. The server accepts their content without validating the UI's enum, boolean, numeric-range, or object schema. Keep unknown extension fields if desired, but validate known fields and a schema version at the write boundary.
- Browser preferences accept any parsed JSON as the generic type. Enum values get some validation; numeric/boolean values need equivalent finite/type/range checks. Valid JSON is not necessarily valid application state.

Use distinct policies for regenerable caches and authoritative curation. Rebuilding a cover cache is safe; silently dropping edits or malformed profile fields is not.

### F24 — Durability needs a narrower, testable contract

**P2 · Risk/tradeoff, not a reproduced power-loss failure.** Locations: `ops/Journal.java:114–120`; `ops/Trees.java:85–90,97–104`; `settings/SettingsStore.java:41–44`; `settings/Libraries.java:108–111`; `backend/src/main/resources/application.yml` datasource URL.

Three separate guarantees are currently easy to conflate:

1. **Process-crash recovery.** Journaled phases and verified outputs provide significant protection, but F01/F02 show incomplete external-change handling.
2. **Source unchanged since reading.** `Trees.stamp()` uses names, sizes, and millisecond mtimes. Same-size timestamp-preserving edits are indistinguishable. This is weaker than proving the current source equals the verified input.
3. **Power-loss durability.** Journal temp files are forced before atomic rename, but directory-entry durability is not explicitly handled. Copied file contents are forced; staging/result directory operations need platform-specific treatment. Settings/library-manifest replacements also do not force their temporary contents. Process-level fault injection does not prove behavior after power loss or storage-cache reordering.

H2 uses `WRITE_DELAY=1200`, deliberately batching persistence of metadata transactions. This is a separate tradeoff for irreplaceable edits, ratings, aliases, and marks, unlike the regenerable Tupl cover cache. H2 documents WRITE_DELAY as the maximum delay before writing its log; it should not be interpreted as a universal 1.2-second power-loss guarantee for every storage device. [H2 SET WRITE_DELAY](https://h2database.com/html/commands.html#set_write_delay).

Document the actual guarantees. Evaluate stronger durability for explicit user saves, platform-appropriate directory synchronization, and fault tests on the supported filesystem/storage combinations. Preserve verified results whenever recovery cannot prove safe disposal. Add coordinated backup/restore for metadata, settings, and pending operations; a casual copy of live H2 split files is not a consistent backup strategy.

### F25 — Recovery failures and partial failures need durable visibility

**P2 · Source-confirmed.** Locations: `ops/Steps.java:142–174`; `ops/FileOps.java:53,107–135`; `index/ScanService.java:205–209`; `frontend/src/app/core/store.ts:258–262`.

Recovery catches failures and leaves items journaled, which is appropriately conservative. However, startup can continue after the recovery attempt, and the UI has no durable recovery inbox explaining blocked items, preserved results, required credentials, or manual decisions. A log entry is insufficient for a user whose source may have moved or partially transformed.

Operation progress also has a problematic outer failure path: planning for an item occurs outside the per-item catch, and the `finally` block publishes a result even after an unexpected exception/Error. There is no explicit terminal “failed before completion” field; an empty failed list can yield an optimistic success toast with zero done. A scan ending with a failed message similarly reaches the frontend's generic “Scan finished” success toast.

Model terminal state explicitly: succeeded, cancelled, failed, partially completed, and recovery required. Persist enough recent operation status to recover it after disconnect. Report unreadable/skipped album counts separately from successful indexing. Prevent new scans or overlapping operations from obscuring unresolved recovery paths, or mark those paths unavailable until resolved.

### F26 — Timeouts do not make readers and workers quiescent

**P2 · Source-confirmed invariant gap; no native use-after-free established.** Locations: `album/AlbumService.java:254–281`; `ops/Steps.java:103–123`; `index/ScanService.java:285–294`; `ops/FileOps.java:87–98`; `web/UndertowServer.java:125–135`.

`quiesce()` marks an album busy and waits for readers, but after five seconds returns even if readers remain. The operation proceeds. On Windows, still-open handles may cause rename/delete failures; on Unix, readers may continue through old unlinked handles. “Waited for five seconds” is not the same state as “no readers exist.”

Shutdown similarly waits a bounded time for workers and may continue closing stores after the deadline. Native calls and slow/network filesystem operations are not guaranteed to honor interruption promptly. A lingering worker can then interact with closing services. This is a lifecycle risk, not proof of an arena leak or JVM memory corruption.

Represent timeout as a refusal/deferred operation, or continue only under a protocol designed for live readers. Close the mutation admission gate before draining work. Make shutdown ordering explicit and log structured evidence of outstanding tasks. Test a deliberately blocked reader/decoder and restart with work still pending. The normal UI Exit refusal during file operations is a useful safeguard to retain.

### F27 — Native recovery and compatibility diagnostics need consistency

**P3 · Source-confirmed.** Locations: `natives/LibraryLoader.java:35–82`; `image/TurboJpeg.java:36`; `image/Thumbnailer.java`, constructor; `docs/natives.md`.

Existing cached native files are trusted by `path()`. JNI `load()` retries after a failed load if the cached file checksum is wrong. TurboJPEG uses `path()` directly with FFM, so a damaged cached TurboJPEG binary has no corresponding reinstall path and can prevent startup until the cache is repaired manually.

Verify/reinstall on FFM lookup failure or checksum each native on first use in a process. Keep cache validation and ownership checks in one loader path. Do not accept arbitrary temp-directory binaries merely because their names match when manifest loading failed; fail explicitly if platform metadata is unavailable.

AVX2 on x86_64 and glibc requirements are documented support constraints, not newly discovered regressions. Startup diagnostics could explain them before native work fails. A baseline build/fallback is a product choice, not automatically worth its maintenance cost.

No definite persistent native-arena leak was found. TurboJPEG buffers/handles use finally blocks; ordinary render arenas/containers are scoped, and the 7-Zip shared-arena lifetime is tied to the caller's arena. Global native-library lifetime is intentional. Remaining confidence requires repeated render/restart stress with direct-memory, RSS, native-memory, and handle-count measurements.

## Frontend state, workflow, and usability

### F15 — Photo marks have no shared version and no cross-client update event

**P2 · Source-confirmed; stale-load behavior reproduced with controlled responses.** Locations: `web/ws/PhotosApi.java:59–67`; `catalog/Catalog.java`, `mark()` around line 289; `frontend/src/app/ui/album-viewer.ts:264–275,390–400`.

Marks do not bump album revision. A mark reply updates the requesting viewer, but the backend publishes no corresponding photo-mark event to other clients. Other tabs can remain stale indefinitely. Replacing entire favorites-list sets also permits one stale client to overwrite another client's additions.

The frontend's load generation only orders loads against other loads. A deferred older album reply can replace details after a successful mark reply, restoring stale marks at the same album revision. The probe observed rating 7/list 2 become an empty marks map. Whether that exact same-tab response order occurs with the current Undertow socket scheduling was not established; the missing cross-client event and replacement-write conflict are independent issues.

Add committed mark versions and targeted invalidation/update events. Prefer add/remove-list operations or version-checked patches over replacing a whole stale set. Reconcile album reloads with pending mark mutations and reject older mark snapshots. Test two tabs, reconnect, and a reload concurrent with a mark.

### F16 — Queued photo actions can follow the next album

**P2 · Source-confirmed.** Location: `frontend/src/app/ui/album-viewer.ts:390–397`.

The mark queue captures names, but obtains `this.album()` only when the queued action begins. Angular can reuse the viewer component for another album route. If a previous mark is pending, the next queued action can therefore target the new album using names selected from the old album. Common names such as `001.jpg` make a wrong-target mutation possible rather than merely a “not found” error.

Bind album ID and library generation at enqueue time, as for F04. Do not reconstruct action ownership from current component state. Cancel unsent work on route destruction only if the UI clearly communicates that cancellation; otherwise finish it against the original album.

### F17 — Reconnect refreshes only part of the application's state

**P2 · Source-confirmed.** Locations: `frontend/src/app/core/store.ts:265–267`; `ui/album-viewer.ts:255–260`; `ui/photo-list.ts:90–97`; `backend/src/main/java/thumbnailer/web/ws/AlbumSocket.java:121–126`.

Reconnection refreshes settings, facets, search, folders, and catalog pages. It does not obtain current scan/operation status or replay the terminal event missed during disconnection. Viewer and photo-list subscriptions ignore connection events. An open viewer can retain stale entry indices after a disconnected reindex; a job can appear to run indefinitely after its completion event was missed. Settings use scan state to disable actions, so stale progress can also block the UI.

Provide a bootstrap/reconnect snapshot containing library identity, active job state, and recent terminal result. Reload active routes against that identity and close or redirect unavailable ones. The same snapshot can solve F05's write-ownership handshake. An event cursor/replay system is optional; a consistent current-state snapshot is the smaller first step.

### F18 — Catalog cache invalidation does not cover in-flight writes to state

**P2 · Reproduced in service probes.** Locations: `frontend/src/app/core/catalog-store.ts:39–49,77–91,154–163`; `ui/shared/entity-picker.ts:125,141`.

Clearing page caches or calling `reset()` does not invalidate pending callbacks that call `remember()`. An old page can repopulate `known` after reset, or overwrite a newer name after rename/merge. `ensureKnown()` reports gone IDs but does not evict their old objects; the Recent picker can continue offering those objects and fail when the user acts on them.

Use one catalog/library generation for all asynchronous state writes, including `remember()` and individual lookups. Remove gone IDs from known entities, recent selections, and filters. Guard cache deletion by promise identity so a failed old request cannot evict a newer request for the same key.

Actual already-sent requests are rejected on socket close, so this is not a claim that every library switch necessarily resurrects an old page. The deferred-response tests prove the missing boundary; same-library invalidation is sufficient to encounter stale state.

### F19 — Favorites-list mutations use unstable object identity

**P2 · Source-confirmed.** Locations: `frontend/src/app/ui/photo-list.ts:95,101–108,145–155`.

A mutation retains an item object, while a concurrent refresh replaces the list with new item objects. Rating updates use `x === p`; removal uses `x !== p`. After a refresh during the request, a successful server removal can leave the replacement item visible while decrementing the total. Reconcile with the existing stable album/name key, and bind the action to list identity/generation.

Refresh also reloads at most 500 items even if the user paged farther. Preserve the visible anchor/range or explicitly reset pagination; silently dropping the loaded tail makes large lists jump. This is a separate issue from the server's sensible per-request page cap.

### F20 — Viewer entry 5,001 has no normal access path

**P2 · Source-confirmed.** Locations: `backend/src/main/java/thumbnailer/web/ws/AlbumSocket.java:61,200–204`; `frontend/src/app/ui/album-viewer.ts:233–240,283–284`; Gateway/Request album-detail contract.

The backend truncates album details to 5,000 entries. There is no entry pagination operation. The viewer treats the returned entries as its complete dataset. A 6,000-image indexed album consequently has 1,000 images unavailable for normal viewing, selection, cover choice, and copy. A favorite linking to an index beyond the cap cannot open normally through the viewer deep link.

Add entry pagination or a separate bounded image-list API. Keep stable identity across pages and expose total/truncation explicitly. If 5,000 is an intentional supported limit, enforce and explain it when indexing/opening instead of silently returning a partial album.

### F21 — Temporary failures can leave persistent broken images

**P2 · Source-confirmed; no visual runtime claim.** Locations: `frontend/src/app/ui/album-card.ts:14`; `ui/album-viewer.ts:86,249–253`; `ui/viewer/lightbox.ts:23`; `backend/src/main/java/thumbnailer/web/ImageController.java:111–127`.

The backend intentionally responds with 503 and Retry-After while an album is busy. The image components do not implement a corresponding bounded retry mechanism. A failed image with an unchanged URL may remain broken or loading until remounted. The lightbox also lacks a useful explicit error/retry state.

Reset image load state on URL change, distinguish transient failures from missing/unreadable files, retry a small bounded number of times, and provide a Retry action. A plain image element does not implement the application's Retry-After policy on its own.

The slideshow advances by elapsed timer time rather than successful display. On a slow archive it can move on before the photo becomes visible. Start the interval after load, with an explicit timeout/skip policy for failed images, and keep prefetch bounded.

### F22 — Hiding duplicates can hide every matching result

**P2 UX policy issue · Query behavior confirmed.** Locations: `backend/src/main/java/thumbnailer/store/jdbc/JdbcAlbumRepository.java`, duplicate predicate around line 421; `frontend/src/app/core/store.ts:49`; `core/prefs.ts:29`.

When duplicates are suppressed, the representative is chosen globally among same-visibility albums, independently of the other filters. If the earliest copy is outside the selected folder or fails the search/rating filter, a later matching copy can still be suppressed. The user sees no result even though a matching album exists in the selected scope.

The current “first indexed” policy is documented and mirrored in the mock, so this is not a mock/backend mismatch. Prefer choosing one representative from the filtered candidate set, or clearly expose that global representatives may reside elsewhere. Test folder, text, rating, hidden, and duplicate-view combinations.

Hidden copies intentionally contribute to copies counts because their files remain on disk. That behavior was checked and is not itself a defect. The more fundamental candidate-fingerprint ambiguity is F09.

### F29 — Keyboard behavior needs an accessible focus model

**P2 · Source audit; not a formal accessibility conformance result.** Locations: `frontend/src/app/ui/tile-grid.ts:22–39`; `ui/shared/context-menu.ts:109` and keyboard handlers; `ui/viewer/lightbox.ts:88`.

The virtualized grid renders generic div tiles and changes visual selection while focus stays on the host. It does not expose a coherent named collection, active descendant, and selected-state relationship. The context menu likewise changes a visual active index while keeping focus on its container without a corresponding active-descendant or roving-item focus model. The lightbox focuses its overlay but lacks modal semantics and an explicit focus boundary, allowing interaction with obscured content.

Implement one consistent keyboard/assistive-technology model per widget. Preserve native dialog behavior where it is already used for confirmation; apply equivalent modal behavior to the lightbox. Expose item names, position/count where useful, selection state, and restored focus. Validate with keyboard-only use and a screen reader in an actual browser. Do not infer accessibility from the number of implemented keyboard shortcuts.

## Dependencies, maintainability, and extensibility

### F28 — A nested development dependency is still vulnerable

**P2 development dependency; the audit includes a high-severity advisory, but application exploitability was not established.** Locations: `frontend/package-lock.json:110,218`; evidence: [npm audit JSON](build/audit/frontend/npm-audit.json).

`@angular/build` 22.2.2 brings a nested Vite 8.3.0. The separate top-level Vite is 8.3.4, so inspecting only that copy misses the affected one. The audit reported two affected dependency nodes, with six advisories covering editor-command handling and development-server request/file handling. The reported 8.3 branch fixes start at 8.3.3.

| Official advisory | Scope/conditions |
|---|---|
| [GHSA-587h-7wmj-h5pm](https://github.com/vitejs/launch-editor/security/advisories/GHSA-587h-7wmj-h5pm) | High: Windows existence-check bypass, relevant with editor command handling |
| [GHSA-698v-4r3x-3vcv](https://github.com/vitejs/launch-editor/security/advisories/GHSA-698v-4r3x-3vcv) | Windows percent escaping |
| [GHSA-r825-w8mm-5pxw](https://github.com/vitejs/launch-editor/security/advisories/GHSA-r825-w8mm-5pxw) | Editor argument interpretation; editor/environment prerequisites |
| [GHSA-9jrq-w75r-8gcw](https://github.com/vitejs/vite/security/advisories/GHSA-9jrq-w75r-8gcw) | Cross-origin execution with particular custom middleware conditions |
| [GHSA-rq7h-c2jc-7f22](https://github.com/vitejs/vite/security/advisories/GHSA-rq7h-c2jc-7f22) | Filesystem allow-list bypass for particular non-Windows path layouts |
| [GHSA-vfpm-58rq-9qcg](https://github.com/vitejs/vite/security/advisories/GHSA-vfpm-58rq-9qcg) | Four-byte disclosure outside configured filesystem access |

Update to a compatible Angular build version carrying patched Vite, or validate a narrowly scoped dependency override. Re-run build, tests, and the audit against the complete installed tree. Do not blindly apply the audit's proposed forced downgrade of Angular build to 22.1.9. These are development-tool advisories, not evidence that the packaged Spring application exposes those endpoints. No exploit was attempted.

### F30 — Type and documentation drift undermine otherwise useful boundaries

**P3 · Source-confirmed.** Locations: `frontend/tsconfig.json`; `frontend/src/app/core/model.ts`; `core/ws-gateway.ts`; `SPEC.md`; `PLAN.md`; `backend/src/main/java/thumbnailer/image/Thumbnailer.java`.

TypeScript `strict` and Angular `strictTemplates` are absent. The protocol is maintained in backend records, TypeScript interfaces, mock behavior, and architecture prose, while gateway responses use broad casts. For example, frontend `Album.cover` is a required string, but the backend permits a null cover when no candidate decodes. Runtime truth and static types should agree.

Adopt stricter checks incrementally at transport/state boundaries. Add a small set of shared wire fixtures or schema-generated contract checks, including nulls, partial replies, unknown message types, limits, and error shapes. Avoid four manually synchronized sources of semantic truth where one executable contract can help.

Documentation also contains stale claims:

- SPEC M5 says AlbumPatch has no date, but editable dates exist.
- SPEC C2/N6 and parts of PLAN still describe a hardcoded cover chain; `CoverSelector` uses beans plus ServiceLoader.
- PLAN says tombstones are never purged, but Optimize purges old ones.
- A PLAN decision says the derived encryption key is persisted, while current settings code keeps it in memory only.
- Thumbnailer comments say the pipeline copies nothing between stages; native/heap transfers and fallback conversions do exist.

These inaccuracies can cause redundant work or incorrect security/performance assumptions. Reconcile current facts and separate historical decisions from active contracts. The map/router is valuable; reduce repeated inventories rather than adding another duplicate architectural narrative.

## Data-model assessment and recommended evolution

### Current sources of truth

| Data | Current owner/storage | Important invariant |
|---|---|---|
| Original image/archive bytes | User filesystem | Scans/browsing must not mutate them; destructive operations require proof and recoverability |
| Album identity, revision, effective facts | H2 `album` | Stable identity through edits/moves; revision identifies a particular state |
| Extracted versus edited metadata | H2 JSON layers plus effective columns | Null means inherit; explicit empty overrides extraction |
| Image entries | H2 `entries.data`, custom packed format | Position is transient; names currently anchor marks |
| Deleted/restorable identity | H2 `removed` | Adoption preserves curation, but only with credible identity evidence |
| Entities and aliases | H2 `entity`, `alias` | Alias uniqueness per kind; stable explicit kind codes |
| Derived album credits | H2 `album_link`, roles 1–5 | Match effective album text and counted visibility |
| Curated album favorites | H2 `album_link`, role 6 | Preserve explicit list membership; never rebuild it from album text |
| Curated entity relationships | H2 `entity_link` | Parent policy and acyclicity must hold after every mutation, including merge |
| Photo ratings/favorites | H2 `photo`, `photo_link` | Mutations need their own version and cross-client visibility |
| Covers | Tupl by default, MVStore alternative | Regenerable cache; bytes must be tagged with the state actually rendered |
| Settings and library selection | JSON files | Validated configuration, atomic publication, recoverable authoritative state |
| Unfinished file operations | Journal plus manifests | Enough durable evidence to distinguish safe continuation from ambiguity |

### What to preserve

The two-layer metadata model is appropriate: rescanning should refresh extracted facts without erasing edits. Explicit empty values matter. Keep their semantics visible in API tests.

Compact integer catalog keys and sparse photo marks avoid multiplying long album IDs across every relationship. Explicit `EntityKind.code()` values are better than persisting enum ordinals. The packed entries format can be a sensible space optimization; the immediate problem is its access pattern, not proof that it should be removed.

H2 transactions already combine album, entries, links, count deltas, tombstone consumption, and renamed marks. Deterministic entity update ordering reduces deadlock risk. Parameterized SQL and escaped path-prefix predicates are good. The sorted-page indexes match the application's sort expressions; do not add or remove them merely by counting indexes.

The separation between metadata and regenerable covers justifies different durability policies. Tupl NO_REDO is not itself an authoritative-data-loss defect. Its quarantine/reopen behavior is useful. MVStore, however, does not provide equivalent cache-error isolation; either document it as a less resilient alternative or align the BlobStore contract's failure behavior.

### Boundaries that need strengthening

1. **Final identity must determine locking.** Content candidates can locate an entity, but cannot substitute for the entity's mutation lock. F06 demonstrates the consequence.
2. **User mutation needs an expected version.** Album stripes prevent concurrent execution, but an old full card/profile/list replacement can still overwrite newer intent. Entity/profile and photo-mark changes need patch semantics or optimistic versions, especially across tabs.
3. **Rendered bytes need an immutable source descriptor.** Use source identity/version, entry identity, and render spec captured with the render. Do not derive cache identity from a mutable later album.
4. **Hierarchy is a domain constraint.** Same-kind curated links currently encode parents. Keep that compact model if it serves the product, but make all transformations enforce it. A typed parent relation/column becomes justified if multiple relation types need different cardinality or traversal rules.
5. **Derived counts need an audit/repair path.** Existing recounting is valuable. Add invariant diagnostics for orphan links, invalid kinds, parent cycles, missing entry blobs, and count mismatches. Do not blindly cascade-delete links retained for tombstone revival.
6. **The `root` column is not fully protected.** `AlbumService.retainRoots()` calls `repo.reroot()` without taking album stripes; a concurrent whole-row patch can restore an older root. Path-based queries limit the harm, and a later scan repairs it, but root/status data can become inconsistent. Use a stripe-protected reassignment or avoid rewriting root during unrelated metadata patches.

Do not normalize every profile enum into a new table merely to save a few bytes. At a few thousand catalog entities, validation and query requirements matter more than JSON storage overhead. Add typed/indexed fields when real filters require them, preserve unknown extension fields intentionally, and migrate with a schema version.

## Performance and optimization plan

### Prioritize repeated work before lower-level tuning

| Priority | Opportunity | Expected mechanism | Measurement before/after |
|---|---|---|---|
| 1 | Bound encoded bytes, decoded pixels, extraction buffers | Prevent RSS/heap spikes and workload collapse | Peak RSS, heap/native allocation, rejected work, p95 latency |
| 2 | Cache immutable decoded entry lists | Remove repeated full-blob reads and Entry allocation | SQL reads and allocated bytes per thumbnail; 100/1,000/5,000-entry albums |
| 3 | Bound/coalesce broadcast backlog | Prevent retained events and slow-client interference | Queue depth/age, scan throughput, delivery latency with a slow client |
| 4 | Stream legacy migration | Limit upgrade memory to an album/batch | Peak heap and restartability on a synthetic large legacy DB |
| 5 | Remove unnecessary native/heap copies on measured paths | Reduce memory bandwidth and allocation | JFR allocation and stage timings by format |
| 6 | Avoid repeated totals/full refreshes | Reduce large filtered-query work | H2 EXPLAIN, query p95, count time vs page time |
| 7 | Index visible layout rows | Reduce per-scroll O(loaded tiles) work | Browser frame time at 1k/10k/50k loaded tiles |
| 8 | Tune threads and storage lanes | Match CPU, memory, and physical disk | Throughput plus viewer p95 under scan, on HDD and SSD separately |

No percentage speedup is promised here. Existing benchmark figures in the repository were not reproduced during this review.

### Conversion and allocation audit

`Thumbnailer.decode()` copies native encoded data to a Java byte array for non-JPEG/fallback paths. WebP then decodes into a Java pixel array. TurboJPEG decodes into native output and copies to `int[]`; JPEG encoding copies pixels back into native memory. This means “one pixel layout” is accurate as a representation goal, while “zero copy everywhere” is not.

Some copies are deliberate and reasonable: keeping long native calls off critical downcalls avoids safepoint problems, and a final small thumbnail pixel copy may be insignificant beside entropy decoding. Measure before building a complex native-pixel abstraction. The more attractive first targets are full-size WebP decoding, large fallback byte-array duplication, 7-Zip member buffering, and ZIP CRC buffering.

Do not silently weaken verification to improve packing throughput. One source read plus an independent result readback serves a different purpose from redundant conversion. Preserve that distinction in benchmarks.

### SQL and data-structure tuning

- `search` currently obtains a total count for every page. Consider returning the total on the first page and refreshing it on invalidation; measure heavily filtered counts first.
- OFFSET pagination costs work proportional to skipped rows even with a matching index. Keyset pagination is a candidate for deep browsing, with deterministic ties and a defined response to concurrent edits. It is not an automatic replacement for every query.
- Text search is a scan-oriented design. Introduce full-text indexing only after workload evidence shows it dominates and after defining token/substring/alias semantics.
- Album saves update effective text/JSON and catalog-derived data. Profile frequent rating/flag changes before splitting updates; preserve transaction and revision invariants.
- `TileGrid.visible()` filters every placement each scroll. DOM virtualization bounds mounted elements, but not all CPU work. Row offsets/binary search can reduce it without replacing the layout engine.
- Catalog `known` grows as entities are visited and clones its Map on updates. Establish a realistic expected entity count; use bounded page data and a small selected/recent identity cache if measurements justify it.

### Thread tuning and I/O admission

`index-threads` is per disk, while rendering has separate cover/thumb limits. More CPUs or more roots can increase concurrent buffered data substantially. Auto-sized thread limits should be constrained by the byte/pixel budget, not CPU count alone.

Most thumbnail reads use disk lanes, but original-image streaming and copy comparison do not consistently use the same admission path; container opening/listing can also occur before acquiring the render lane. These paths should be included in HDD responsiveness measurements. Avoid holding two disk lanes in opposite orders when comparing albums on different volumes.

Keep the existing sequential archive gallery. Returning to per-photo extraction would reintroduce repeated prefix decompression. Test random jumps, reverse navigation, two viewers on the same archive, cancellation, and eviction rather than optimizing only a forward sequential gallery.

Use a benchmark matrix with cold/warm caches, HDD/SSD/network paths, ordinary and solid archives, many small and a few huge images, JPEG/WebP/PNG/TIFF, missing/corrupt entries, and concurrent scan/view/edit activity. Report p50/p95/p99, allocations, disk bytes, and memory high-water marks; throughput alone can hide unusable latency.

## Simplicity and expandability

### Simplify duplicated state ownership

The principal complexity is not excessive interfaces. It is that album revision, photo marks, library identity, route identity, pending mutations, catalog generation, and job progress each have partial freshness rules. Several bugs arise where those rules meet.

Use a small number of explicit mechanisms:

- A library-scoped connection bootstrap.
- Identity-bound mutation queues.
- Versioned authoritative entities/marks.
- Immutable render-source identity.
- Recovery records that carry ownership and source proof.

These should replace ad hoc guards, not add another parallel state manager. The Gateway and repository ports are useful test seams; the SPI has real implementations and should not be removed merely because interfaces add files.

### Keep native and platform costs explicit

The custom Undertow integration, native loader, six-platform binary bundle, custom packed codec, H2 migration code, and operation journal each impose an ongoing compatibility burden. They may be justified, but they need executable upgrade tests and clear ownership.

Do not replace Undertow as an audit reflex: the repository records it as an explicit product choice. Add lifecycle/HTTP/WebSocket smoke tests for the supported Boot/Undertow combination. The custom direct buffer pool should have explicit verified ownership and closure on normal shutdown and startup failure; this review did not establish a sustained leak, so treat repeated-restart RSS/handle testing as required evidence rather than declaring one.

The plugin mechanism isolates normal provider exceptions, but it is not a sandbox or time budget. A blocking extractor can hold an indexing worker. Define plugins as trusted in-process code, document container/thread ownership, and expose slow-provider diagnostics. Add process isolation only if loading untrusted third-party plugins becomes a real requirement.

### Avoid speculative rewrites

- Keep the custom resampler unless comparative measurements justify replacement.
- Keep separate metadata and cover stores; changing engines does not repair identity or cache tagging.
- Retain the mock for development, but add contract fixtures to prevent semantic drift.
- Keep benchmarks as standalone tools; they are not production runtime bloat.
- Prefer bounded caches and streaming groups over a new distributed queue or generalized job framework for this local application.
- Break large orchestration methods by stable responsibility only where it makes invariants easier to enforce; moving the same branching into many tiny wrappers is not simplification.

## Missing capabilities and workflow improvements

These are product/operational gaps, not all implementation bugs:

| Capability | Why it matters | Smallest useful next step |
|---|---|---|
| Coordinated backup/restore | Curation is irreplaceable even when covers can regenerate | Supported consistent metadata backup plus settings/journal inventory; restore verification |
| Recovery inbox | Users cannot act on log-only unfinished operations | List pending item, phase, preserved paths, reason, and safe retry/manual resolution |
| Entry pagination | Large albums are currently truncated | Page entries with total and stable entry identity |
| Job snapshot/history | Reconnect loses progress and terminal outcomes | Current job plus last result in bootstrap |
| Conflict-aware editing | Multiple tabs can overwrite stale full replacements | Expected versions and explicit conflict resolution for entities/marks/settings |
| Corrupt-file diagnostics | A scan can finish while individual images were skipped | Exportable per-album failures with retry after repair |
| Contextual facets | Current suggestions are global | Query-keyed facet requests with generation guards |
| Deep integrity verification | Size/mtime fingerprints cannot establish byte identity | Optional targeted/background verification and an ambiguity report |
| Native support diagnostics | Unsupported CPU/OS or cache damage can prevent startup | Clear platform report, cache repair action, tested supported-platform matrix |
| Automated release checks | Existing local tests do not establish packaging/restart safety | CI for unit/build/contract checks, fixture-only native smoke, dependency inventory |
| Accessibility verification | Keyboard shortcuts do not establish usable focus semantics | Real-browser keyboard and screen-reader checks for grid, menus, dialogs, viewer |

Multi-volume archives and archive-password support remain distinct from the implemented `.cry` image format. Treat them as explicit feature scope rather than assuming `.cry` support solves encrypted archive handling.

Local-only binding and restricted WebSocket origins are appropriate for the current application. If remote/LAN access is introduced, authentication and authorization must be a real design change; changing `server.address` alone would not provide them. No broad SQL injection or Angular template-XSS defect was established in the reviewed paths.

Session-only encryption keys do not imply that every decrypted derivative vanishes on session end. Covers can persist and browser image responses are publicly cacheable for a day. If the intended privacy promise includes locking already rendered thumbnails, define and test cache behavior explicitly; this is a policy gap, not proof that the current README promises encrypted thumbnail storage.

## Remediation sequence and acceptance criteria

### First: prevent wrong deletion and wrong-target mutation

1. Repair F01/F02 with recovery semantics safe after partial disposal. Acceptance: changed sources and sole surviving verified results are always preserved; old manifests fail closed when proof is missing.
2. Repair F03 on Windows and test destination collisions at planning, install, and recovery, including different casing and case-only renames.
3. Bind queued card/photo edits to their original identities (F04/F16).
4. Add library-scoped write admission/reconnect bootstrap (F05/F17).
5. Repair revival locking and immutable cover provenance (F06).

### Next: restore reliable domain and UI state

6. Protect unreadable subtrees from pruning and reject cyclic merges (F07/F08).
7. Enforce image revisions and add stale-image/retry handling (F10/F21).
8. Version/broadcast photo marks; repair catalog generations and favorites reconciliation (F15/F18/F19).
9. Resolve silent 5,000-entry truncation (F20) and clarify duplicate filtering (F22).
10. Surface incomplete recovery and failed terminal states (F25), then make read-drain failures explicit (F26).

### Then: establish resource and operational limits

11. Add byte/pixel budgets and stream/spool large archive units (F11).
12. Bound broadcast backlog and decoded-entry caching (F12/F13).
13. Stream legacy migration, validate serialized state, and provide supported backup/restore (F14/F23/F24).
14. Update the affected development dependency, then add ongoing dependency checks (F28). This can proceed independently of the application fixes.
15. Measure remaining SQL/UI/native conversion costs before changing storage formats or algorithms.
16. Complete accessibility, stricter contract checks, and documentation reconciliation (F29/F30).

### Regression matrix worth maintaining

| Area | Adversarial case | Required invariant |
|---|---|---|
| File operations | Crash at every phase, then modify input | No changed source is deleted; no sole verified copy is rolled back |
| File operations | Partial source deletion, then restart | Remaining source plus result remain recoverable |
| Filesystem | Target appears with different case after planning | Unrelated target is never replaced |
| Scan | AccessDenied, unplugged root, cancelled walk | Unseen is not automatically absent |
| Identity | Same names/sizes, different image bytes | Candidate grouping cannot silently assert verified identity |
| Database | Revival concurrent with cover pin/edit | Bytes and tag refer to the same source; revision does not regress |
| Catalog | Merge ancestor into descendant | Parent graph stays acyclic |
| UI | Navigate while multiple saves are queued | Every mutation retains original library/entity/album ownership |
| Reconnect | Switch libraries while disconnected | No old-library mutation is accepted in the new library |
| Marks | Two tabs add/remove different favorites | Concurrent intent is preserved or a conflict is surfaced |
| HTTP | Old numeric index after reindex | Old URL cannot return another entry as if current |
| Capacity | Huge declared member/image dimensions | Predictable bounded failure rather than process exhaustion |
| Migration | Large legacy table under small heap | Memory bounded by batch; restart safe |
| Lifecycle | Reader/native call outlives timeout | Stores and operations do not assume it finished |
| Accessibility | Keyboard and screen reader navigation | Focus, selection, active item, and modal scope are announced correctly |

## Evidence appendix

### Scratch backend probes

The temporary Gradle init script adds ignored scratch sources to the backend test source set for that invocation only:

- [Init script](build/audit/audit.init.gradle).
- [Operation recovery fixture/probes](backend/build/audit/src/thumbnailer/ops/AuditFileOpsTest.java).
- [Transactional catalog/cover probes](build/audit/src/thumbnailer/store/jdbc/AuditCatalogRegressionTest.java).
- [Windows destination probe](build/audit/src/thumbnailer/ops/AuditPathTest.java).
- Saved results: [move recovery](build/audit/disposing-recovery-result.xml), [encryption recovery](build/audit/crypt-recovery-result.xml), [catalog/cover](build/audit/catalog-result.xml), [Windows destination](build/audit/path-result.xml).

```powershell
$env:JAVA_HOME='C:\work\thumbnailer\devtools\jdk-27'
.\gradlew.bat -PskipFrontend -I build/audit/audit.init.gradle :backend:test --tests thumbnailer.ops.AuditFileOpsTest.recoveryMustKeepSourceChangedAfterDisposingWasJournaled --console=plain
.\gradlew.bat -PskipFrontend -I build/audit/audit.init.gradle :backend:test --tests thumbnailer.ops.AuditFileOpsTest.cryptRecoveryMustKeepSourceChangedAfterDisposingWasJournaled --console=plain
.\gradlew.bat -PskipFrontend -I build/audit/audit.init.gradle :backend:test --tests thumbnailer.ops.AuditPathTest --console=plain
.\gradlew.bat -PskipFrontend -I build/audit/audit.init.gradle :backend:test --tests '*AuditCatalogRegressionTest' --console=plain
```

The first three probes assert desired safety and failed on the reviewed revision. The catalog probes assert the observed bad outcome and passed. Convert them into clean regression tests asserting correct behavior when implementing fixes; do not count their diagnostic passes as correctness coverage. The Windows probe skips where the filesystem is case-sensitive. The move recovery fixture forces the copy path; it does not substitute for a real cross-volume test.

### Scratch frontend probes

- [State runner](build/audit/frontend/reproduce.cjs) and [output](build/audit/frontend/reproduce-output.json).
- [Reconnect runner](build/audit/frontend/reproduce-ws.cjs) and [output](build/audit/frontend/reproduce-ws-output.json).
- [Dependency audit](build/audit/frontend/npm-audit.json).

```powershell
node build/audit/frontend/reproduce.cjs
node build/audit/frontend/reproduce-ws.cjs
```

These transpile actual source in memory and control framework/transport responses. They demonstrate service logic, not rendered-browser behavior. The runners target this checkout at `C:/work/thumbnailer`; the state runner also accepts an alternate repository path argument. The captured output is the evidence from this run. Build-directory evidence is local and may be removed by a clean; this report retains the scenarios, outcomes, and source references so the findings remain understandable independently.

### Findings deliberately not promoted into defects

- No sustained native leak, invalid FFM lifetime, or confirmed native memory corruption was established.
- No claim was made that Tupl's regenerable-cache durability setting loses authoritative metadata.
- Hidden albums contributing to copies counts is intentional.
- The existing archive duplicate-name/link/size validation and output readback are valuable; this report does not suggest removing them for speed.
- No blanket assertion was made that custom resampling, packed entries, repository interfaces, or the mock are overengineering.
- The frontend's small timer/frame cleanup gaps are bounded callbacks, not evidence of an unbounded leak.
- Power-loss safety, production-size performance, dependency exploitability, and screen-reader usability require the additional validation described above.
