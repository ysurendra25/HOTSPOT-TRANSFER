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

## Build environment notes (sandbox, not committed)
- The sandbox is aarch64: JDK 17 aarch64, Android SDK (platform 35, build-tools 35/36) and an
  x86 aapt2 run through qemu-x86_64 (`-Pandroid.aapt2FromMavenOverride`). Maven Central is reached
  through a Google mirror (Gradle init script). Robolectric runs offline from pre-fetched
  android-all-instrumented jars with LEGACY graphics/sqlite and Conscrypt OFF.

## Next
- 3: transfer engine.
- 4: Windows desktop app, full line-by-line review, release, Drive upload.

## Known test notes
- tools/test-edge-presentation.mjs launches msedge.exe and only runs on Windows (fails in the sandbox
  with ENOENT, before and after these changes).

