# PocketBridge Windows control

This optional companion is based on [TaciTech web-remote-desktop](https://github.com/tacitech/web-remote-desktop), release `v0.1.0` (MIT). PocketBridge patches the upstream server with a multipart JPEG screen stream so the control page still works without FFmpeg or internet access. H.264/HEVC remains available when FFmpeg is present.

## Use it

1. Start **Start local share** in PocketBridge on Android.
2. Connect Windows to the Wi-Fi name and password PocketBridge shows. Keep Windows connected even if it reports no internet.
3. Extract `PocketBridge-Windows-Control-v0.3.0.zip` and double-click `Start-Control.bat`.
4. If Windows Firewall asks, allow access on private networks only.
5. Open the printed `http://...:7070/?t=...` link on the phone. Use it while the phone is still connected to the PocketBridge hotspot.
6. Tap or drag the laptop image to move/click. Use the page's keyboard, scroll, right-click, and trackpad controls.
7. Press a key in the helper console when finished. Stop the phone's local share separately.

The helper runs as a portable process; it does not install a Windows service or need a cloud tunnel. Keep the control URL private. Its local HTTP page is protected by a random token, and anyone with that URL and access to the hotspot can control the laptop.

## Build from source

The upstream MIT-licensed source is included under `upstream-v0.1.0/web-remote-desktop-0.1.0`. Install Go 1.26 or newer, then run `./build.ps1`. The script runs upstream Go tests and produces `dist/pc-remote.exe`. It includes PocketBridge's `/jpeg` handler and browser fallback; the release ZIP includes a prebuilt executable so the laptop does not need Go.

The first run creates `config.json` and `pc-remote.log` beside the executable. The log prints the URL to open. For no-internet use, FFmpeg is optional: PocketBridge's JPEG fallback works without it. FFmpeg enables more bandwidth-efficient H.264/HEVC streaming.
