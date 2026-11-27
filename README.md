# PocketBridge

PocketBridge is an open-source Android app for sharing phone media and the phone screen to a laptop browser over a local-only Wi-Fi hotspot. An optional portable open-source Windows helper also lets the phone view and control the laptop. Both workflows work without mobile data or internet access.

For laptop files and power controls, copy the **LAN** link for the laptop's Wi-Fi adapter into **Connect → Windows helper link** on Android. PocketBridge checks that exact address for the Windows helper. Links starting with `127.0.0.1` open on the laptop itself.

See the illustrated [quick-start guide](docs/QUICK_START_GUIDE.md) for connecting, browsing, and screen sharing.

## Version 0.7.0.1 (phase 1 of the 0.7 upgrade — not yet verified on devices)

Version code 46, so it installs over 0.7.0.0 (code 45) and 0.6.0.12 (code 36).

### Screen sharing latency and quality
- **Phone → laptop.** The phone now encodes with a hardware H.264 encoder when WebRTC has one (then hardware VP8, then software VP8 at 1280 px). Since 0.6.0.x the phone captured at native resolution and took the browser's first codec (VP8), which most phones encode in software, so frames queued for seconds and froze on keyframes. The capture keeps the native size when the encoder accepts it and otherwise scales, keeping the aspect ratio, to the largest size it accepts. Before, a 1080×2400 portrait frame could be handed to an encoder limited to 1920×1088, which corrupted or half-showed the picture. The source is limited to 30 fps without cropping. The JPEG fallback is latest-frame-only and scaled on the GPU to 1920 px, at up to 20 fps, so it no longer competes with the encoder. Edge shows live codec, size, fps and buffer delay under the viewer.
- **Laptop → phone.** The helper's JPEG stream defaults went back to a hotspot-safe profile: quality 75, at most 1920 px wide, 30 fps. The old 95 / native / 30 profile is migrated automatically. The stream socket's send buffer is capped at 256 KB, and frames are skipped when the link is slower than the frame rate, so the phone always gets the newest frame. H.264 via FFmpeg uses no B-frames and a 2 s GOP, with one fragment per frame. The phone's MediaSource player catches up instead of drifting up to 1.5 s behind. Edge's presenter sends H.264 first with a 12 Mbps ceiling and balanced degradation, and the phone viewer sets a zero jitter-buffer target.

### Laptop power buttons on the phone
Lock, wake, wake & show sign-in, sleep, **restart** (new) and shut down. The phone uses the pasted helper link when it can reach the laptop. Otherwise it uses the helper's own outbound connection to the phone, which needs no Windows Firewall rule. That connection opens automatically when the laptop pairs through the capture workspace, or by **Pair phone** in the helper's settings sheet. For sleep, restart and shut down, the phone checks back and shows the error Windows reported, if any.

### Airplane mode
- **Phone** (Connect tab on the phone and in Edge, and the helper's settings sheet): *refresh mobile data* switches the phone's airplane mode on and off, then brings the hotspot back. The laptop keeps rejoining the hotspot (`netsh wlan connect`) and the Edge page keeps retrying until the phone answers again.
  - Android does not let normal apps switch airplane mode or start the hotspot. PocketBridge uses the best route available:
    1. **One-time adb grant** (optional). From a computer with USB debugging on, run:
       ```
       adb shell pm grant com.pocketbridge android.permission.WRITE_SECURE_SETTINGS
       ```
       PocketBridge then writes the airplane setting and checks that the mobile radio really followed. Some Android versions ignore the setting unless the system sends its broadcast. In that case PocketBridge puts the setting back and moves to the next route.
    2. **Accessibility** (optional): enable *PocketBridge airplane reset* in Settings → Accessibility. It opens Quick Settings and taps the Airplane mode and Hotspot tiles. This works when those tiles are on the first Quick Settings page.
    3. **Guided** (always available): a high-priority notification opens full-screen steps: airplane ON, airplane OFF, hotspot ON. Each step advances by itself.
  - A Quick Settings tile, *Refresh mobile data*, starts the same reset.
- **Laptop** (helper settings sheet, on the laptop only): *Laptop airplane ON/OFF* turns every laptop radio off or on through the Windows.Devices.Radios API, using Windows PowerShell. Windows does not expose the Settings "Airplane mode" switch itself to apps. Switching the laptop's radios is refused from the phone, because it would cut the phone's own connection.

## Version 0.6.0.9

- The Edge video player opens in Fill screen. Playback controls disappear after three seconds and return when you move the pointer. Select Original to see the complete frame, or choose a cinema, IMAX, portrait or zoom setting.
- The Screen tab in Edge can share a selected laptop display or window back to the phone. The Camera tab connects both cameras. The matching portable Windows helper can be downloaded directly from either tab over the hotspot.
- Hotspot mode can reuse a phone hotspot that is already running. It refreshes the advertised address if the hotspot changes.

See [the release notes and setup instructions](RELEASE_NOTES_v0.6.0.9.md).

## Version 0.6.0.8

- **Screen sharing no longer builds the encoder on Android's main thread.** Startup and teardown are serialized in a service worker, startup can be cancelled, and the screen share adapts bitrate and frame rate to a congested hotspot instead of forcing a high bitrate that queues stale frames.
- **Camera startup reports its real state and errors.** The phone keeps checking while the camera initializes, avoids duplicate service starts, allows startup cancellation, and preserves permission or camera-open failures in the UI.
- **Fullscreen video keeps the selected aspect ratio.** Original, cinema, portrait and other ratios now size the video stage within the fullscreen controls; Fill and Stretch still use the available viewing area.
- Existing direct local WebRTC screen/camera sharing, phone and laptop file browsing, chat attachments, and the portable Windows screen/control helper remain included.

See [RELEASE_NOTES_v0.6.0.8.md](RELEASE_NOTES_v0.6.0.8.md).

## Version 0.6.0.7

- **Two-way camera share over the local hotspot.** The new Camera tab replaces the unsupported Clone/profile feature. The phone owner starts the camera and grants Android permission; the laptop owner opens the helper's Camera page on localhost, grants Edge permission, and connects with the phone link and code. The direct WebRTC call uses no STUN/TURN service or cloud relay, and either side can stop it.
- **Laptop files can be downloaded to the phone.** The file browser streams directly to the phone's private receive folder, asks Android to make storage available for known large files, removes partial downloads on failure, and adds successful downloads to Share chat.
- **Exact laptop paths now target the exact file.** Pasting a full file path filters the helper to that file, while an empty path restores the default Downloads folder.
- **Chat scrolling stays usable on touch.** The phone's nested chat scroll now captures its own vertical gestures, and the browser uses native vertical panning without scrolling the whole page.
- **The laptop Camera page is served by the portable helper.** It is protected by the helper token, opened through loopback for Edge camera permissions, and then signals the phone using the six-digit code.

See [RELEASE_NOTES_v0.6.0.7.md](RELEASE_NOTES_v0.6.0.7.md).

## Version 0.6.0.6

- **A cleaner video viewer.** The browser streams the original file first and fits the image to its source ratio by default. Optional Fill (crop), Stretch, 21:9, 16:10, 16:9, 3:2, 4:3, square and portrait layouts are available, along with playback speed, volume and picture-in-picture where the browser supports it.
- **The Music tab is audio-only.** Videos and file-type filters stay in Files; the Music tab contains the song queue and its player.
- **Chat is easier to browse.** The conversation has a larger scrollable history, keeps your reading position during refresh, and does not rebuild unchanged messages. Downloading a received attachment now starts only when you click its Download action.
- **Laptop files load with recycled rows on Android.** The phone no longer creates hundreds of buttons on the main thread as each laptop page arrives, reducing freezes while browsing large folders.
- **Incoming files can be saved deliberately on Android.** Tap Download to phone, choose a destination, then the file is copied in the background.
- **Repeated video opens release UI listeners and reset the viewer layout**, avoiding stale sizing and accumulated input callbacks.

See [RELEASE_NOTES_v0.6.0.5.md](RELEASE_NOTES_v0.6.0.5.md).

## Version 0.6.0.3

- **No more freezes from busy connections.** The phone's server creates workers as needed (up to 64) and answers "busy" instead of queueing, closes stuck screen viewers and all connections when sharing stops, and the screen stream no longer spins the CPU. Chat and other disk/network work moved off the main thread.
- **Laptop files load reliably.** The helper pages its file list with a time budget and skips system folders; the phone loads each page with retries and shows the helper's own error messages.
- **Full-frame, original-quality screen sharing.** Fixed the picture that showed in half the viewer, cut JPEG frames by Content-Length, follow rotation on every Android version, and stream WebRTC at native resolution with up to 25 Mbps and minimal delay. The JPEG fallback is native resolution too.
- **Laptop power controls work.** Sleep and shut down enable the Windows shutdown privilege, power buttons use the helper link, and every result is reported on the phone. "Wake & show sign-in" wakes the display; Windows still needs the PIN or password on the laptop.
- **The laptop screen shows on the phone.** The viewer may start muted video without a tap, the helper sends its access code with every request, falls back to its JPEG stream when video is refused, and wakes the display for the JPEG stream.
- **A real wrong-code lockout**, typed received files, and a release workflow that works for any version tag. See [RELEASE_NOTES_v0.6.0.3.md](RELEASE_NOTES_v0.6.0.3.md).

## Version 0.5.9.6

- **Phone and laptop files are separate library views.** Laptop files no longer sit below the phone's long media list. The phone view now has device tabs, laptop search, and type filters.
- **Laptop videos can stream.** The helper lists nested files in its shared folder, serves supported media inline with byte ranges, and the phone opens remote items using their actual media type.
- **Paste the helper link once.** The phone shares the link between Library and Screen; screen status explains whether the helper page could be reached.
- **Smoother hotspot screen streaming.** The Windows helper defaults to 1280 px / 10 fps / quality 60 and reuses JPEG buffers in its offline path. Phone-to-laptop WebRTC is capped at 5 Mbps / 24 fps to leave hotspot capacity for files.
- **Clearer multi-file chat.** Attachments show sender, time, media type and size. Incoming groups can be accepted in one prompt, and Android can queue multiple selected files in order.

## Version 0.5.9.5

- **Low-latency screen sharing.** The browser now negotiates a direct WebRTC video stream using only local network candidates; it does not require STUN, a relay or internet. The old MJPEG stream remains as a browser fallback and is only JPEG-encoded when needed.
- **No multi-second frame queue.** The fallback viewer keeps reading while decoding and draws the latest frame, dropping stale frames if the browser falls behind. The screen video and canvas both use a fitted full-viewport fullscreen layout.
- **Files can be sent from Chat on either device.** Attach multiple photos, videos or other files; the receiver gets a prompt and can download/open from the shared conversation.
- **A full music listening view.** Album artwork, searchable song queue, playback/seek/volume controls, shuffle, repeat, previous and next are together in a responsive now-playing layout.
- **Larger transfer blocks.** Multi-gigabyte uploads remain streamed with no app size cap; transfers still need enough phone storage and a stable hotspot. The server now uses 1 MiB blocks for upload writes.

## Version 0.5.9.4

- **No fixed upload-size cap.** Laptop uploads are streamed to the phone's private inbox in small blocks, and phone-selected files are copied the same way. The app checks free storage before and during a transfer and removes incomplete files. A 15 GB movie can transfer when the phone has enough free space; device storage and the connection still set the real limit.
- **Shared files appear in Chat.** Each side sees a file entry and confirms before downloading/opening it.
- **Added a Music tab** with song search, shuffle, previous/next and automatic next-track playback.
- **Added file size filters and a remembered list/grid switch**, and included documents and APKs in the Files collection.
- **Centered video playback** with viewport-safe fullscreen, 10-second seek buttons and aspect-ratio choices.
- **Screen sharing no longer restarts capture when the laptop viewer connects**, which could leave the viewer waiting on its first frame.

The browser requires a known file length for direct uploads. Standard browser file pickers provide this; transfers without a valid size or with insufficient phone storage are rejected cleanly.

## Version 0.5.9.3

- **The laptop tabs now actually switch sections.** 0.5.9.2 added the bar but each section's own CSS kept it visible, so every tab showed everything. A single rule makes hiding win.
- **The redundant "Library · Screen · Transfer" jump links are gone** — the bottom bar replaces them.
- **The media-type chips (All, Photos, Videos, Audio, Files) belong to the Files tab** and no longer appear under Screen or Chat.

## Version 0.5.9.2

What's new:

- **The laptop page has the same four tabs** — Connect, Screen, Files and Chat — along the bottom, matching the phone. Your last tab is remembered.
- **Laptop files on the phone.** The Library tab now reads the Windows helper's folder as well, with a search box that filters the list as you type, so you no longer need a second window.
- **Album art for music.** Tracks that carry embedded artwork now show it in the gallery, the way videos show a thumbnail. Tracks without art keep the note icon.
- **A sturdier screen stream.** The browser reads the stream frame by frame and draws it on a canvas, so a dropped connection reconnects on its own, the state line reports how many frames have arrived, and the phone's own setup error is shown instead of a generic message.
- **Screen diagnostics.** `/api/screen/status` now reports frame count, how long ago the last frame arrived, and the capture size.
- **Faster transfers.** File copying used a 512 KB buffer at this version.
- **Oversized uploads are refused immediately**, before the phone reads any of the body, instead of after draining it.

Tests: 39 in total, including 14 that start the real server and drive it over HTTP — the page, the code gate and its rate limit, chat in both directions, sending a file to the phone and reading it back, byte ranges, path traversal, and a 20 MB upload being refused.

## Version 0.5.9.1

A correctness pass over the whole app, with automated tests for each feature.

What's new:

- **Automated tests.** The project now has JUnit tests for byte ranges, HTTP status lines, upload limits, ZIP entry names, media kind filters, MIME classification and the share chat, plus a script that checks every route the browser page calls exists on the phone. All of it runs in GitHub Actions on every push.
- **Library filters that actually filter.** The phone asks for `image` while the server expected `images`, so the Photos, Videos, Audio and Files chips returned nothing. Both spellings now mean the same thing.
- **Uploads are checked, not truncated.** A file larger than 16 MB used to be cut to 16 MB and reported as sent. It is now refused with a clear message, and a connection that drops mid-upload is reported instead of saving a half file.
- **Correct HTTP status lines.** Replies now say `201 Created`, `413 Payload Too Large` and `500 Internal Server Error` instead of calling everything a bad request.
- **Chat senders are normalised.** A message can only be recorded as coming from the phone or the laptop.
- **Adaptive icon for themed launchers.** The launcher icon gained a monochrome layer, so Android 13+ themed icons render properly.

## Version 0.5.9

What's new:

- **Four tabs on the phone.** Connect, Screen, Library and Chat, chosen from a bottom bar with an icon for each. The selected tab turns green.
- **Chat on both sides.** The phone now shows the same share chat the laptop sees and can answer from the Chat tab. Messages are kept on the phone and never leave the local network.
- **Library on the phone.** Photos, videos, audio and files, filterable, newest first. Tap an item to open it in the app that handles it.
- **Laptop screen on the phone.** Paste the Windows helper link in the Screen tab and its live view opens inside PocketBridge.
- **A live screen again.** The screen stream no longer freezes on a still picture: connecting restarts the capture to force a fresh frame, and the server repeats the last frame so the browser stream never looks dead.
- **Video thumbnails that load.** Thumbnail building is capped at two at a time, because a page of video previews used to start a dozen decoders at once and run the phone out of memory. Two extra fallbacks handle providers that hand back an unseekable file.
- **A real app icon.** A green rounded icon with a white arrow replaces Android's default.

Everything from 0.5.7:

- **Initial offline screen sharing.** Version 0.5.7 used local MJPEG frames over HTTP. Version 0.5.9.5 now prefers WebRTC for lower latency and keeps MJPEG as a fallback; both paths stay on the local hotspot and require no internet relay.
- **More reliable media labels.** If Android reports a generic MIME type, PocketBridge identifies common photo, video, and audio formats from the filename extension.
- **More reliable video response headers.** The server infers common MIME types and can stream files whose provider does not report a size instead of returning an empty response.

The original local MJPEG mode follows the broad HTTP-streaming approach used by [ScreenStream's offline local mode](https://github.com/dkrivoruchko/ScreenStream); PocketBridge's implementation is separate and integrated with its existing sharing server.
- **Stop screen share** from the phone (the button changes to "Stop screen share" while sharing). Stopping the share also stops screen capture.
- **Video thumbnails on every supported Android version** (they were missing on Android 8 and 9). A frame from about one second in is used instead of a possibly black first frame.
- **Sharper thumbnails**, lazy-loaded so the first page appears faster, grouped by day (Today, Yesterday, dates).
- **Fast photo viewer** that loads a phone-made JPEG preview (works for HEIC and AVIF too), with Previous/Next, arrow keys, swipe, and an Original download. Escape now closes the photo viewer.
- **Select several files and download them as one ZIP** (up to 300 at a time).
- A sturdier phone web server: a fixed worker pool so a busy gallery or a playing video cannot block new connections.

Everything from earlier versions:

- Two ways to connect, chosen on the phone: **Wi-Fi mode** (laptop joins the same Wi-Fi as the phone) and **Hotspot mode** (the phone creates its own private Wi-Fi, or reuses the hotspot you already switched on).
- Open a short link like `http://192.168.43.1:43117` and enter a 6-digit code shown on the phone, instead of typing a long token.
- The file server starts before any network work, and the link is worked out from three independent sources, so a share always produces a link or a precise reason why not.
- The phone shows a Diagnostics card (server state, chosen address, every address it found) and an "Also try these links" card.
- Browse photos, videos, audio **and files**, play video and audio in the browser with 10-second skip controls, or download the originals. If a browser cannot decode a video, PocketBridge makes an H.264/AAC copy on the phone for playback.
- Load the media gallery in pages, cache previews on the laptop, and seek within original video files using byte ranges.
- Show the nearby network name, hotspot password where Android makes it available, and one-time browser link.
- Stream the phone screen directly to the browser over the local network.
- Control the Windows laptop from the phone using the separate portable, open-source helper in this release. The helper is a companion because Windows requires a PC-side screen/input process.
- Keep file and screen data on the local hotspot; no cloud relay or account.

The Windows helper is unchanged from 0.3.0 and is provided as a separate ZIP; it is not installed as a service. It is based on the open-source [web-remote-desktop](https://github.com/tacitech/web-remote-desktop) project under its MIT license. PocketBridge repaired the upstream offline JPEG fallback; FFmpeg is optional and enables H.264/HEVC. Its optional internet tunnel is not used for hotspot sharing. Android will show its system consent dialog whenever phone screen capture starts. Protected content may appear black because Android apps can block screen capture.

Wi-Fi mode shows the current network name when Nearby Wi-Fi access is allowed. Android does not let PocketBridge reveal the saved Wi-Fi password; enter your usual router password on the laptop if it is not already connected. Phone-created hotspot credentials are shown by Android's hotspot configuration.

For illustrated setup steps, see the [Quick Start Guide](docs/QUICK_START_GUIDE.md).

## Build

Use Android SDK Platform 35, Build Tools 35.0.0, Gradle 9.1.0, and JDK 17 or newer. Then run:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The release APK is built for 64-bit ARM (`arm64-v8a`) to keep the download small; this matches current Android phones, including Android 15 devices. The browser loads the media library in small pages, thumbnails are cached on the laptop, and video playback streams the original file with byte-range seeking. Video and audio have 10-second skip controls. Other ABIs can be enabled in `app/build.gradle.kts` if needed.
To build the larger universal APK with all WebRTC Android ABIs, run `.\gradlew.bat assembleDebug -PpocketbridgeUniversal=true`.

## Use

See [INSTALL.md](INSTALL.md) for simple phone and laptop setup steps.

1. Install the APK on Android and grant nearby Wi-Fi and media permissions.
2. Choose a mode: **Wi-Fi mode** if the laptop is already on the same Wi-Fi as the phone, or **Hotspot mode** to have the phone create its own network.
3. Join the network named on the phone screen (in Wi-Fi mode there is nothing to join).
4. Open the short link in the laptop browser and enter the 6-digit code shown on the phone.
5. For screen sharing, tap **Share phone screen** in the app and approve Android's capture prompt. Then choose **Watch screen** in the browser.
6. For PC control, use **Download Windows helper** on the paired laptop page. Extract the ZIP, open `Start-Control.bat`, and paste the Wi-Fi adapter’s printed **LAN** link into PocketBridge on the phone. Keep the helper window open while connected. If Windows Firewall asks, allow it on private networks only.

Android 8.0 (API 26) and newer are supported by the project. Android 14 and 15 may ask for access to selected photos and videos; grant access to the items you want to share.

## Security

The phone host uses a random 6-digit code for the browser, rate limited against guessing, plus a long token for legacy links. Keep both links private and close sharing when finished. The local-only hotspot uses Android-generated Wi-Fi credentials. Files and screen frames use plain HTTP on the local network; the screen stream is not end-to-end encrypted. Use a private hotspot and do not share the code or link with people you do not trust. The third-party PC helper's control page is also plain HTTP on the local hotspot.

## Open source

This project is licensed under Apache-2.0. WebRTC and FFmpegKit are separately licensed; see `THIRD_PARTY_NOTICES.md` for dependency notices. The video compatibility library makes the phone APK larger.
