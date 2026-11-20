# PocketBridge 0.6.0.7

This release focuses on the reported reverse sharing and camera issues.

- Replaced the unsupported Clone tab with an opt-in Camera tab. Phone and laptop users approve their own camera access. The helper opens the Edge camera page on `127.0.0.1`, and a local WebRTC connection shares both camera tracks directly over Wi-Fi or hotspot without an internet relay.
- Added code-protected camera status, offer and stop endpoints with localhost-only CORS and private-network preflight support.
- Added a Stop camera action to Android's active camera notification.
- Added streamed laptop-to-phone downloads in Library. Android checks/reserves allocatable storage for known file sizes, cleans incomplete downloads and records a successful transfer in Share chat.
- Fixed exact laptop file paths to list only that file. Clearing the path restores the helper's default Downloads folder. File matching uses the actual on-disk name so Windows case-insensitive paths work.
- Fixed nested touch scrolling in phone Share chat and enabled native vertical touch scrolling in the laptop browser chat.

Verification on the build machine:

- Android unit tests and `lintDebug` pass.
- Windows helper Go tests pass, including authenticated camera-page routing and exact-file selection.
- Browser route/element check and JavaScript syntax checks pass.

The build machine has no Android device connected, so the new Android-to-Edge camera call and real hotspot file transfer still need a live-device check after installation.
