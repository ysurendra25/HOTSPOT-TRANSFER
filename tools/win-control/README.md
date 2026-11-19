# PocketBridge Windows control

This optional companion is based on [TaciTech web-remote-desktop](https://github.com/tacitech/web-remote-desktop), release `v0.1.0` (MIT). PocketBridge patches the upstream server with a multipart JPEG screen stream so the control page still works without FFmpeg or internet access. H.264/HEVC remains available when FFmpeg is present.

## Use it

1. Start **Start local share** in PocketBridge on Android.
2. Connect Windows to the Wi-Fi name and password PocketBridge shows. Keep Windows connected even if it reports no internet.
3. Extract `PocketBridge-Windows-Control-0.6.0.3.zip` and double-click `Start-Control.bat`.
4. If Windows Firewall asks, allow access on private networks only.
5. Open the printed `http://...:7070/?t=...` link on the phone. Use it while the phone is still connected to the PocketBridge hotspot.
6. Tap or drag the laptop image to move/click. Use the page's keyboard, scroll, right-click, and trackpad controls.
7. Press a key in the helper console when finished. Stop the phone's local share separately.

The helper shares the folder configured by `share_dir` in `config.json` (Downloads by default), including nested folders. Keep the helper running while the phone browses files or opens the laptop screen. Its default screen preset is tuned for a local hotspot; the screen settings still let you choose a sharper or lower-bandwidth stream.

## QR Share without a device network

With the helper running on the laptop, open `http://127.0.0.1:7070/qr.html?t=<helper-token>` in Microsoft Edge on that same laptop. This local page uses the laptop camera to read QR frames shown by the phone, and can display QR frames for a small laptop file or text for the phone to scan. The phone and laptop do not send data to each other over Wi-Fi, hotspot, Bluetooth, or the internet; the file is optically encoded in QR frames. The transfer cap is 128 KiB, so use the normal Wi-Fi/Hotspot share for photos and videos larger than that. Camera scanning needs a browser that supports `BarcodeDetector` (current Edge does).

The helper runs as a portable process; it does not install a Windows service or need a cloud tunnel. Keep the control URL private. Its local HTTP page is protected by a random token, and anyone with that URL and access to the hotspot can control the laptop.

## Build from source

The upstream MIT-licensed source is included under `upstream-v0.1.0/web-remote-desktop-0.1.0`. Install Go 1.26 or newer, then run `./build.ps1`. The script runs upstream Go tests and produces `dist/pc-remote.exe`. It includes PocketBridge's `/jpeg` handler and browser fallback; the release ZIP includes a prebuilt executable so the laptop does not need Go.

The first run creates `config.json` and `pc-remote.log` beside the executable. The log prints the URL to open. For no-internet use, FFmpeg is optional: PocketBridge's JPEG fallback works without it. FFmpeg enables more bandwidth-efficient H.264/HEVC streaming.
