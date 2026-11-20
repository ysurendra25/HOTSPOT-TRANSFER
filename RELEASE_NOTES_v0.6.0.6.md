# PocketBridge 0.6.0.6

This release republishes the 0.6.0.5 upgrades as a new version and produces the universal Android APK.

- Video player controls include fullscreen, seeking, volume, playback speed, and expanded aspect-ratio choices while retaining original-source playback.
- Media categories remain in their intended tabs, and the Music tab is limited to the music library.
- Chat is scrollable and incoming attachments require an explicit user action to download, view, or play.
- Two-way local hotspot sharing, byte-range media playback, and streamed file transfers are retained.

### Verification

The release workflow runs Android unit tests, lint, browser route checks, and builds and signs the universal APK. Real-device hotspot transfer and screen-sharing behavior should be checked on Android hardware.
