# Thumbnail store evaluation — which embedded KV store backs `BlobStore`

[↑ INDEX](../INDEX.md) · harness and method: [kvbench/README.md](../kvbench/README.md) · generated tables:
[REPORT.md](../kvbench/results/REPORT.md) (100 000 values) · [REPORT-400000.md](../kvbench/results/REPORT-400000.md)

## Requirements (from the brief and its follow-ups)

- **Platform:** pure Java (no JNI, no native libraries), running on JDK 26.
- **Capacity:** at least 100 000 values of 50–100 KiB (mean about 80 KiB, JPEG, so incompressible).
- **Space:** disk overhead as low as possible, ideally ≤ 5–6 %.
- **Reliability:** a crash must not corrupt anything or lose synced writes. Thumbnails can be regenerated, so losing the
  last few seconds is acceptable.
- **Speed:** weighted 60 % read, 35 % write, 5 % delete.
  - Reads matter most and are random, out-of-order lookups of different keys. Overwrites of existing keys matter too.
  - Writes come from one thread, rarely 2–3. About 50 MB/s single-thread is already enough.
  - Space, heap and reliability decide among the stores that are fast enough.

## Verdict — final ranking

Applied criteria:
- **Gates:** pure Java; reliable (0 verify errors, both hard kills survived, no starvation); steady-state overhead
  ≤ 6 %; single-thread writes ≥ 50 MB/s.
- **Ordering:** 60 % read, 35 % write, 5 % delete. Licence is a caveat, not a criterion.

| rank | store | gates | why |
|---|---|---|---|
| **1 — winner** | **TuplDB 1.9.1 with CRC32C page checksums** | all pass | The only reliable pure-Java library that meets every gate. **Space:** 1.2–1.3 % after load, 2.3 % after overwrites plus its stepped `compactFile`, and 1.2 % at 30 GiB. Writes 240–295 MB/s, 53–88 MB heap plus a configurable off-heap cache. Every page is checksummed and verified on read (review grade A−), and it is actively developed. **Caveats:** cold reads are ≈ 2.2 k/s, about half of JE, because an 80 KiB value is about 20 page reads. Between compactions, overwrites leave up to about 22 % free pages. A fast bulk load parks gigabytes in the redo log until checkpoints catch up. **AGPL-3.0**, one maintainer, FFM flags needed. Plain TuplDB measures the same but does not verify data on read, so take the CRC variant. |
| **2 — fallback** | **Berkeley DB JE 18.3.12** | fails space only (10.3–10.9 % worst case) | Fastest pure-Java reads: ≈ 4.6 k/s cold single-thread, 2–2.8× TuplDB, and ≈ 19 k/s with 8 threads. Most reliable: every log entry is checksummed and verified, recovery is append-only (grade A). Writes 530–544 MB/s, 65–117 MB heap, Apache-2.0. Space is 0.2 % after load but levels off at about 10 % under heavy churn, because the cleaner is capped at 90 % utilization. No release since 2018. **Take JE instead of TuplDB if AGPL-3.0 is unacceptable, or if the ~10 % worst case is acceptable in exchange for 2× read speed.** |
| 3 | H2 MVStore 2.4.240 (current) | fails space | **Replace it.** Overhead reaches 13 % after churn and 44 % after overwrites, and `compactFile` does not recover it. 8-thread reads barely scale (≈ 4.7 k/s). Payloads are not checksummed (B−). |
| 4 | Infinispan SoftIndexFileStore 16.3.0 | fails space, reliability C | 22 % overhead after overwrites with no explicit compaction, heap 0.5–2.9 GB. No payload checksum, a full index rebuild after every crash, and data-loss bugs in its 2026 index rewrite. |
| 5 | Xodus 2.0.1 | reliability C | Slowest reads (≈ 1.3 k/s). Overhead is 13–54 % until a full GC that takes 3.5–5 minutes. No checksums, project in sunset, values capped at the log file size (16 MiB failed). |
| 6 | DataStore4J 0.1.0 | review reject | Passed the functional runs, but its WAL has no checksum and its sync ordering is not crash-safe; no prefix scan. |
| 7 | LevelDB-Java 0.12 | review reject | 13 % overhead, 64 MB/s writes, `compactRange` not implemented, stale since 2019. |
| 8 | MapDB 3.1.0 | fails reliability | Writers livelocked next to readers. 42 MB/s writes, no working compaction. |
| 9 | Lasher 0.0.2 | fails reliability | Fastest raw speed thanks to mmap, but both crash runs lost synced data, and overhead reached 118 %. |
| 10 | FastKV-Java 1.2.7 | fails | Out of memory at 100 k values. Both crash runs lost synced data. |
| 11 | whippet-db 1.0.2 | fails | Hard cap at 2 GiB; reads fail with NPE or `/ by zero`. |
| – | bytevault, StormDB, HaloDB, Chronicle Map, Eclipse Store, SwayDB | excluded on review | Broken, the wrong data model, or native/unavailable (see the table below). |
| ref | plain files, one per key (not a library) | — | Matches JE on reads (≈ 4.4 k/s cold), keeps 1.6 % overhead under any churn, 100 MB/s writes. Costs: 100 k+ files on NTFS and no checksums. |
| ref | RocksDB, LMDB (native) | not pure Java | RocksDB/BlobDB stays at 0.1 % overhead but writes only 46 MB/s. LMDB on Windows preallocates its map (9.5 %). |

Both finalists beat the current MVStore on every measured axis except raw write speed, and all three clear the
50 MB/s bar there.

**Owner decision (2026-10-12):** plain **TuplDB, without page checksums**. Licence is irrelevant for this hobby
project, and the store is a regenerable cache, so space and speed come before integrity checks. Implemented
2026-10-12 as `store/tupl/TuplBlobStore` ([backend map](map/backend.md)); since then it holds covers only.

## Results at 100 000 values (7.61 GiB), means over all runs

Machine: Windows 10, NVMe (Kingston KC3000), 12 threads, 32 GB RAM. During the runs only about 4 GB of RAM was free,
so the OS file cache stayed under 1 GB. Every full-store read pass is therefore **cold**: random 80 KiB reads from
the SSD, which is the realistic case for a store this size. Cold single-thread reads varied up to ±50 % between
runs, so the per-engine means pool every sample.

| store | cold 1T reads/s, after load / after updates | 8T reads/s | load MB/s (1T) | overwrite MB/s (1T) | overhead after load | steady-state overhead¹ | heap MB | crash runs | review grade |
|---|---|---|---|---|---|---|---|---|---|
| JE | 4 227 / 5 470 | 19 262 | 544 | 46–86 | 0.2 % | 10.9 % | 65 | 2/2 ok | A |
| TuplDB (4 KiB, CRC) | 2 954 / 962–2 285 (engine mean ≈ 2.2 k over 9 samples) | 9 359 | 241 | 217–274 | 1.3 % | 2.3 % | 53 | 2/2 ok | A− |
| TuplDB (4 KiB) | 1 695 / 1 708 | 7 137 | 251 | 280–286 | 1.2 % | 2.2 % | 53 | 2/2 ok | B |
| MVStore (current) | 3 213 / 2 955 | 4 697 | 871 | 170–180 | 2.7 % | 44.3 % | 134 | 2/2 ok | B− |
| Infinispan SIFS | 3 107 / 2 584 | 11 663 | 316 | 113–136 | 0.1 % | 22.2 % | 515–2 905 | 2/2 ok | C |
| Xodus | 1 290 / 1 312 | 6 549 | 278 | 56–100 | 13.0 % | 0.6 % (after 287 s GC) | 304 | 2/2 ok | C |
| files (baseline) | 4 752 / 3 723 | 19 452 | 108 | 88–93 | 1.6 % | 1.6 % | 48 | 2/2 ok | – |

¹ The worse of two scenarios, each after the store's explicit compaction: churn (delete 10 % by album, then write
it back) and overwrites (two rounds over a random 20 % of existing keys). TuplDB uses the stepped `compactFile`
(0.90 → 0.99). A single 0.95 step left 6.5–6.7 %; those runs are kept in `superseded.jsonl`.

TuplDB variants that were tried and rejected:
- mmap: hot reads collapse to 1.4 k/s under memory pressure, and overwrites drop to 66–102 MB/s.
- 16 KiB pages: no gain on single-thread reads, and 3.7–9.1 % overhead.

## What the measurements confirmed about the rejected stores

| store | result |
|---|---|
| whippet-db | `Unsupported capacity: 2147483648 > 2147483647` at 2 GiB. Reads also fail with NPE or `/ by zero`, depending on the size hint. |
| FastKV | `OutOfMemoryError` at 100 k values with a 3 GB heap. Both crash runs lost synced values. |
| Lasher | Both crash runs lost 1 531 synced values. Overhead was 118 % after churn. It was the fastest store, thanks to mmap. |
| MapDB | Writers livelocked: 4 writers could not finish 4 000 puts in 3 minutes next to 4 readers. 42 MB/s writes, and 8-thread reads did not scale. |
| LevelDB-Java | `compactRange` is "Not yet implemented". 13 % overhead and 64 MB/s writes. |
| DataStore4J | Passed the functional tests here, but has no prefix scan. Its crash-safety gaps are in the WAL and SST sync ordering (code review), which a process kill does not exercise. |

## Large scale: 400 000 values

30.4 GiB of payload, about the machine's whole RAM, for the two finalists only (bench, stability and crash runs).
Both stayed correct: 0 verify errors, 0 lost or corrupt values in both hard kills, 64 MiB values and concurrency
passed. Space behaved as it did at 100 k.

| store | cold 1T reads/s | 8T reads/s | load MB/s | overhead after load / after churn | heap MB | process commit MB |
|---|---|---|---|---|---|---|
| JE | 3 170 | 19 766 | 530 | 0.24 % / 10.3 % | 117 | 610 |
| TuplDB | 1 114 | 4 983 | 295 | 1.18 % / 1.21 % | 88 | 746 |

At this size JE reads 2.8× (1 thread) to 4× (8 threads) faster than TuplDB.

During a fast bulk load TuplDB's redo log grew past 10 GB before checkpoints caught up. So peak disk use while
loading is far above the final size; budget for it, or throttle bulk loads.

## NO_REDO validation (2026-10-12)

Variant `tupldb-noredo` (`NO_REDO`, `checkpointSizeThreshold(0)`, `checkpointRate(1 s)`, `sync()` = `checkpoint()`), n = 100 000, JDK 26.0.2.1. Rows in [kvbench/results/results.jsonl](../kvbench/results/results.jsonl), report [kvbench/results/REPORT.md](../kvbench/results/REPORT.md), logs in `kvbench/results/logs/tupldb-noredo-*.log`.

| criterion | measured | result |
|---|---|---|
| verify errors | 0 | pass |
| crash runs (300 ms / 1.5 s) | lostSynced 0, corrupt 0, writableAfter true (both); 0 of 3 414 / 10 532 unsynced writes survived | pass |
| load | 386 MB/s (tupldb NO_SYNC: 251) | pass (>= 250) |
| overhead after load | 1.2 % | pass (<= 1.5 %) |
| overhead after updates + stepped compaction | 2.2 % | pass (<= 3 %) |
| no `tupl.redo.*` during load | files exist but stay header-only: max 153 bytes, 0-2 at a time, rotated per checkpoint | literal fail, intent pass |

Read after updates 2 064 ops/s (tupldb: 1 708). Single-thread read 1 964 ops/s (tupldb: 1 695). Unsynced writes are lost on a crash by design (it is a cache); synced ones are not.

**Verdict: accepted on all measured criteria.** The only deviation is the literal "no redo files" check: Tupl creates a tiny redo file per checkpoint even under `NO_REDO`, but no redo data accumulates, which was the point of the criterion.

## Integrating the choice (for the follow-up change)

Historical notes; the implementation differs where the owner decided: no page checksums and no CRC envelope (each
value carries an album-state tag and a WebP RIFF size check instead), `NO_REDO` per the validation above, exact-key
removal only. See `store/tupl/TuplBlobStore` and `AlbumService.storedCover`.

- Keep the `BlobStore` port. Add `store/<engine>/` with `@ConditionalOnProperty(thumbnailer.store.blobs=…)`.
- Wrap values in a small envelope carrying a CRC32C. Treat a mismatch, or a store that refuses to open, as "move the
  directory aside and regenerate". Every engine has such a refuse-to-open path.
- **JE:** `EnvironmentConfig`:
  - `transactional=true`, `Durability.COMMIT_NO_SYNC`, cache 64–256 MB, database `CacheMode.EVICT_LN`
  - `je.cleaner.minUtilization=90`, `je.log.fileMax=256 MB`, `je.env.runVerifier=false`, `je.log.detectFileDelete=false`
  - prefix delete via a cursor in a transaction
- **TuplDB:**
  - `checksumPages(CRC32C::new)` at creation, `durabilityMode(NO_SYNC)`
  - JVM flags `-Dorg.cojen.tupl.core.DirectPageOpsSelector=ffm --enable-native-access=ALL-UNNAMED`
  - close with `shutdown()`, because `close()` leaves the redo log to be replayed
  - stepped `compactFile` (0.90 → 0.99) when idle
  - plan for the AGPL obligations
