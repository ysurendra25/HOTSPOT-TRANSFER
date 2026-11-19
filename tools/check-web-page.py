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
SERVER = ROOT / "app/src/main/java/com/pocketbridge/MediaHttpServer.java"


def fail(message):
    print("FAIL: " + message)
    return 1


def main():
    problems = 0
    html = PAGE.read_text(encoding="utf-8")
    server = SERVER.read_text(encoding="utf-8")

    # 1. Routes the page asks for must exist in the server.
    served = set()
    for match in re.finditer(r'path\.equals\("(/[^"]+)"\)', server):
        served.add(match.group(1))
    prefixes = [m.group(1) for m in re.finditer(r'path\.startsWith\("(/[^"]+)"\)', server)]

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
        "screen fullscreen video sizing": ".screen-view:fullscreen video",
        "fullscreen player fills the stage": ".modal:fullscreen .stage.video-stage{flex:1 1 auto;width:100%",
        "fullscreen player releases aspect-sized inline dimensions": "stage.style.removeProperty('height');",
        "fallback screen status matches the encoder cap": "Compatibility JPEG · up to 1280px · max 20 fps",
        "dark theme and persisted theme control": "pocketbridge_theme",
        "video aspect ratio recalculation": "function fitVideoStage()",
        "aspect ratio selection resizes the viewer": "fitVideoStage();",
        "transfer immediately refreshes chat": "await refreshMessages();",
        "chat file picker": 'id="chatFile"',
        "music play control": 'id="musicPlayPause"',
        "music queue repeat control": 'id="musicRepeat"',
    }
    for feature, marker in required_page_features.items():
        if marker not in html:
            problems += fail("missing " + feature)
    if "Open with" in html:
        problems += fail("the removed Open with action is still in the browser UI")
    if "2560px / 12 fps" in html:
        problems += fail("the screen-share fallback label does not match the phone encoder limits")

    # 4. The inline script must parse. node is optional, so skip when absent.
    scripts = re.findall(r"<script>(.*?)</script>", html, re.S)
    if not scripts:
        problems += fail("no inline script found in the page")
    else:
        try:
            with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as handle:
                handle.write("\n".join(scripts))
                path = handle.name
            result = subprocess.run(["node", "--check", path], capture_output=True, text=True)
            if result.returncode != 0:
                problems += fail("the page's JavaScript does not parse:\n" + result.stderr.strip())
        except FileNotFoundError:
            print("note: node is not installed, skipping the syntax check")

    if problems == 0:
        print("OK: " + str(len(requested)) + " routes, " + str(len(ids_used)) + " element ids and the script all check out")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
