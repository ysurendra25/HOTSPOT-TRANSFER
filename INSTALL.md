# Install and use PocketBridge

## Install the APK on Android 15

1. Download `PocketBridge-debug.apk` to your phone. You can copy it from your laptop by USB, or download it from the project's GitHub release when one is published.
2. Open the APK from the phone's Downloads app or Files app.
3. If Android asks, allow the app you used to open the APK to install unknown apps, then return and tap **Install**.
4. Open PocketBridge and allow nearby Wi-Fi and access to the media you want to share.

The debug APK is signed with a development key. It is suitable for personal installation. Future builds signed with a different key cannot update this installation in place; uninstall this copy first if Android reports a signature conflict.

## Share files or the phone screen

1. In PocketBridge, tap **Start local share**. Android creates a local-only Wi-Fi hotspot; mobile data and internet are not needed.
2. On the laptop, connect to the Wi-Fi name and password shown in the app. Choose to stay connected if Windows reports that the network has no internet.
3. Type the exact link shown in PocketBridge into the laptop browser. Keep the link private while sharing is active.
4. Browse, play, or download items in the browser. To share the screen, tap **Share phone screen** on the phone, approve Android's capture prompt, then tap **Watch screen** in the browser.
5. Tap **Stop local share** on the phone when finished.

File sharing sends the original file bytes over the local hotspot. Screen sharing uses a peer-to-peer WebRTC stream and Android will ask for capture approval each time. Neither feature uses a cloud account or relay.

## Offline use

Download the APK before going offline. After installation, PocketBridge creates its own local Wi-Fi network and the laptop connects directly to it. Internet access is not needed for file browsing, downloads, or screen streaming. The laptop may warn that the hotspot has no internet; keep it connected to use PocketBridge.
