# PocketBridge 0.6.0.5

- Reworked the laptop video player controls and original-quality playback surface with fullscreen, seek, volume, playback speed, and more aspect ratios.
- Fixed media tab separation: only Music belongs to the Music tab, and the library file categories stay within their own tabs.
- Increased chat height and scrolling so long conversations and many attachments remain easy to browse.
- Removed automatic incoming-file download prompts and kept every attachment as an explicit Download or Play/View action.
- Improved receiver loading, chat redraws, and background/off-main-thread handling to reduce freezes during two-way transfers.
- Preserved original file byte ranges and 1 MiB streaming blocks for fast hotspot downloads and uploads.

### Verification

- Android unit and tab-switching tests should pass through GitHub Actions or a local Gradle build with a valid Android SDK.
- Browser asset syntax and route checks should pass through the existing test suite.
- Live hotspot transfer, WebRTC screen sharing, and device-to-device file receiving require physical Android/laptop verification.
