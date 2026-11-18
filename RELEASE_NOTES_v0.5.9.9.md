## PocketBridge 0.5.9.9

- Added a dark appearance by default on Android and in the laptop browser, with a saved light/dark theme switch in the browser.
- Added Copy for chat messages in the Android app.
- Added Android's installed-player chooser for videos shared from either device.
- Added a laptop Open with chooser that discovers supported installed video players and streams the original local file URL to the selected player.
- Fixed video aspect-ratio sizing in the browser viewer, including fullscreen and window resize.
- Reduced compatibility screen-stream encoding load and aligned WebRTC setup timeouts to reduce stale-frame lag.
- Added a confirmed laptop Sleep action and fixed the Windows sleep API call. Lock, wake-display, and show-sign-in behavior now report helper errors instead of claiming to unlock Windows; Windows still requires its PIN/password.
