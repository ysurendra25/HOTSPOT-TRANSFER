# PocketBridge 0.5.9.5

This update focuses on screen-share latency and layout, chat file sharing, and the music player.

## Screen sharing

- The browser first negotiates a direct WebRTC video stream over the local network, using host candidates only. It does not need internet, STUN servers, or a cloud relay.
- If WebRTC is unavailable or cannot connect on a particular browser/network, PocketBridge falls back to the MJPEG stream.
- The MJPEG viewer keeps draining network data while decoding and displays the newest complete frame instead of letting slow decoding build a seconds-long queue.
- The live video and fallback canvas now fit the viewport in fullscreen and preserve the phone's aspect ratio.
- JPEG fallback encoding starts only when the browser needs that mode, avoiding its extra CPU work during WebRTC sharing.
- Capture stays within one Android MediaProjection session. This avoids restarting the virtual display when a laptop viewer connects, which is incompatible with Android 14+ capture-session rules.

## Chat files

- The laptop Chat tab can attach multiple photos, videos, and files.
- The phone Chat tab can open Android's document picker and send any openable file to the laptop.
- New incoming attachments show an alert. The recipient chooses whether to download/open; the file is also listed in the chat history.

## Music

- The Music tab has a now-playing panel, embedded artwork, a searchable song queue, play/pause, seek, volume, previous/next, shuffle, and repeat off/all/one controls.
- The layout adapts from a two-column desktop view to a stacked phone-width view.

## Transfer performance

- Uploads remain streamed, with no PocketBridge file-size cap. The phone must have enough free storage for laptop-to-phone uploads, and a stable local hotspot is still required.
- Upload writes and phone file-picker copies use 1 MiB blocks.

## Verification

- The web route, DOM element, and JavaScript syntax check passes.
- Android unit tests and lint run in the release workflow before APK packaging.
- The Windows helper is tested and packaged by the same release workflow.
- WebRTC screen capture, rotation/fullscreen rendering, hotspot throughput, and very large physical-file transfers still need testing on a real Android phone and laptop; automated CI cannot emulate those hardware/network conditions.
