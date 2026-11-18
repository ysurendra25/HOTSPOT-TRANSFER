# PocketBridge 0.5.9.6

This release improves two-way laptop/phone sharing and makes incoming chat attachments easier to identify.

- The mobile Library now has separate **Files on this phone** and **Files on the laptop** views, so a long phone gallery no longer hides laptop files. Laptop file search and Photos, Videos, Audio and Other filters are in the laptop view.
- The Windows helper lists files recursively inside its configured shared folder. It returns media types and serves photos, audio, video and PDFs inline with byte-range support, so Android viewers can stream supported video formats instead of downloading a forced attachment.
- The laptop helper link is saved once and shared between the mobile Screen and Library views. The laptop viewer now has a larger hardware-accelerated WebView and clearer link, connection and load status.
- Windows screen streaming now starts with a hotspot-friendly balanced profile, migrates the previous built-in profile, reuses JPEG buffers and keeps H.264 available when FFmpeg is installed.
- Phone-to-laptop WebRTC capture now caps the stream at 5 Mbps and 24 fps to reduce queueing while file transfers use the same hotspot.
- Chat attachments show sender, timestamp, file type and size. Incoming multi-file shares prompt once for the group, and the phone file picker supports multiple selection with transfers queued in order.

Validation: browser route checks, Android unit tests, lint, APK builds, and Windows helper tests/build run in GitHub Actions. The live Edge session was checked against the connected phone server. A physical Android test is still required to verify the laptop helper viewer and screen latency on the user's handset and hotspot.
