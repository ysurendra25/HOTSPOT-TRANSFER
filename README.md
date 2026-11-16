# PocketBridge

PocketBridge is an open-source Android app for sharing phone media and the phone screen to a laptop browser over a local-only Wi-Fi hotspot. It is designed to work without mobile data, internet access, or a laptop installation.

## Planned first release

- Start a private local-only hotspot from the phone app.
- Show the network name, password, and one-time browser link.
- Browse photos, videos, and audio from the laptop browser.
- Play media in the browser or download selected files.
- Stream the phone screen to the browser using WebRTC with hardware H.264 where available.
- Keep file and screen data on the local hotspot; no cloud relay or account.

Remote control is out of scope for the first release. Android will show its system consent dialog whenever screen capture starts. Protected content may appear black because Android apps can block screen capture.

## Build

Use Android SDK Platform 35, Build Tools 35.0.0, Gradle 9.1.0, and JDK 25. Then run:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Use

1. Install the APK on Android 15 or later and grant nearby Wi-Fi and media permissions.
2. Tap **Start local share**. The app creates a local-only hotspot, which has no internet connection.
3. Connect the laptop to the SSID and password shown in the app.
4. Open the one-time local link in the laptop browser.
5. For screen sharing, tap **Share phone screen** in the app and approve Android's capture prompt. Then choose **Watch screen** in the browser.

The first build is intended for Android 15; broader Android versions will be checked before release.

## Security

The phone hosts the site only while a share session is active. The browser link contains a random session token. The local-only hotspot uses Android-generated Wi-Fi credentials. Screen video is sent peer-to-peer with WebRTC encryption; files are streamed directly from the phone over the private hotspot. Stop the session in the app when finished.

## Open source

This project is licensed under Apache-2.0. The WebRTC Android library is separately licensed by its maintainers; see `THIRD_PARTY_NOTICES.md` for the dependency notice.
