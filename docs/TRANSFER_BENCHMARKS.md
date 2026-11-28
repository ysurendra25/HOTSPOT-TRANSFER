# Transfer benchmarks (0.7.0.1, phase 3b)

**Loopback is not Wi-Fi.** Every number here was measured over `127.0.0.1` on one
machine: no radio, no air-time sharing, no packet loss and near-zero latency. They
show CPU, disk and protocol cost (requests per file, buffer copies, parallelism
overhead), not the speed a phone hotspot will reach. On a real 2.4/5 GHz link the
wire is the bottleneck by one to two orders of magnitude; parallel connections and
resume matter there because one TCP stream rarely fills a lossy, high-latency link
and because a dropped connection no longer costs the whole file.

## Machine

- 2 CPUs (`nproc` = 2), aarch64 Neoverse-V2, ~7 GB RAM, overlay /tmp with ~2 GB free
- Go 1.26.11 linux/arm64; OpenJDK 17.0.21 (Robolectric 4, Android SDK 34 runtime)
- Measured 2026-11-28 in the build sandbox.

## What is compared

| Case | old path | new path |
|---|---|---|
| 1 KB × 5000 upload | one `POST /api/upload` per file (legacy shape) | one PBB1 batch (`POST /api/upload/batch`) |
| 100 MB upload | one streaming `POST` | `start` + parallel `PUT ?offset=` (4 on Go, receiver-chosen 2 on the JVM) + `finish` |
| 100 MB download | one plain `GET` | parallel `Range` requests with `If-Range` (Go: 4 × 16 MiB; JVM: `RangedDownloader`) |
| 4 GB up/down | same as above | same as above |

The 4 GB cases read a **sparse source** (a 4 GiB file made with truncate, so it reads
as zeros without disk I/O) and write to a **discarding sink** (Go: the upload store's
data file is swapped for a discard `WriterAt`; JVM: a small discarding loopback
receiver), because /tmp is smaller than 4 GB. They measure transport and protocol
cost only, without disk writes.

## Go helper (`go test -run '^$' -bench Transfer -benchtime 3x -count 2`, `PB_BENCH_4G=1`)

Source: `tools/win-control/upstream-v0.1.0/web-remote-desktop-0.1.0/transfer_bench_test.go`.
Each row is the mean of 3 iterations; two runs shown.

| Case | old MB/s | new MB/s | new / old |
|---|---|---|---|
| 1 KB × 5000 upload | 22.99 / 22.98 | 56.56 / 57.08 | **2.5×** (223 ms → 90 ms) |
| 100 MB upload | 1837 / 2117 | 2400 / 2430 | 1.2× |
| 100 MB download | 3713 / 3242 | 7840 / 7019 | 2.1× |
| 4 GB upload (sparse → discard) | 5356 / 5406 | 7953 / 7978 | 1.5× |
| 4 GB download (sparse → discard) | 8233 / 8494 | 19799 / 19946 | 2.4× |

## Android/JVM (`TransferBenchmarkTest`, gated by `-Dpocketbridge.bench=true`)

Run: `./gradlew :app:testDebugUnitTest -Ppocketbridge.bench=true --tests '*TransferBenchmarkTest*'`.
1 KB × 5000 and 100 MB go through the real phone server (`MediaHttpServer`) under
Robolectric; one run each, so treat ±20 % as noise.

| Case | old | new | new / old |
|---|---|---|---|
| 1 KB × 5000 upload | 10 428 ms (0.5 MB/s) | 397 ms (12.9 MB/s) | **26×** |
| 100 MB upload | 164 ms (641 MB/s) | 172 ms (609 MB/s) | ~1× |
| 100 MB download | 57 ms (1854 MB/s) | 101 ms (1039 MB/s) | 0.56× |
| 4 GB upload (sparse → discard) | 705 ms (6094 MB/s) | 801 ms (5360 MB/s) | 0.9× |
| 4 GB download (sparse → discard) | 553 ms (7764 MB/s) | 520 ms (8254 MB/s) | 1.06× |

## Reading the numbers

- **Many small files is the big win**: one request per file costs a connection, a
  request parse, a temp file, an fsync and (on the phone) a chat entry each. PBB1
  turns 5000 of those into one body: 2.5× on the Go helper, 26× on the phone server.
- **Big files on loopback** are CPU-bound on 2 cores, so parallel connections can
  only help where one stream leaves a core idle (Go: 1.2–2.4×) and cost a little
  where it does not (JVM 100 MB download: `RangedDownloader` also preallocates,
  writes a resume manifest and forces data to disk, which the plain GET does not).
  The parallel paths are for Wi-Fi, where a single TCP stream stalls on loss and
  latency; that has to be measured on devices (phase 4).
- **Resume** is the other gain and does not show here: an interrupted 4 GB transfer
  now continues from the last synced range instead of starting over (covered by
  `TestUploadResumesAfterAnInterruptionAndARestart`, `cancelStopsTheUploadAndSendingAgainResumes`
  and the 3a resume tests).
