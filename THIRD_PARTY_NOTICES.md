# Third-party notices

## WebRTC Android SDK

PocketBridge uses the precompiled WebRTC Android library from [webrtc-sdk/android](https://github.com/webrtc-sdk/android), published as `io.github.webrtc-sdk:android`. The library is distributed under the Apache License 2.0. Its upstream source and license are available in that repository.

## FFmpegKit video library

PocketBridge uses `dev.ffmpegkit-maintained:ffmpeg-kit-video:8.1.7` to prepare a browser-compatible copy when the browser cannot decode the original video. The package is distributed under the GNU Lesser General Public License v3.0 (LGPL-3.0). A copy of the license is in `THIRD_PARTY_LICENSES/FFmpegKit-LGPL-3.0.txt`; the maintained source and build configuration are at [ffmpegkit-maintained/ffmpeg](https://github.com/ffmpegkit-maintained/ffmpeg).

## Windows remote desktop helper

The optional Windows control helper is based on [TaciTech/web-remote-desktop](https://github.com/tacitech/web-remote-desktop), release `v0.1.0`, under the MIT License. PocketBridge adds an offline JPEG stream and browser fallback. The upstream source and MIT license are included under `tools/win-control/upstream-v0.1.0`; the release ZIP includes the complete upstream license as `UPSTREAM-LICENSE.txt`.
