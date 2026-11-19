## PocketBridge 0.6.0.2

- Make the video stage fill the fullscreen viewer while preserving the source aspect ratio.
- Keep native phone resolution for WebRTC screen sharing and run JPEG fallback conversion on a worker that drops stale frames.
- Wait for a decoded WebRTC frame before switching away from the visible JPEG fallback, and allow more time for hotspot ICE negotiation.
- Make the screen-share fallback status reflect its 1280-pixel, 20-fps cap and report direct-link failure reasons.
- Normalize Windows long and short temporary paths in helper tests.

This version has passed local Android unit tests, lint, APK build, browser-page checks, Go tests, vet, and helper build. Live Android-device screen-share verification is still required before publishing the GitHub release.
