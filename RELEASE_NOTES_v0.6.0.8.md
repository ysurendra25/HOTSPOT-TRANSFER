# PocketBridge 0.6.0.8

This release addresses screen-share startup stalls, camera status races, and fullscreen video sizing.

- Moved Android screen encoder and capturer setup off the service/UI thread. Startup and teardown now run in order on a worker so a stop or retry cannot release WebRTC resources while initialization is using them.
- Added visible screen-share startup/cancel state and surfaced setup errors in the app.
- Changed screen sender adaptation to a 500 kbps–12 Mbps range and up to 30 fps. WebRTC can reduce resolution/frame rate when the hotspot is congested, avoiding seconds-old frames building up behind an oversized bitrate target. When the link has capacity it can use the phone's full captured resolution.
- Fixed camera startup polling so the app waits for camera initialization, prevents duplicate starts, permits cancellation, and keeps camera/permission errors visible after the service exits.
- Fixed fullscreen video layout so Original and selected aspect ratios remain centered and fit the available viewport; Fill and Stretch use the available stage area.
- Added a regression check for fullscreen and windowed aspect-ratio geometry to the release workflow.

## Verification

- `python tools/check-web-page.py` passes.
- `node tools/test-player-layout.js` passes.
- `gradlew.bat testDebugUnitTest lintDebug` passes on the build machine.
- Windows helper `go test ./...` and `go vet ./...` pass.
- The build machine has no Android device attached, so a live phone/hotspot pass for camera permission, screen capture, Wi-Fi discovery, and actual file throughput remains unverified here. These flows require the installed APK and both devices on the same local hotspot.
