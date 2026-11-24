# PocketBridge 0.6.0.11

## Watching and downloading movies

Drag the timeline to preview a time, then release to seek. The player requests the chosen position once. Indexed movies that Edge can decode play from the original file. Formats requiring conversion support random seeking once the phone finishes the compatibility copy.

Movie playback and downloads now use the system TCP send buffer and transfer seekable files directly from disk to the socket where supported. Missing video thumbnails show a placeholder without opening a second movie stream. Actual speed depends on the hotspot, storage and both devices.

## Finding phone files in Edge

Open **Files** to refresh the phone library. Filename searches cover indexed files beyond the previous 5,000-item cutoff. For a document or movie outside that index, tap **Connect → Add files to Edge for this session** on the phone and choose the file. Open **Files** again in Edge. Selected files are served from their original locations and their links expire when sharing stops.

## Connecting over a hotspot

PocketBridge reuses an existing local hotspot address on more phone models. If Android hides the address, join the phone hotspot on Windows, run `ipconfig`, and find the IPv4 **Default Gateway** under Wi-Fi. Enter `http://<gateway>:43117` in Edge, then enter the code shown on the phone.
