# Install and use PocketBridge

## Install the APK on Android 15

1. Download `PocketBridge-v0.3.0.apk` to your phone from the project's GitHub release. You can also copy it from your laptop by USB.
2. Open the APK from the phone's Downloads app or Files app.
3. If Android asks, allow the app you used to open the APK to install unknown apps, then return and tap **Install**.
4. Open PocketBridge and allow nearby Wi-Fi and access to the media you want to share.

The release APK targets 64-bit ARM (`arm64-v8a`).

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

## Control the laptop from the phone

1. Start the PocketBridge hotspot and connect the laptop to it.
2. Download and extract `PocketBridge-Windows-Control-v0.3.0.zip` on Windows.
3. Double-click `Start-Control.bat`. If Windows Firewall asks, allow private network access only.
4. The helper prints a link in its window. Open that link on the phone while the phone is still connected to the laptop's PocketBridge hotspot.
5. Use the touch screen for mouse movement and clicks, and the keyboard controls on the page to type. Leave the helper running while connected. Press a key in its console when done to stop it.

The Windows helper is open-source software from [TaciTech web-remote-desktop](https://github.com/tacitech/web-remote-desktop), redistributed under MIT. Keep its access link private. The helper does not need an internet tunnel for this local-hotspot workflow. It can use JPEG streaming offline; FFmpeg is needed only for hardware H.264/HEVC video.
