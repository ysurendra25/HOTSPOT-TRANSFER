# Install and use PocketBridge

## Install the APK on Android 15

1. Download `PocketBridge-v0.5.0.apk` for 64-bit ARM phones. For other Android architectures, download `PocketBridge-v0.5.0-universal.apk.zip`, extract it, and use the APK inside.
2. Open the APK from the phone's Downloads app or Files app. If you downloaded the universal ZIP, extract it first.
3. If Android asks, allow the app you used to open the APK to install unknown apps, then return and tap **Install**.
4. Open PocketBridge and allow nearby Wi-Fi and access to the media you want to share.

The small release APK targets 64-bit ARM (`arm64-v8a`). The universal APK includes ARM and x86 variants.

The debug APK is signed with a development key. It is suitable for personal installation. Future builds signed with a different key cannot update this installation in place; uninstall this copy first if Android reports a signature conflict.

## Share files or the phone screen

1. In PocketBridge, pick a mode.
   - **Wi-Fi mode**: the laptop joins the same Wi-Fi this phone is on. Nothing to create, and it works on phones that refuse to start a hotspot.
   - **Hotspot mode**: the phone creates a private local-only hotspot. If your hotspot is already switched on in Android settings, PocketBridge reuses it instead of creating a second one.
2. On the laptop, connect to the Wi-Fi name and password shown in the app. Choose to stay connected if Windows reports that the network has no internet.
3. Open the short link shown on the phone (for example `http://192.168.43.1:43117`) and enter the 6-digit code. The link is short on purpose so it is easy to type; the code keeps it private.
4. Browse photos, videos, audio and files. Tap a video or audio item to play it — the player has -10s and +10s buttons, a seek bar and fullscreen. To share the screen, tap **Share phone screen** on the phone, approve Android's capture prompt, then tap **Watch screen** in the browser.
5. Tap **Stop local share** on the phone when finished.

File sharing sends the original file bytes over the local hotspot. Screen sharing uses a peer-to-peer WebRTC stream and Android will ask for capture approval each time. Neither feature uses a cloud account or relay.

## If the page will not open

1. Check the laptop is on the network named on the phone screen. In Wi-Fi mode both devices must be on the same Wi-Fi.
2. Turn off any VPN on the laptop; VPNs often block local addresses.
3. If the browser shows a security or connection error, turn off "Always use secure connections" / HTTPS-Only mode for this address — PocketBridge is plain HTTP on your own network.
4. If the page loads but the code is rejected, read the current code from the phone; a new share generates a new code.

## If the link never appears

PocketBridge first asks Android for a private local-only hotspot. Some phones refuse that while their own hotspot is on, and some refuse it while joined to another Wi-Fi network. In both cases PocketBridge falls back automatically:

1. It shares over the hotspot the phone already has, or
2. It shares over the Wi-Fi network the phone is already joined to, and asks you to put the laptop on that same network.

The share link stays random and private in every case. If neither fallback finds a usable address, turn the phone's hotspot off in Android settings and tap **Start local share** again.

## Offline use

Download the APK before going offline. After installation, PocketBridge creates its own local Wi-Fi network and the laptop connects directly to it. Internet access is not needed for file browsing, downloads, or screen streaming. The laptop may warn that the hotspot has no internet; keep it connected to use PocketBridge.

## Control the laptop from the phone

1. Start the PocketBridge hotspot and connect the laptop to it.
2. Download and extract `PocketBridge-Windows-Control-v0.5.0.zip` on Windows.
3. Double-click `Start-Control.bat`. If Windows Firewall asks, allow private network access only.
4. The helper prints a link in its window. Open that link on the phone while the phone is still connected to the laptop's PocketBridge hotspot.
5. Use the touch screen for mouse movement and clicks, and the keyboard controls on the page to type. Leave the helper running while connected. Press a key in its console when done to stop it.

The Windows helper is open-source software from [TaciTech web-remote-desktop](https://github.com/tacitech/web-remote-desktop), redistributed under MIT. Keep its access link private. The helper does not need an internet tunnel for this local-hotspot workflow. It can use JPEG streaming offline; FFmpeg is needed only for hardware H.264/HEVC video.
