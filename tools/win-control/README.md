# PocketBridge Windows control

This optional companion is based on [TaciTech web-remote-desktop](https://github.com/tacitech/web-remote-desktop), release `v0.1.0` (MIT). PocketBridge patches the upstream server with a multipart JPEG screen stream so the control page still works without FFmpeg or internet access. H.264/HEVC remains available when FFmpeg is present.

## Use it

1. Start **Start local share** in PocketBridge on Android.
2. Connect Windows to the Wi-Fi name and password PocketBridge shows. Keep Windows connected even if it reports no internet.
3. Extract `PocketBridge-Windows-Control-<version>.zip` and double-click `Start-Control.bat`.
4. If Windows Firewall asks, allow access on private networks only.
5. In PocketBridge on the phone, paste the printed **LAN** link for the laptop’s Wi-Fi adapter into **Connect** → **Windows helper link**. If several links appear, match the address to **Wi-Fi → IPv4 Address** in Windows `ipconfig`. PocketBridge checks the helper identity at that address. The `127.0.0.1` link opens on the laptop itself. The helper saves its current LAN links in `pc-remote-links.txt`. If another window already has the helper running, return to that window for the active link.
6. Tap or drag the laptop image to move/click. Use the page's keyboard, scroll, right-click, and trackpad controls.
7. Close the helper window when finished. This stops the helper running in that window. Stop the phone's local share separately.

The helper shares the folder configured by `share_dir` in `config.json` (Downloads by default), including nested folders. Very large folders are listed in pages: at most 20,000 files are read, within about four seconds, newest first, and system folders such as AppData, node_modules and $Recycle.Bin are skipped. Pick a smaller folder on the phone to see files beyond that.

## QR Share

Open `http://127.0.0.1:7070/qr.html?t=<helper-code>` in Edge on the laptop. It can display a QR for a small laptop file or text, or scan the phone's QR frames. The phone can also display QR frames for the laptop camera to scan. This is optical transfer only and is capped at 128 KiB; use the hotspot for photos, videos, and other large files. The helper page is served locally by the portable process and does not use the internet.

## Power controls

The phone's **Laptop controls** send `POST /power` with `{"action": "...", "confirm": true}`:

- **Lock laptop** – `LockWorkStation`.
- **Wake display** – turns the panel on (monitor-power message plus a zero-distance mouse move) and asks Windows to keep it on.
- **Wake & show sign-in** – wakes the display so the lock screen's PIN or password box is ready. Windows sign-in cannot be bypassed remotely; enter the PIN or password on the laptop.
- **Sleep** (asks first) – enables the shutdown privilege, releases the helper's keep-awake hold and calls `SetSuspendState`; on Modern Standby laptops without S3 sleep it turns the display off so Windows enters standby.
- **Shut down** (asks first) – enables the shutdown privilege and calls `ExitWindowsEx`, falling back to `shutdown /s /t 0`.

A laptop that is asleep or shut down cannot be woken over Wi-Fi by the helper. Keep the helper running while the phone browses files or opens the laptop screen. Its default screen preset is tuned for a local hotspot; the screen settings still let you choose a sharper or lower-bandwidth stream.

The helper runs as a portable process; it does not install a Windows service or need a cloud tunnel. Keep the control URL private. Its local HTTP page is protected by a random token, and anyone with that URL and access to the hotspot can control the laptop.

## Build from source

From the repository root, `./gradlew :app:packageWindowsHelper` builds the portable Windows x64 ZIP in `app/build/generated/windowsHelperAssets/`. The Android build runs this task automatically. For a universal APK on Windows, run `.\gradlew.bat assembleDebug -PpocketbridgeUniversal=true` with Go and the Android SDK installed. The paired browser can download that build's matching helper from the phone while offline.

The upstream MIT-licensed source is included under `upstream-v0.1.0/web-remote-desktop-0.1.0`. Install Go 1.26 or newer (the release build uses Go 1.27), then run `./build.ps1`. The script runs upstream Go tests and produces `dist/pc-remote.exe`. It includes PocketBridge's `/jpeg` handler and browser fallback; the release ZIP includes a prebuilt executable so the laptop does not need Go.

The first run creates `config.json` and `pc-remote.log` beside the executable. The running console prints the pairing links. The log contains startup and connection diagnostics. For no-internet use, FFmpeg is optional: PocketBridge's JPEG fallback works without it. FFmpeg enables more bandwidth-efficient H.264/HEVC streaming.
