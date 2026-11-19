# PocketBridge 0.6.0.4

- Keep the 0.6.0.3 screen streaming, laptop display, chat, power control, and paged laptop library improvements.
- Make the phone Library show laptop files only. Search now reaches the Windows helper, and an exact Windows file path selects its containing folder.
- Add a full-screen laptop screen viewer on Android.
- Add camera-only QR sharing between the phone and the Windows helper for text and files up to 128 KiB. Use the local hotspot for larger files.
- Add a user-approved Android Work profile section for separately installed app copies. Android controls which apps can be installed; app data is not copied and ads are not removed.

### Checks

- Android unit tests, lint, and universal APK build: passed.
- Windows helper tests, vet, and Windows cross-build: passed.
- Browser page syntax and route checks: passed.
- Universal APK package version and local signature verification: passed.

### Install note

The published 0.6.0.3 APK was signed with a different CI debug certificate whose private key is unavailable. Android cannot install an update over that copy; uninstalling it first will remove its app data. This release workflow now requires a stable signing key so later versions can update consistently.

### Device verification

No Android device is attached to the build environment. The camera QR loop, Work profile setup, real laptop file access, and live screen-share latency need physical-device confirmation.
