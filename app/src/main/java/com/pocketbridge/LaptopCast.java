package com.pocketbridge;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Direct WebRTC signaling and a single latest JPEG for hotspots that cannot establish ICE. */
final class LaptopCast {
    static final long LEASE_MS = 90_000L;
    private String publisher = "";
    private String session = "";
    private String viewer = "";
    private String offer = "";
    private String answer = "";
    private long frameRequestUntil;
    private long frameNumber;
    private String frame = "";
    private long publisherSeen;
    private long viewerSeen;

    static final class Refused extends Exception {
        final int status;
        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void clearViewer() {
        viewer = "";
        offer = "";
        answer = "";
    }

    private void clear() {
        publisher = "";
        session = "";
        frame = "";
        frameNumber = frameRequestUntil = 0;
        clearViewer();
    }

    synchronized JSONObject exchange(JSONObject request, boolean onPhone, long now) throws JSONException, Refused {
        if (now - publisherSeen > LEASE_MS) clear();
        if (now - viewerSeen > LEASE_MS) clearViewer();
        JSONObject result = new JSONObject();
        String action = request.optString("action");
        switch (action) {
            case "start":
                if (!publisher.isEmpty()) throw new Refused(409, "Another laptop is presenting. Stop that share first.");
                session = UUID.randomUUID().toString();
                publisher = UUID.randomUUID().toString();
                publisherSeen = now;
                return result.put("publisher", publisher);
            case "poll":
            case "frame":
            case "answer":
            case "stop":
                if (publisher.isEmpty() || !publisher.equals(request.optString("publisher"))) {
                    throw new Refused(410, "This presentation has ended.");
                }
                publisherSeen = now;
                if (action.equals("stop")) clear();
                else if (action.equals("frame")) {
                    String jpeg = request.optString("jpeg");
                    if (jpeg.length() > 192000 || !jpeg.startsWith("data:image/jpeg;base64,/9j/")) throw new Refused(400, "Invalid preview frame.");
                    if (now < frameRequestUntil) { frame = jpeg; frameNumber++; }
                } else if (action.equals("answer")) {
                    if (viewer.isEmpty() || !viewer.equals(request.optString("viewer"))) {
                        throw new Refused(409, "The phone viewer has changed.");
                    }
                    answer = requiredSdp(request);
                } else {
                    result.put("viewer", viewer).put("needsFrame", now < frameRequestUntil);
                    if (!viewer.isEmpty() && !viewer.equals(request.optString("previous"))) result.put("offer", offer);
                }
                return result;
            case "preview":
                if (!onPhone) throw new Refused(403, "Open the preview on the sharing phone.");
                if (!session.isEmpty()) frameRequestUntil = now + 3500;
                if (!frame.isEmpty() && (!session.equals(request.optString("session")) || frameNumber != request.optLong("frameNumber")))
                    result.put("jpeg",frame);
                return result.put("session",session).put("frameNumber",frameNumber);
            case "view":
            case "offer":
            case "leave":
                if (!onPhone) throw new Refused(403, "Open the viewer on the sharing phone.");
                if (action.equals("offer")) {
                    if (session.isEmpty() || !session.equals(request.optString("session"))) {
                        throw new Refused(409, "The laptop presentation has changed.");
                    }
                    String sdp = requiredSdp(request);
                    viewer = UUID.randomUUID().toString();
                    offer = sdp;
                    answer = "";
                    viewerSeen = now;
                } else if (!viewer.isEmpty() && viewer.equals(request.optString("viewer"))) {
                    viewerSeen = now;
                    if (action.equals("leave")) clearViewer();
                    else if (!answer.isEmpty() && !request.optBoolean("connected")) result.put("answer", answer);
                }
                return result.put("session", session).put("viewer", viewer);
            default:
                throw new Refused(400, "Unknown laptop screen action.");
        }
    }

    private String requiredSdp(JSONObject request) throws Refused {
        String sdp = request.optString("sdp");
        if (!sdp.startsWith("v=0") || sdp.length() > 200_000) throw new Refused(400, "Invalid session description.");
        return sdp;
    }
}
