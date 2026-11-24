# PocketBridge 0.6.0.12

## Browse the connected laptop from the phone

Connect the laptop to the phone hotspot, open PocketBridge in Edge and enter the pairing code. In **Connect** or **Files**, select **Share folder**. Open **Library** on the phone to see the folder’s files, including their relative paths. Search by filename or folder name, filter by type, tap a file to open it, or use **Download to phone** to save a copy. The folder is read-only and playback requests the original bytes on demand. Keep the sharing tab open. Use **Stop sharing files** to withdraw the folder.

Files explicitly sent to the phone also appear in Library and can open after disconnecting. Windows helper browsing remains available and respects the laptop’s selected folder when the phone’s path field is empty.

## Screen sharing

The fallback viewer reuses memory when assembling fragmented JPEG frames and displays the latest complete frame. A stationary screen sends fewer duplicate images. Direct WebRTC starts at a lower bitrate so it can adapt to a busy hotspot while retaining its previous maximum bitrate.
