# PocketBridge 0.7 — working state

Branch: `pocketbridge-0.7` (from `codex/pocketbridge-next-fix` @ c1542fb, v0.6.0.12).
Never touch `main`, tags or releases from this branch.

## Version numbers
- Branch base: versionName 0.6.0.12, versionCode 36
- Google Drive build (drive-0700.apk, aapt2 dump badging): versionName 0.7.0.0, versionCode 45
- This branch: versionName **0.7.0.1**, versionCode **46**

## Phase 1 (done, commits dcf32e3, 1b1abd8 and the follow-up)
- Screen latency fixes (ScreenEncoderPlan, HardwareEncoders), phone power relay
  (LaptopRelay, LaptopPowerClient, Go phone_relay.go), airplane-mode reset
  (AirplaneController + accessibility service + quick tile).
- phone_relay.go backs off 60 s on HTTP 429 (phone code lockout) instead of retrying.
- Unit tests: ScreenEncoderPlanTest, LaptopRelayTest, LaptopPowerClientTest, Go phase07_test.go.

## Phase 2a (done): downloads and chat actions
- Server: `?download=1` on /api/media and /api/inbox sends `Content-Disposition: attachment`
  with an ASCII `filename` and an RFC 5987 `filename*` (DownloadHeaders). Playback stays `inline`.
  Ranged responses carry a strong ETag and honour If-Range, so Edge can resume.
- /api/zip: stored entries with data descriptors (StoredZip) and an exact Content-Length when
  every file's size is known (ParcelFileDescriptor.getStatSize) and no ZIP64 is needed;
  otherwise the old streaming ZIP without a length. A provider that reports size 0 is asked
  for its real size; a truly empty file gets Content-Length: 0.
- Edge page: every Download (Library cards, viewer Original, Received files, Chat) is a link with
  the download attribute to `?download=1`. Chat buttons by type: video Play / Play in VLC
  (.m3u playlist download) / Download, music Play / Download, other Open / Download.
- Android: laptop/Edge files still land in the private chat inbox, then are copied into system
  Downloads (MediaStore Download/PocketBridge on 10+, app Downloads + DownloadManager
  addCompletedDownload on 8–9) with a progress notification (DownloadPublisher). Chat Download
  does the same (folder picker only as fallback). Play in VLC = ACTION_VIEW to org.videolan.vlc,
  chooser fallback. DownloadManager itself is not used for fetching: it binds to the default
  network and cannot reach a laptop on this phone's hotspot.
- Connect: "Hotspot speed" card (HotspotBand) when sharing over the hotspot: says 2.4 GHz when
  the band can be read (hidden SoftApConfiguration.getBand / WifiConfiguration.apBand via
  reflection), otherwise a "check the band" hint; button opens hotspot/tether settings.
- Tests: DownloadHeadersTest, ChatActionsTest, StoredZipTest, HotspotBandTest, two new
  ServerFunctionalTest cases, tools/test-chat-actions.js; check-web-page.py markers updated.

## Phase 2b (done): Library sort
- Six orders everywhere: name_asc, name_desc, date_desc (default), date_asc, size_desc, size_asc
  (LibrarySort.java; natural, case-insensitive collator: "Ep 2" < "ep 10"; every order ends on a
  unique key, so sorting is total and paging stable).
- Categories: all, videos, music (audio), images, documents, apps, other (MediaTypeClassifier.category;
  "files" = documents+apps+other stays for old clients).
- /api/files?sort=… → MediaRepository.listPage(kind, offset, limit, q, sort). Matching rows (metadata
  only) are read once, sorted in memory and kept as a snapshot keyed by kind/q/sort; offset 0 takes a
  fresh snapshot, later pages reuse it (2 min), so pages never repeat or skip. Reply echoes "sort".
- Edge page: sort select in the toolbar + visible sort buttons under the category tabs (Files tab),
  choice per category in localStorage (pb_sort_<kind>). Edge-shared files now carry lastModified.
- Android: laptop-file and Edge-shared lists get category chips (All/Videos/Music/Photos/Docs/Apps/Other)
  and a "Sort: … ▾" menu, saved per category in SharedPreferences("library", sort_<kind>); the sort is
  also sent to the helper's /files so its 5000-row cap keeps the right rows.
- Go helper: /files?sort=… (files_sort.go), echoes "sort".
- The other phone's files are listed only by the Edge page (a phone browser opening this phone's link),
  so the web sort covers them. The app itself has no own-phone media list.
- Tests: LibrarySortTest, ServerFunctionalTest.libraryEchoesTheSortItApplied, Go files_sort_test.go,
  tools/test-library-sort.js; check-web-page.py markers.

## Phase 2c (done in code, needs devices): camera approval
- CameraCall (phone server): idle → requested → accepted | declined | expired (30 s) → live → stopped.
  Asking counts as the asker's consent; the other side must accept. Fresh id per request; stale ids
  get 410; offers before both accepted get 409. Either side's stop ends it for both.
- Routes: GET /api/camera/status; POST /api/camera/request {name}, /respond {id, accept},
  /offer {id, sdp}, /stop {id}. All return the call state (CORS only for the helper's localhost page).
- CameraSessionService starts when this phone consents (request/accept, from the app after the Android
  camera permission), but opens the camera only once both accepted; follows CameraCall (mode host) or
  PeerCameraLink (mode peer). answerOffer waits up to 15 s for the camera instead of refusing (old
  signaling-order failure). Front/back switch, mirrored front preview, live flag on ICE connected,
  ICE failed → call stopped.
- Phone↔phone: PeerCameraLink polls the other phone's /api/camera/status (1 s) with its link + code
  (Copy phone link and access code); the calling phone sends the offer, the other phone's server answers.
- Edge: hotspot page Camera section polls status, shows Accept/Decline, LIVE badge, End call; Accept and
  Request open the helper's /capture-camera window (secure localhost, getUserMedia only after both
  accepted; screen source waits for a click because getDisplayMedia needs one).
- Notifications: CameraCallNotifier shows a high-priority Accept/Decline notification when the app is
  in the background (Accept opens the app for the permission prompt; Decline answers directly).
- WebRTC fixes: no STUN/TURN, UDP host candidates only (TCP/srflx/relay stripped; mDNS kept), TCP
  candidates off, GATHER_ONCE, max-bundle; hardware H.264 first (ScreenEncoderPlan.preferCodecs on the
  offer the phone answers; Edge setCodecPreferences), camera bitrates 300–3000 kbps.
- Go helper /phone-signal forwards request/respond/offer/stop bodies for /offline-camera.
- Tests: CameraCallTest, CameraSdpTest (host-only, H.264 first, link parsing), ServerFunctionalTest
  camera cases, Go phone_signal_camera_test.go.

## Phase 3a (done): transfer engine, Android side
- Core (pure Java, unit tested): TransferPlan (range split, Range/Content-Range maths, 1..16 MiB adaptive
  chunks, 2..6 connections via Concurrency), BufferPool (bounded 1 MiB buffers, HEAP/DIRECT pools),
  ResumeManifest (PBRESUME 1: size, validator, done ranges; merge, missing, atomic temp+fsync+rename),
  RelPath (safe folder paths: no ../, drive letters, reserved chars or Windows device names; depth 32;
  unique " (2)" names), BatchStream (PBB1: "PBB1" | u16 len, path, u64 size, bytes ... | u16 0).
- UploadSessions: resumable uploads in filesDir/uploads/<id>.data + <id>.pbresume. id = first 32 hex of
  SHA-256(name\nsize\nlastModified\nrelPath), so the same client file always resumes. Sparse
  preallocation (setLength), positional FileChannel writes from BufferPool.HEAP, force() then manifest save
  every 64 MiB or 2 s (and on a dropped connection, keeping the bytes that did arrive). Free space checked
  once at start (507). finish renames into received/<relPath> (or received/edge-<id>-<name>), clash -> " (2)",
  restores lastModified. Sessions are reloaded from disk after a restart; stale ones (7 days) pruned at start.
- MediaHttpServer routes (same code/token auth): POST /api/upload/start {name,size,lastModified,relPath}
  -> {id,received,missing:[[a,b]],chunk,connections,resumed}; PUT /api/upload/{id}?offset=N (raw body);
  GET / DELETE /api/upload/{id}; POST /api/upload/{id}/finish -> {id (inbox id), name, bytes};
  POST /api/upload/batch (PBB1 body, folders kept; <=20 files one chat entry each, else one chat line).
- Transport: HTTP/1.1 keep-alive (Connection: keep-alive, Keep-Alive: timeout=40) only for upload routes
  and media/inbox responses of known length; everything else still closes. SO_RCVBUF 4 MiB set before
  bind; bulk responses (>= 1 MiB) get SO_SNDBUF 4 MiB and TCP_NODELAY off; NODELAY back on for the next
  request on a kept-alive socket (control/screen keep NODELAY). 40 s idle: socket read timeout plus a
  watchdog that closes a bulk send with no progress for 40 s. Legacy /api/upload checks free space once.
  A short read on a kept-alive response now closes instead of leaving the client waiting.
- /api/inbox lists received/ recursively: id = path below received/ ("Trip/day 1/a.jpg"), plus "folder".
  /api/inbox/{id} accepts nested ids (encoded or plain slashes), checked by EdgeInbox.validId/resolve;
  ReceivedFileProvider and the chat actions use the same check. ?download=1, ETag and If-Range unchanged.
- RangedDownloader (client): probe "Range: bytes=0-0"; 206 -> size + strong ETag, preallocate .part,
  2..6 adaptive workers with Range + If-Range, manifest beside the part (.part.pbresume) for resume;
  a 200 to If-Range (or a different ETag/size) = source changed -> restart (max 2); a 200 to the probe =
  no ranges -> single stream from that response. LaptopFileTransfer uses it with a stable hidden part
  name (.pb-<hash of url-without-query, size, name>-<name>.part) so a retry resumes.
- DownloadPublisher copies into Downloads with FileChannel.transferTo (stream fallback).
- ChunkedUploader (client, phone->laptop helper once 3b lands, phone->phone now): start, parallel PUTs of
  the missing ranges (retry asks the receiver for what is still missing), finish; uploadBatch for PBB1.
  Not wired into MainActivity yet (no UI entry point).
- Tests: TransferPlanTest, BufferPoolTest, ResumeManifestTest, RelPathTest, BatchStreamTest,
  UploadSessionsTest, RangedDownloaderTest (+ RangeHttpServer test server), TransferServerTest
  (routes, mid-range interruption + resume, folders, keep-alive, ETag/If-Range unchanged, 48 MiB loopback
  upload + parallel download with equal SHA-256). Full suite: 213 tests, 0 failures. Lint 0 errors.

## Phase 3b (done): Go helper, Edge page, app wiring, benchmarks, docs
- Go helper (upload.go): same upload protocol as the phone (start / PUT ?offset= / GET / DELETE / finish /
  PBB1 batch) under /api/upload/, files land in <shared folder>/PocketBridge (state in
  .pocketbridge-uploads/<id>.data + .pbresume, PBRESUME 1, session id identical to the phone's). Sparse
  Truncate, WriteAt from a sync.Pool of 1 MiB buffers, sync then manifest every 64 MiB / 2 s and on a dropped
  read, 507 free-space check (GetDiskFreeSpaceEx / Statfs), 7-day prune, mtime restored, " (2)" on clash.
  safeRelPath refuses .., absolute/UNC paths, drive letters and device names (CON..LPT9, CONIN$/CONOUT$).
  auth also accepts "Authorization: Bearer <token>" (the app's ChunkedUploader). fileHandler sends a strong
  ETag "pb-<size hex>-<mtime ns hex>" (ServeFile honours If-Range). CORS: GET/HEAD/POST/PUT/DELETE/OPTIONS,
  allows Range/If-Range/Authorization, exposes ETag/Content-Range/Accept-Ranges/Content-Disposition.
  Tests upload_test.go (loopback 24 MiB upload + 4-way ranged download SHA-256, resume after a dropped PUT and
  a restart, escapes, batch, prune, 507); transfer_bench_test.go (PB_BENCH_4G=1 for 4 GB).
- Edge page: "Transfer engine" section (pbUploadChunked 2-4 PUTs x 4-16 MiB, retry via status route,
  PBB1 batches for files <= 2 MiB, legacy POST on 404, pbDownloadRanged with showSaveFilePicker + positional
  createWritable for files >= 32 MiB, else the ?download=1 link). "Choose files" (multiple) + "Send folder"
  (webkitdirectory, keeps webkitRelativePath). Fixed bottom progress toast with % + MB/s + Cancel; download
  links carry data-size. check-web-page.py: route constants (UPLOAD_ROUTE/BATCH_ROUTE) + 3b markers.
  tools/test-transfer-plan.js (planner, PBB1 bytes, retry, legacy fallback).
- App: picked files (Send button and chat Attach) -> chooser: Laptop (saved helper link) / Other phone
  (PeerCameraLink target) / Keep on this phone for Edge (old behaviour; also used directly when neither is
  set). sendToReceiver: probe (start size:-1 -> 400 new, 404/405 old), small files in one PBB1 batch
  (uploadBatchSources), others ChunkedUploader.send; progress dialog with MB/s, Cancel (stop() disconnects),
  Retry/Resume dialog (same keys -> resumes). Old helper -> files go to the phone inbox + message; old phone
  -> legacy POST. ChunkedUploader now takes a Source (File or content Uri via ParcelFileDescriptor; non-seekable
  documents are copied to cache/outgoing first). Runs on phoneUploadQueue in the activity (no foreground
  service yet).
- Tests: TransferServerTest +4 (probe, legacy fallback, cancel then resume, source batch); TransferBenchmarkTest
  (gated, -Ppocketbridge.bench=true). Full suite 220 tests (3 skipped = gated benchmark), 0 failures; lint
  0 errors (136 warnings); assembleDebug OK; go vet (linux + windows/amd64), go test, Windows amd64 build OK.
- docs/TRANSFER_ENGINE.md (protocol, endpoints, back-compat, resume format, migration) and
  docs/TRANSFER_BENCHMARKS.md (loopback, 2 CPUs, numbers).

## Build environment notes (sandbox, not committed)
- The sandbox is aarch64: Temurin JDK 17 aarch64 in /tmp/jdk, Go 1.26 arm64 in /tmp/go (the helper is
  built during preBuild), Android SDK in /tmp/sdk (platforms 35/36, build-tools 35/36, via sdkmanager),
  qemu-user 11 (Debian .deb, extracted) runs the x86 build-tools aapt2 with an x86 glibc + libgcc root
  (/tmp/x86root) through the /tmp/aapt2 wrapper (`-Pandroid.aapt2FromMavenOverride=/tmp/aapt2`).
- The sandbox proxy needs credentials that Java tools do not send on CONNECT, so a tiny local forwarder
  (/tmp/fwdproxy.py on 127.0.0.1:3128) adds them; Java uses it through system properties and a JKS
  truststore built from the sandbox CA bundle. GRADLE_USER_HOME=/tmp/gradle-home holds those properties
  and an init script with Google's Maven Central mirror; it also sets Robolectric LEGACY graphics/sqlite,
  Conscrypt OFF, and the mirror as Robolectric's repo (android-all is downloaded once, not offline).
- Long Gradle runs: start them detached (`setsid nohup /tmp/test-x86.sh ... &`) and poll /tmp/test.rc; a full
  test + lint + assemble chain is in /tmp/full.sh (logs /tmp/full-*.log, rc /tmp/full-*.rc).
- /tmp/env.sh, /tmp/build.sh <tasks> (log /tmp/build.log, rc /tmp/build.rc), /tmp/test-x86.sh [--tests X]
  (log /tmp/test.log, rc /tmp/test.rc). gradlew is not executable on this file system: run `bash ./gradlew`.

## Next
- 3a, 3b: done (above).
- 4: installable Windows desktop app, full line-by-line review, release, Drive upload.
  Carry-overs from 3b to look at in 4: move app sends into a foreground service so a long send survives the
  activity; device tests over a real hotspot (parallel vs single stream on Wi-Fi); the app's "Send folder"
  (ACTION_OPEN_DOCUMENT_TREE) is not wired (the Edge page has it); a "Download from laptop" path in the app
  already uses RangedDownloader (3a).

## Known test notes
- tools/test-edge-presentation.mjs launches msedge.exe and only runs on Windows (fails in the sandbox
  with ENOENT, before and after these changes).

