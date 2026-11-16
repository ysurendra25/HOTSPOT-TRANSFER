# PocketBridge

PocketBridge is an open-source Android app for sharing phone media and the phone screen to a laptop browser over a local-only Wi-Fi hotspot. An optional portable open-source Windows helper also lets the phone view and control the laptop. Both workflows work without mobile data or internet access.

## Version 0.3.0

- Start a private local-only hotspot from the phone app.
- Show the network name, password, and one-time browser link.
- Browse photos, videos, and audio from the laptop browser.
- Play media in the browser or download selected files.
- Stream the phone screen to the browser using WebRTC with hardware H.264 where available.
- Control the Windows laptop from the phone using the separate portable, open-source helper in this release. The helper is a companion because Windows requires a PC-side screen/input process.
- Keep file and screen data on the local hotspot; no cloud relay or account.

The Windows helper is provided as a separate ZIP; it is not installed as a service. It is based on the open-source [web-remote-desktop](https://github.com/tacitech/web-remote-desktop) project under its MIT license. PocketBridge repaired the upstream offline JPEG fallback; FFmpeg is optional and enables H.264/HEVC. Its optional internet tunnel is not used for hotspot sharing. Android will show its system consent dialog whenever phone screen capture starts. Protected content may appear black because Android apps can block screen capture.

## Build

Use Android SDK Platform 35, Build Tools 35.0.0, Gradle 9.1.0, and JDK 25. Then run:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The release APK is built for 64-bit ARM (`arm64-v8a`) to keep the download small; this matches current Android phones, including Android 15 devices. Other ABIs can be enabled in `app/build.gradle.kts` if needed.
To build the larger universal APK with all WebRTC Android ABIs, run `.\gradlew.bat assembleDebug -PpocketbridgeUniversal=true`.

## Use

See [INSTALL.md](INSTALL.md) for simple phone and laptop setup steps.

1. Install the APK on Android and grant nearby Wi-Fi and media permissions.
2. Tap **Start local share**. The app creates a local-only hotspot, which has no internet connection.
3. Connect the laptop to the SSID and password shown in the app.
4. Open the one-time local link in the laptop browser.
5. For screen sharing, tap **Share phone screen** in the app and approve Android's capture prompt. Then choose **Watch screen** in the browser.
6. For PC control, download and extract `PocketBridge-Windows-Control-v0.3.0.zip` on the laptop, then double-click `Start-Control.bat`. Keep the helper running, and open the printed link on your phone while it is connected to the PocketBridge hotspot. If Windows Firewall asks, allow it on private networks only.

Android 8.0 (API 26) and newer are supported by the project. Android 14 and 15 may ask for access to selected photos and videos; grant access to the items you want to share.

## Security

The phone host and PC helper each use temporary random link tokens. Keep both links private and close both services when finished. The local-only hotspot uses Android-generated Wi-Fi credentials. Phone screen video is sent peer-to-peer with WebRTC encryption; files are streamed directly from the phone over the private hotspot. The third-party PC helper's control page is plain HTTP on the local hotspot, so anyone who knows its link and joins that hotspot could control the PC.

## Open source

This project is licensed under Apache-2.0. The WebRTC Android library is separately licensed by its maintainers; see `THIRD_PARTY_NOTICES.md` for the dependency notice.
