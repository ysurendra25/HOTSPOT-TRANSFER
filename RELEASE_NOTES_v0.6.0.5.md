# PocketBridge 0.6.0.5

## Changes

- The web Music tab now remains dedicated to audio playback and its song queue. Video browsing and video type filters stay in Files.
- Reworked the video viewer sizing so the default Fit option follows the source video's aspect ratio and uses the original media URL. Added Fill (crop), Stretch, 21:9, 16:10, 16:9, 3:2, 4:3, square and portrait choices; playback speed, volume and supported-browser picture-in-picture controls; and visible loading/buffering/error status.
- Set video preload to metadata to avoid pulling extra media data before playback starts. Repeatedly opening files now removes prior pointer handlers and resets the stage layout.
- Expanded Chat history, preserved the reader's scroll position while polling, and skipped redraws when messages are unchanged. Incoming attachments no longer trigger an automatic browser download; the recipient starts a download by clicking Download.
- Added a Download to phone action for received Android chat attachments. It opens Android's save picker and copies the file in the background.
- Changed the Android laptop library to use a recycled, scrollable list. Laptop pages are accumulated off the UI thread and displayed after loading, instead of creating hundreds of buttons during each page update.

## Verification

- `python tools/check-web-page.py` — passed (10 API routes, 74 page element IDs, inline scripts and required feature markers).
- `gradlew testDebugUnitTest lintDebug assembleDebug -PpocketbridgeUniversal=true` — passed.
- Android unit tests — 85 passed, 0 failures, 0 errors.

Original media is requested directly and is not converted unless the browser reports that it cannot decode the format. Fullscreen display ratios can crop or letterbox video depending on the selected option and source dimensions. Playback smoothness and transfer speed still depend on the laptop/phone codecs, device load, storage and hotspot conditions; this build was not validated on a connected physical phone.
