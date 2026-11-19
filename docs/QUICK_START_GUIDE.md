# PocketBridge Quick Start (Android 15)

This guide shows how to share files and your phone screen with a laptop. The laptop needs a browser only; file sharing and screen streaming work over the local Wi-Fi link without mobile data or internet.

## 1. Connect the laptop

![Connect your laptop to the phone](images/quick-start-connect.svg)

1. Open PocketBridge on the phone and allow **Nearby Wi-Fi** access. Allow access to the photos and videos you want to share.
2. For the easiest offline connection, tap **Hotspot mode**. The phone shows a Wi-Fi network name, password, link, and six-digit code.
3. On the laptop, join the Wi-Fi network shown by PocketBridge. If Windows warns that this network has no internet, choose to stay connected.
4. Open the exact link shown on the phone. Type the six-digit code and tap **Open my files**.

**Wi-Fi mode:** tap it when the laptop is already on the same Wi-Fi as the phone. The laptop may need the usual router password to join. Android does not let PocketBridge read or show a saved Wi-Fi password.

## 2. Preview, play, or download files

![Browse the library and use the video controls](images/quick-start-files.svg)

- Photos and video thumbnails appear in the grid. Select **Photos**, **Videos**, **Audio**, or **Files** to narrow the list.
- Tap **View** or a photo preview to open a photo. Tap **Play** or a video thumbnail to open a video.
- In the player, tap **Play**, **↶ Back 10s**, or **Forward 10s ↷**. The seek bar also moves through the video; the left and right arrow keys skip ten seconds while the player is open.
- Use the **Music** tab for a searchable track queue, album artwork, shuffle/repeat, and playback controls.
- Use **Chat** → **Attach photos, videos or files** to send laptop files to the phone. On the phone, use **Chat** → **Share a photo, video or file** to send phone files to the laptop. The receiver is prompted before opening/downloading, and the attachment stays in the conversation.
- If your browser cannot decode a video's format, PocketBridge creates an H.264/AAC copy on the phone and switches to that copy. This can take a few minutes and uses temporary phone storage. **Download** always saves the original file.

Keep PocketBridge open on the phone while previews, playback, or downloads are in progress. Large files can take longer on a slow Wi-Fi connection.

## 3. Share the phone screen

![Start screen sharing from the phone and view it on the laptop](images/quick-start-screen.svg)

1. Keep the phone and laptop on the same Wi-Fi network or PocketBridge hotspot.
2. Tap **Share phone screen** in PocketBridge on the phone.
3. Approve Android's screen-capture prompt. Android asks for this approval each time.
4. On the laptop, tap **Watch screen**. PocketBridge first uses a direct WebRTC video stream over the hotspot (no internet, STUN server, or relay). If the browser cannot establish it, the page automatically falls back to the local JPEG stream. Use **Fullscreen** to fit the phone screen to the whole laptop display while preserving its shape. Keep PocketBridge open and the screen on while sharing.

To stop, tap **Stop viewing** in the browser and **Stop sharing** in PocketBridge.

## 4. Browse laptop files and screen on the phone

![Laptop file browsing from the phone](images/quick-start-laptop.svg)

1. Keep the laptop and phone connected to the same PocketBridge hotspot.
2. On Windows, extract the portable PocketBridge Windows Control ZIP and start `Start-Control.bat`. Keep the helper running; if Windows asks, allow it on private networks.
3. Copy the local link printed by the helper. In PocketBridge on the phone, open **Library** → **Files on the laptop**, paste the link and tap **Load laptop files**. The link is saved and shared with **Screen**.
4. Use Search or the Photos, Videos, Audio and Other filters. Tap a supported video or audio file to stream it in an Android player; other formats open in a compatible app. The helper shares Downloads and its subfolders by default.
5. To view the laptop display, open **Screen** → **Show laptop screen here**. The helper page shows connection and stream status; use its toolbar to fit the view or adjust the stream quality.

## If something does not work

- **The Wi-Fi name is missing:** on Android 13 or newer, enable PocketBridge's **Nearby Wi-Fi devices** permission in Android Settings → Apps → PocketBridge → Permissions, then stop and restart sharing. On older Android versions, enable the requested location permission; Android uses it to reveal the connected Wi-Fi name.
- **The link does not open:** confirm both devices are on the same Wi-Fi, retype the exact link, keep the phone share active, and try the other link shown on the phone. If router Wi-Fi mode blocks device-to-device connections, use Hotspot mode.
- **A preview is blank:** leave the page open for a moment. If the phone's media provider has no thumbnail, PocketBridge tries a fallback preview; downloading still transfers the original.
- **A video copy does not play:** the phone may not support converting that source codec. Use **Download** for the original; conversion can need free storage and time.
- **No screen frame appears:** confirm Android's capture permission was approved, both devices remain on the same network, and PocketBridge stays open. Stop capture on the phone, start it again, then tap **Watch screen**.
- **Laptop files are missing on the phone:** start the Windows helper, paste its printed link in **Library** → **Files on the laptop**, and load again. The helper lists the folder set by `share_dir` in its `config.json` (Downloads by default). If the phone says the folder was only partly read, enter a smaller folder path. If the pasted address does not answer, PocketBridge searches the hotspot for the laptop and remembers the address that works.
- **Laptop screen is blank on the phone:** keep the Windows helper running, confirm the phone is on the laptop's hotspot, and check the helper link in **Connect**. A sleeping laptop or Windows Firewall can block the viewer.
- **Laptop controls:** Lock, Wake display, Sleep and Shut down work while the helper runs. **Wake & show sign-in** only wakes the display; Windows still needs the PIN or password typed on the laptop.
- **A large download pauses:** keep the phone unlocked and sharing, keep the laptop connected to the hotspot, and retry the download.

PocketBridge transfers files and screen frames directly from the phone over the local network. The local HTTP connection is not encrypted; use a private hotspot and do not share the one-time code or link with people you do not trust.
