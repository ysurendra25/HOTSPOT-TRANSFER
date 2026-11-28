# PocketBridge transfer engine (0.7.0.1)

One protocol, three senders, two receivers:

| Sender | Receiver | Code |
|---|---|---|
| Edge page (laptop browser) | phone (`MediaHttpServer`) | `index.html` "Transfer engine" section |
| Android app, phone → laptop | Windows helper (Go) | `ChunkedUploader` ← `MainActivity.sendToReceiver` → `upload.go` |
| Android app, phone → phone | other phone (`MediaHttpServer`) | `ChunkedUploader` ← `MainActivity.sendToReceiver` |

Downloads use plain HTTP ranges on the existing file routes (`/api/media/…`,
`/api/inbox/…` on the phone, `/file/…` on the helper), with a strong `ETag` and
`If-Range`.

## Upload protocol

All routes need the receiver's normal auth: on the phone `?code=` or
`Authorization: Bearer <token>`; on the helper `?t=`, `X-Token`, the `pcr` cookie or
`Authorization: Bearer <token>`. Bodies are JSON unless stated.

| Method & path | Body | Reply |
|---|---|---|
| `POST /api/upload/start` | `{name, size, lastModified, relPath}` | state (below) |
| `PUT /api/upload/{id}?offset=N` | raw bytes for `[N, N+Content-Length)` | `{id, received, size, complete}` |
| `GET /api/upload/{id}` | – | state |
| `DELETE /api/upload/{id}` | – | `{ok}` (404 when unknown) |
| `POST /api/upload/{id}/finish` | – | `201 {ok, id, name, bytes}` |
| `POST /api/upload/batch` | PBB1 (below), `Content-Length` required | `201 {ok, count, bytes, files:[{id,name,bytes}]}` |

State: `{id, name, relPath, size, received, complete, resumed, missing:[[a,b],…], chunk, connections}`.
`missing` lists half-open byte ranges still needed; `chunk`/`connections` are the
receiver's suggestion (`TransferPlan.initialChunk/initialConnections`; the Go helper
uses the same formulas).

**Session id** = first 32 hex digits of `SHA-256(name + "\n" + size + "\n" + lastModified + "\n" + relPath)`
after the receiver sanitised `name` and `relPath`. The same file from the same
sender therefore always maps to the same session, on either receiver: sending it
again after a cancel, a dropped connection, a page reload or a receiver restart
resumes. `lastModified` must be stable for one file (the app uses the document's
`COLUMN_LAST_MODIFIED`, 0 when the provider has none).

**Errors** (JSON `{error}`): 400 bad request or body ended early (bytes that arrived
are kept), 401 auth, 404 unknown session, 409 finish while incomplete or while parts
are still being written, 410 session closed, 411 missing `Content-Length`, 416 part
outside the file, 507 not enough free space (checked once at start / per batch).

**Client loop** (all three senders): `start` → split `missing` into chunks → parallel
`PUT`s → on a failed part, `GET` the state and resend only from
`firstMissing(missing, partStart, partEnd)` (backoff 300–400 ms × attempt, 3–4
retries; 4xx other than 400/408 is final) → `finish`.

| Sender | Parallel PUTs | Chunk |
|---|---|---|
| Edge page | 1 (≤ 4 MiB), 2 (< 256 MiB), 3 (< 1 GiB), 4 | 4–16 MiB (≈ size / connections / 8) |
| App | receiver's `connections` (1–4), capped at 6 | receiver's `chunk` (1–16 MiB) |

## PBB1 batch

Many small files in one body (Edge page: files ≤ 2 MiB, batches < 64 MiB / ≤ 1000
files; app: multi-select files ≤ 2 MiB). All integers big-endian:

```
"PBB1"
repeat: u16 pathLength | path (UTF-8, "Trip/day 1/a.jpg") | u64 size | size bytes
u16 0
```

Paths keep folders. On any error the receiver removes every file this batch wrote.

## Safe paths

- Phone (`RelPath.sanitize`): rewrites — drops `.`/`..`/empty segments, replaces
  reserved characters, prefixes Windows device names with `_`, max depth 32.
- Helper (`safeRelPath` in `upload.go`): **refuses** `..`, absolute paths, UNC
  prefixes, drive letters (`C:`), and device names (`CON`, `PRN`, `AUX`, `NUL`,
  `COM0-9`, `LPT0-9`, `CONIN$`, `CONOUT$`, with any extension) with 400; replaces
  `<>:"|?*` and control characters; max depth 32; the joined path is checked again
  to stay inside the receive folder.
- A name clash on finish gets `name (2).ext`, never an overwrite.

## Where files land

- Phone: `filesDir/received/<relPath>` (or `edge-<id>-<name>` for a single file); chat
  entry added; the app's "received" list and `/api/inbox` show nested ids.
- Helper: `<shared folder>/PocketBridge/<relPath or name>`, mtime restored from
  `lastModified`, so the files also appear in the phone's laptop file list.

## Resume format

Partial data: `uploads/<id>.data` (phone, in `filesDir`) or
`<shared folder>/PocketBridge/.pocketbridge-uploads/<id>.data` (helper), sparsely
preallocated (`RandomAccessFile.setLength` / `os.File.Truncate`) and written by
position (`FileChannel.write(buf, pos)` / `WriteAt`) from pooled 1 MiB buffers.

Progress: `<id>.pbresume` beside it, text, written atomically (temp file, fsync,
rename) and **only after the data it describes was synced** (every 64 MiB or 2 s,
and when a connection drops):

```
PBRESUME 1
size=4294967296
validator=4294967296:1700000000000      size:lastModified (downloads: the ETag)
source=rel:Trip/day 1/clip.mp4          or name:<file name>
done=0-16777216,33554432-50331648       half-open, sorted, merged
```

A crash can lose progress but never marks unwritten bytes as done. Sessions are
reloaded from these files after a restart; ones untouched for 7 days are deleted at
start. `RangedDownloader` uses the same format for downloads (`<part>.pbresume`).

## Downloads

- Probe `Range: bytes=0-0` → 206 gives size + strong `ETag`.
- Parallel `Range` + `If-Range: <etag>` parts; a 200 to `If-Range` means the source
  changed: restart (app) or fall back to a normal download (Edge page).
- Phone `ETag`: `DownloadHeaders.etag(key, size, modified)`; helper `ETag`:
  `"pb-<size hex>-<mtime ns hex>"` set before `http.ServeFile`, which honours
  `If-Range` against it.
- Edge page: files ≥ 32 MiB with `showSaveFilePicker` available are fetched in
  parallel into the chosen file with positional `createWritable().write({position})`;
  otherwise (and on any failure) the plain `?download=1` link with
  `Content-Disposition: attachment` runs as before.
- Helper CORS: methods `GET, HEAD, POST, PUT, DELETE, OPTIONS`; request headers
  `Content-Type, X-Token, Authorization, Range, If-Range`; exposed
  `Content-Length, Content-Range, Accept-Ranges, ETag, Content-Disposition`;
  Private Network Access preflight answered.

## Back-compat

| Sender ↔ receiver | Behaviour |
|---|---|
| new sender → old phone | `POST /api/upload/start` gets 404 → legacy `POST /api/upload?name=` (whole file, one request). The Edge page and the app (phone → phone) both do this; a batch 404 falls back to one legacy POST per file. |
| app → old helper | The probe (`start` with `size:-1`: 400 on a current receiver, 404/405 on an old one) fails → the files go to this phone's shared inbox as before and the app says to update the helper. |
| old sender → new receiver | Legacy routes are unchanged (`/api/upload`, `?download=1`, ranges). |
| any → any download | Ranges, `ETag` and `If-Range` are plain HTTP; a client that ignores them gets the whole file. |

## Migration steps

1. Update the Windows helper first (it ships inside the APK: Connect → "Download
   Windows helper"); until then phone → laptop sends keep using the phone inbox.
2. Update both phones; mixed versions fall back to the legacy POST automatically.
3. Nothing to migrate on disk: received files stay where they were; unfinished 3a
   sessions in `filesDir/uploads` keep resuming (same id and manifest format).
4. To clean up: a cancelled or abandoned upload is removed by `DELETE
   /api/upload/{id}` or after 7 days.

## Tests

- Go: `upload_test.go` (paths, manifest format, session id equals the phone's,
  ETag/If-Range, CORS, auth, loopback upload + 4-way ranged download with SHA-256,
  resume after a dropped PUT and a restart, bad input, batch + escapes, prune, 507),
  `transfer_bench_test.go`.
- JVM: `TransferServerTest` (+ probe, legacy fallback, cancel then resume, source
  batch), `TransferPlanTest`, `ResumeManifestTest`, `RelPathTest`, `BatchStreamTest`,
  `UploadSessionsTest`, `RangedDownloaderTest`, `TransferBenchmarkTest` (gated).
- Page: `tools/test-transfer-plan.js` (planner, PBB1 bytes, retry via status, legacy
  fallback), `tools/check-web-page.py` markers.
