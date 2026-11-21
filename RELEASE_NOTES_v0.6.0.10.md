# PocketBridge 0.6.0.10

## Offline laptop sharing

Install the universal APK and start a hotspot share. Join the hotspot in Windows, open the phone link in Edge, and enter the code. From Screen or Camera, download the matching helper from the phone, extract the ZIP into a new folder, close the older helper window if it is open, and run Start-Control.bat.

Choose the offline workspace link and enter the current phone code there. The workspace runs on the laptop’s loopback address and communicates with the phone directly over the hotspot. Screen lets you choose a tab, window or display, then view it under **Screen → Watch Edge screen** on Android. Camera lets the laptop share its webcam or a screen while receiving the phone camera. Start the camera session on the phone and approve capture on both devices.

The laptop screen viewer can show the latest JPEG frame over the hotspot while the direct video connection is unavailable. The phone releases that frame when the presentation stops.

## Playback and hotspot changes

- Compatible movies still play from their original files. For unsupported media, the phone reads directly from Android storage and streams a fragmented MP4 while it prepares the playback copy. AVC video can retain its original video track while audio is converted to AAC.
- Movies that stall before the browser reports a codec error now offer **Try compatible playback**. A completed compatibility copy supports byte-range seeking. Conversion needs free space for the output and a working decoder and encoder on the phone.
- Hotspot setup checks for the existing network even when Android delays its creation callback. Interface discovery retains explicit hotspot adapter information when Android also reports the same address as Wi-Fi.
