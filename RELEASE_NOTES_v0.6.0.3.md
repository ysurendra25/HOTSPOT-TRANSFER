## PocketBridge 0.6.0.3

Fixes for the mobile app freezing, laptop files not loading, half-frame screen sharing, the laptop power controls and the laptop screen on the phone. Version name 0.6.0.3, version code 27.

### Mobile app freezes
- The phone's web server no longer runs out of workers. It used a fixed 16 workers with a waiting queue; a few long streams (screen, video, downloads) plus idle or stalled connections left every new request waiting, so the laptop page and the phone's own checks hung. Workers are now created on demand up to 64, and past that a request gets an immediate "busy" answer.
- The screen stream loop could spin at full CPU once the JPEG encoder was switched off by a WebRTC viewer. It now waits properly, keeps the encoder on while any JPEG viewer watches, and turns it off when the last one leaves.
- A screen viewer that stops reading (laptop asleep, hotspot dropped) is closed after 10 seconds instead of holding a worker forever, a reconnecting laptop replaces its older screen stream (at most two), and stopping the share now closes every open connection.
- Chat is read off the main thread and redrawn only when it changed; sending and clearing messages also run in the background. Library reads, laptop file lists and power commands use a small bounded worker pool instead of a new thread per tap, and stale library reads can no longer add duplicate rows.
- Chat is written atomically, so an interrupted write cannot leave an unreadable conversation.

### Laptop files on the phone
- The helper lists its shared folder in pages with a file cap and a time budget, skips system folders (AppData, node_modules, $Recycle.Bin, …), and caches the walk briefly so later pages are instant. A whole-drive folder no longer times out the phone.
- The phone loads the list page by page with retries and longer read timeouts, shows progress, and says when a very large folder was only partly read.
- One link parser for all laptop features: stray spaces, quotes, a missing `http://` or a whole copied log line are accepted, and a missing access code is explained.
- When the pasted address does not answer (for example the helper printed the address of another adapter) or is the laptop's own 127.0.0.1, the phone finds the helper on its hotspot by itself and remembers the working link.
- Errors now show the helper's own message (wrong code, missing folder, firewall/unreachable, timeout) instead of a bare HTTP status.
- The helper prints every private address (the one it routes to the internet first) and Start-Control.bat shows this run's links from a file that is replaced on each start, never an old link from the log.

### Phone screen on the laptop: half frame, quality and delay
- Fixed the half-height picture: while the direct stream was starting, the hidden video and the JPEG preview were stacked and each took half of the viewer. They now share one cell.
- The JPEG fallback cuts frames by their Content-Length instead of scanning for JPEG markers, so a frame is never drawn cut short.
- The capture follows rotation on every Android version (before Android 14 nothing resized it, squeezing a landscape screen into a portrait capture).
- Original quality: WebRTC keeps native resolution with maintain-resolution degradation, a 25 Mbps ceiling, an 8 Mbps start and a 1.5 Mbps floor (up from a 6 Mbps cap), up to 60 fps, and the browser asks for zero playout delay.
- The JPEG fallback now runs at native resolution and quality 88 (was 1280 px / quality 65), with faster plane copies, and still sends only the newest frame.

### Laptop power controls
- Sleep and shut down now enable the Windows shutdown privilege first; without it Windows refused them while the phone showed "sent". Sleep releases the helper's keep-awake hold and falls back to display-off standby on Modern Standby laptops. Shut down uses ExitWindowsEx and falls back to `shutdown /s /t 0`.
- The power buttons now find the laptop the same way files and screen do; a loopback or stale link used to send them to the phone itself.
- The helper answers every power request in JSON with a sentence the phone shows, accepts older action names, lists its actions on GET /power, and answers CORS preflights.
- "Unlock" is now labelled "Wake & show sign-in (PIN needed)". Windows sign-in cannot be bypassed remotely: it wakes the display so the PIN or password box is ready on the laptop.

### Laptop screen on the phone
- The phone viewer allows muted video to start without a tap (WebView blocked it, leaving the screen black), accepts the helper's cookies, and the helper page sends its access code on every request instead of depending on the cookie.
- When video playback is refused, the helper page switches to its JPEG stream, which it now reads frame by frame so the watchdog sees a live stream.
- The helper's JPEG stream now wakes the display and holds it on, like the H.264 path. Before, a laptop whose display had idled off never produced a JPEG frame.

### Other fixes
- Wrong-code lockout: after 20 wrong codes in a minute the gate stops checking codes at all (it used to still accept the right one, so it did not stop guessing). The phone's own token link still works, and the laptop page says when it is locked out.
- Received files are served with their real type, so photos, videos and PDFs open in place. Chat attachments open with their type on the phone.
- `go test ./...` runs on non-Windows machines again (a Windows-only test was moved behind a build tag).
- The release workflow runs for any `v*` tag, can be started by hand for an existing tag, picks `RELEASE_NOTES_<tag>.md` from the tag, and checks that the app version matches the tag.

### Checked before release
Android unit tests, lint and the debug and universal APK builds; the browser-page check and `node --check` on the page and helper scripts; `go vet`, `go test` and the Windows amd64 build of the helper. These run on a build machine, not a phone or a Windows PC: the screen sharing, WebView viewer, hotspot discovery and Windows power actions still need a check on a real phone and laptop.
