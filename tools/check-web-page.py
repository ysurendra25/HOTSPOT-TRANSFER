#!/usr/bin/env python3
"""Cross-checks the browser page against the phone web server.

Three things have broken this project before and are cheap to catch here:

1. the page calling an /api/... route the server does not implement,
2. the page using an element id that no longer exists,
3. the page's JavaScript not parsing at all.

Run from the repository root:  python3 tools/check-web-page.py
Exits non-zero and prints what is wrong, so CI can fail on it.
"""

import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "app/src/main/assets/www/index.html"
QR_PAGE = ROOT / "tools/win-control/upstream-v0.1.0/web-remote-desktop-0.1.0/web/qr.html"
SERVER = ROOT / "app/src/main/java/com/pocketbridge/MediaHttpServer.java"


def fail(message):
    print("FAIL: " + message)
    return 1


def main():
    problems = 0
    html = PAGE.read_text(encoding="utf-8")
    qr_html = QR_PAGE.read_text(encoding="utf-8")
    server = SERVER.read_text(encoding="utf-8")

    # 1. Routes the page asks for must exist in the server.
    served = set()
    for match in re.finditer(r'path\.equals\("(/[^"]+)"\)', server):
        served.add(match.group(1))
    prefixes = [m.group(1) for m in re.finditer(r'path\.startsWith\("(/[^"]+)"\)', server)]
    # Routes held in constants: static final String UPLOAD_ROUTE = "/api/upload/";
    constants = dict(re.findall(r'static final String (\w+_ROUTE) = "(/[^"]+)"', server))
    for name in re.findall(r'path\.startsWith\((\w+_ROUTE)\)', server):
        if name in constants:
            prefixes.append(constants[name])
    for name in re.findall(r'path\.equals\((\w+_ROUTE)\)', server):
        if name in constants:
            served.add(constants[name])

    requested = set()
    for match in re.finditer(r"endpoint\('(/api/[^'?]+)", html):
        requested.add(match.group(1))

    for route in sorted(requested):
        if route in served:
            continue
        if any(route.startswith(prefix) for prefix in prefixes):
            continue
        problems += fail("the page calls " + route + ", which the server does not serve")

    # 2. Element ids used by the script must exist in the markup.
    ids_present = set(re.findall(r'\bid="([^"]+)"', html))
    ids_used = set(re.findall(r"getElementById\('([^']+)'\)", html))
    for element_id in sorted(ids_used - ids_present):
        problems += fail("the script uses #" + element_id + ", which is not in the page")

    # 3. Keep the fragile interactive flows wired in the actual shipped page.
    required_page_features = {
        "direct local WebRTC screen path": "endpoint('/api/screen/offer')",
        "MJPEG latest-frame fallback": "function drawLatestScreenFrame",
        "WebRTC screen waits for decoded video": "function waitForVideoFrame(video, timeoutMs)",
        "hotspot ICE gets time to connect while fallback stays visible": "await waitForIceConnection(peer, 20000);",
        "screen fullscreen video sizing": ".screen-view:fullscreen .screen-stage",
        "screen video and JPEG preview share one cell (no half-height picture)": ".screen-stage>canvas,.screen-stage>video{grid-area:1/1",
        "MJPEG frames are cut by Content-Length": "function takeScreenPart(bytes)",
        "WebRTC receiver asks for minimal playout delay": "jitterBufferTarget = 0",
        "fullscreen player uses a full-viewport black stage": ".modal.video-viewer .stage{position:absolute;inset:0;width:100%;height:100%",
        "player sizes the picture independently from overlay controls": "media.style.width = layout.width + 'px';",
        "fallback screen status matches the encoder": "Compatibility JPEG · native resolution",
        "dark theme and persisted theme control": "pocketbridge_theme",
        "video aspect ratio recalculation": "function fitVideoStage()",
        "aspect ratio selection resizes the viewer": "fitVideoStage();",
        "video playback preserves the original URL": "media.src = url;",
        "video fitting offers cinema, portrait and classic ratios": "value=\"21-9\"",
        "video controls are cleaned up between files": "modal.removeEventListener(eventName, controlRevealHandler)",
        "music queue reads audio only": "/api/files?kind=audio&limit=5000",
        "chat history has a large scroll area": "#chatMessages{max-height:min(58vh,560px)",
        "unchanged chat polls do not rebuild the conversation": "if (fingerprint === lastChatFingerprint) return;",
        "chat downloads are initiated only by the recipient": "button.href = downloadEndpoint(inboxPath);",
        "download buttons ask the phone for an attachment": "return endpoint(path + (path.includes('?') ? '&' : '?') + 'download=1');",
        "library download links use the attachment URL": "href=\"' + safe(downloadEndpoint(file.url)) + '\" download=",
        "chat video offers Play in VLC": "if (kind === 'video') return ['play', 'vlc', 'download'];",
        "transfer immediately refreshes chat": "await refreshMessages();",
        "chat file picker": 'id="chatFile"',
        "music play control": 'id="musicPlayPause"',
        "music queue repeat control": 'id="musicRepeat"',
        "library sort select in the toolbar": 'id="sortSelect"',
        "visible library sort buttons": 'id="sortBar"',
        "library asks the phone for the chosen sort": "'&sort=' + encodeURIComponent(sortFor(currentKind))",
        "sort choice is kept per category": "'pb_sort_' + (kind || 'all')",
        "every library category tab": 'data-kind="documents"',
        "chunked uploads start a resumable session": "endpoint('/api/upload/start')",
        "chunks are PUT at an offset": "endpoint('/api/upload/' + start.id + '?offset=' + at)",
        "a failed chunk asks the status route what arrived": "pbFirstMissing(state.missing, from, to)",
        "uploads finish the session": "endpoint('/api/upload/' + start.id + '/finish')",
        "older phones fall back to the legacy POST": "endpoint('/api/upload?name=' + encodeURIComponent(file.name))",
        "a 404 on start means a legacy peer": "if (startResponse.status === 404 || startResponse.status === 405) throw new PbLegacyPeer('legacy');",
        "2 to 4 parallel PUTs": "const PB_MIN_PUTS = 2, PB_MAX_PUTS = 4;",
        "4 to 16 MiB chunks": "const PB_MIN_CHUNK = 4 * PB_MIB;",
        "small files travel in PBB1 batches": "endpoint('/api/upload/batch')",
        "PBB1 batch magic": "new TextEncoder().encode('PBB1')",
        "batches only files up to 2 MiB": "const PB_SMALL_FILE = 2 * PB_MIB;",
        "send folder picker": 'id="sendFolder" type="file" webkitdirectory',
        "folders keep webkitRelativePath": "path:file.webkitRelativePath || file.name",
        "big downloads use the save picker": "showSaveFilePicker({suggestedName:name})",
        "positional writes into the saved file": "writable.write({type:'write', position:part[0], data})",
        "ranged parts are checked with If-Range": "headers['If-Range'] = etag",
        "ranged download from 32 MiB": "const PB_BIG_DOWNLOAD = 32 * PB_MIB;",
        "download links carry their size": "data-size=\"' + (Number(file.size) || 0) + '\">Download</a>",
        "transfer speed in MB/s": "' MB/s'",
        "transfer progress and cancel": 'id="transferCancel"',
        "camera call prompt with accept and decline": 'id="cameraAccept"',
        "camera call live indicator": 'id="cameraLive"',
        "either side can end the camera call": 'id="cameraStop"',
    }
    for feature, marker in required_page_features.items():
        if marker not in html:
            problems += fail("missing " + feature)
    if "Open with" in html:
        problems += fail("the removed Open with action is still in the browser UI")
    if "Download all to this laptop now?" in html:
        problems += fail("chat polling can still start automatic file downloads")
    if "if (!confirm('Download " in html:
        problems += fail("recipient file download still requires a second confirmation instead of one deliberate click")
    groups = re.search(r"const TAB_GROUPS = \{(.*?)\n  \};", html, re.S)
    if not groups or "files: ['inboxSection', 'inboxGrid', 'librarySection', 'kindTabs', 'sortBar', 'grid', 'filesPaging', 'transferSection', 'edgeFilesSection']" not in groups.group(1):
        problems += fail("video and file type filters and the sort buttons must stay inside the Files tab")
    if not groups or "music: ['musicSection']" not in groups.group(1):
        problems += fail("the Music tab must show only the music player and song list")
    if "2560px / 12 fps" in html or "up to 1280px" in html:
        problems += fail("the screen-share fallback label does not match the phone encoder limits")

    # 4. The inline script must parse. node is optional, so skip when absent.
    for name, page in (("sharing page", html), ("QR share page", qr_html)):
        scripts = re.findall(r"<script>(.*?)</script>", page, re.S)
        if not scripts:
            problems += fail("no inline script found in " + name)
            continue
        try:
            with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as handle:
                handle.write("\n".join(scripts))
                path = handle.name
            result = subprocess.run(["node", "--check", path], capture_output=True, text=True)
            if result.returncode != 0:
                problems += fail(name + " JavaScript does not parse:\n" + result.stderr.strip())
        except FileNotFoundError:
            print("note: node is not installed, skipping JavaScript syntax checks")
            break
    camera_js = (ROOT / "app/src/main/assets/www/camera-launch.js").read_text(encoding="utf-8")
    camera_html = (ROOT / "tools/win-control/upstream-v0.1.0/web-remote-desktop-0.1.0/web/camera.html").read_text(encoding="utf-8")
    for marker in ("request: 'POST'", "respond: 'POST'", "endpoint('/api/camera/' + action)", "command: 'accept'"):
        if marker not in camera_js:
            problems += fail("camera launcher is missing " + marker)
    for marker in ("signal('request'", "signal('respond'", "state.phoneAccepted && state.remoteAccepted", "setCodecPreferences",
                   "iceServers:[]", 'id="live"', 'id="accept"', 'id="decline"'):
        if marker not in camera_html:
            problems += fail("laptop camera window is missing " + marker)
    for route in ("/api/camera/status", "/api/camera/request", "/api/camera/respond", "/api/camera/offer", "/api/camera/stop"):
        if '"' + route + '"' not in server:
            problems += fail("the phone server does not route " + route)
    for marker in ("PBQ1|F|", "PBQ1|T|", "128 KiB", "BarcodeDetector", "/qr.png"):
        if marker not in qr_html:
            problems += fail("QR share page is missing " + marker)

    if problems == 0:
        print("OK: " + str(len(requested)) + " routes, " + str(len(ids_used)) + " element ids and the script all check out")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
