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

## Build environment notes (sandbox, not committed)
- The sandbox is aarch64: JDK 17 aarch64, Android SDK (platform 35, build-tools 35/36) and an
  x86 aapt2 run through qemu-x86_64 (`-Pandroid.aapt2FromMavenOverride`). Maven Central is reached
  through a Google mirror (Gradle init script). Robolectric runs offline from pre-fetched
  android-all-instrumented jars with LEGACY graphics/sqlite and Conscrypt OFF.

## Next
- 2b: library sort.
- 2c: camera approval.
- 3: transfer engine.
- 4: Windows desktop app, full line-by-line review, release, Drive upload.

