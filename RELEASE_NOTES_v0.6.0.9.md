# PocketBridge 0.6.0.9

The universal Android APK includes the portable Windows x64 helper used by the laptop screen, camera and file features.

## Watching videos in Edge

Open Files, select Play, and press F or use the fullscreen button. The player uses the entire window. The title and controls overlay the picture and disappear after three seconds of playback. Move the mouse to bring them back. Fill screen preserves proportions and crops the edges to fill your display. Choose Original to keep every edge of the frame. The player remembers your choice on that browser.

Options include 16:9, 16:10, 4:3, 21:9, 1.43:1 and 1.90:1 IMAX, 2.35:1 and 2.39:1 CinemaScope, portrait, square, stretch and zoom. Zoom can also enlarge movies with black bars encoded in the video. Supported media streams directly from the original file. Existing compatibility playback is still available for formats Edge cannot decode directly.

Play now starts a video with one click from Files or Chat. Next and Previous also start the selected video. A file-size formatting error that interrupted the rendering of Chat attachments and Inbox files has been fixed.

## Sharing over a hotspot

1. Start Hotspot sharing in PocketBridge and join that hotspot from the laptop. Open the address shown under Connect and enter the current six-digit code. PocketBridge can reuse the Android hotspot if it is already running.
2. To see the phone screen in Edge, tap **Share phone screen** and approve Android capture. In Edge, open **Screen → Watch screen**. The viewer waits for the phone to get ready and can fall back to a JPEG stream if WebRTC fails.
3. For laptop screen or camera sharing, download the bundled Windows helper from the setup section in the Edge Screen or Camera tab. Extract the ZIP, run **Start-Control.bat**, and keep its window open.
4. In Edge, choose **Screen → Share laptop screen**. Select the display or window in the Edge picker. On Android, open **Screen → Watch Edge screen**.
5. For a two-way camera session, tap **Camera → Start camera session** on Android and **Camera → Open laptop camera** in Edge. Approve each device’s own camera prompt.
6. For laptop files and controls on Android, paste the helper’s printed Wi-Fi LAN link in **Connect → Windows helper link**. Reopening the launcher leaves the original helper and link active.

## Verification

The test suite covers Android hotspot selection and restart, file and helper downloads, screen signaling and stale sessions. The Edge regression plays landscape and portrait videos, checks fullscreen bounds, fit modes, skipping and controls, and decodes a changing test stream through the laptop presenter and receiver.

A live hotspot check used this version’s Edge player with a phone running the previous server. A 1920×1080 MP4 filled a 1920×1080 fullscreen viewport and resumed after seeking to 15:33. A 3090×2160 WebM also played and resumed after seeking. The two-device camera and screen regression tests used generated video sources. Physical capture with this APK on Android 15 is still unverified.
