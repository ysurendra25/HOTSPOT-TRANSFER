# PocketBridge 0.5.9.7

## What's new

- Universal Android APK for ARM and x86 devices.
- Phone screen sharing captures the display at its reported pixel dimensions, adapts on rotation, and gives WebRTC more bitrate and frame-rate headroom.
- The browser starts a local preview immediately while attempting the direct WebRTC stream. It labels the active transport and reports when it falls back to JPEG.
- The Windows helper defaults to native display width, 30 fps, and its highest quality setting. The settings panel still lets you choose a lower-bandwidth profile.
- Original videos continue to stream from their source files with HTTP byte-range support; no conversion is applied automatically. Android now offers VLC or another installed player for videos from the phone library, laptop library, or chat.
- Chat file attachments display as separate cards with type, filename, size, and an open action.
- Authenticated Windows controls: lock, request display wake, sleep, schedule shutdown with a 15-second cancel window, or cancel a scheduled shutdown.

## Important behavior

- Screen sharing is encoded video, so it is not mathematically lossless. Source pixel dimensions are preserved by default where the device encoder and hotspot can sustain them; a congested hotspot or decoder can still lower effective detail or frame rate. Use the in-app quality settings to reduce bandwidth if the stream stutters.
- The Windows helper must be running on the laptop for laptop files, laptop screen, or PC controls to work. Keep it on a Private network and protect its access link.
- Windows does not allow PocketBridge to bypass sign-in. “Wake display” can request that the monitor turn on; enter the Windows PIN or password on the laptop to unlock it. Sleep ends the hotspot connection. Shutdown is delayed 15 seconds and can be canceled from the helper page.
- VLC must already be installed to use the VLC action.

## Install

Download `PocketBridge-0.5.9.7-universal.apk` from this release, install it on Android, and allow the requested local network/media permissions. For laptop screen/files/control, download and extract the Windows Control ZIP, then run `Start-Control.bat` once per session and paste the printed local link in PocketBridge's Screen tab.
