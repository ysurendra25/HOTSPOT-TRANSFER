# PocketBridge

PocketBridge is an open-source Android app for sharing phone media and the phone screen to a laptop browser over a local-only Wi-Fi hotspot. An optional portable open-source Windows helper also lets the phone view and control the laptop. Both workflows work without mobile data or internet access.

## Version 0.5.7

What's new:

- **Direct offline screen sharing.** The browser receives screen frames as a local MJPEG stream over the same HTTP connection as file sharing. It no longer depends on WebRTC ICE negotiation, STUN, a relay, or internet access. Frames are capped at 1280 pixels and 12 fps for hotspot use.
- **More reliable media labels.** If Android reports a generic MIME type, PocketBridge identifies common photo, video, and audio formats from the filename extension.
- **More reliable video response headers.** The server infers common MIME types and can stream files whose provider does not report a size instead of returning an empty response.

The local screen-stream design follows the same broad MJPEG-over-HTTP approach used by [ScreenStream's offline local mode](https://github.com/dkrivoruchko/ScreenStream); PocketBridge's implementation is separate and integrated with its existing sharing server.
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

Use Android SDK Platform 35, Build Tools 35.0.0, Gradle 9.1.0, and JDK 25. Then run:

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
6. For PC control, download and extract `PocketBridge-Windows-Control-v0.5.7.zip` on the laptop, then double-click `Start-Control.bat`. Keep the helper running, and open the printed link on your phone while it is connected to the PocketBridge hotspot. If Windows Firewall asks, allow it on private networks only.

Android 8.0 (API 26) and newer are supported by the project. Android 14 and 15 may ask for access to selected photos and videos; grant access to the items you want to share.

## Security

The phone host uses a random 6-digit code for the browser, rate limited against guessing, plus a long token for legacy links. Keep both links private and close sharing when finished. The local-only hotspot uses Android-generated Wi-Fi credentials. Files and screen frames use plain HTTP on the local network; the screen stream is not end-to-end encrypted. Use a private hotspot and do not share the code or link with people you do not trust. The third-party PC helper's control page is also plain HTTP on the local hotspot.

## Open source

This project is licensed under Apache-2.0. WebRTC and FFmpegKit are separately licensed; see `THIRD_PARTY_NOTICES.md` for dependency notices. The video compatibility library makes the phone APK larger.
