package com.pocketbridge;

import android.net.Uri;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This phone's side of a camera call with another phone's PocketBridge
 * (phone↔phone). The other phone runs the server and keeps the call state
 * ({@link CameraCall}); here this phone is its "remote" side. A background
 * poll mirrors that state so a request from the other phone shows up here,
 * with a notification when the app is in the background.
 */
final class PeerCameraLink {
    static final PeerCameraLink INSTANCE = new PeerCameraLink();
    static final long POLL_MS = 1000;

    interface Listener { void changed(PeerCameraLink link); }

    static final class Target {
        final String origin;
        final String code;
        Target(String origin, String code) { this.origin = origin; this.code = code; }
    }

    private static final Pattern LINK = Pattern.compile("http://(\\d{1,3}(?:\\.\\d{1,3}){3}):43117");
    private static final Pattern CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

    /** Reads "http://192.168.43.1:43117 … Code: 123456" (the phone's Copy button text). */
    static Target parse(String text) {
        if (text == null) return null;
        Matcher link = LINK.matcher(text);
        if (!link.find()) return null;
        for (String part : link.group(1).split("\\.")) if (Integer.parseInt(part) > 255) return null;
        String rest = text.substring(0, link.start()) + " " + text.substring(link.end());
        Matcher code = CODE.matcher(rest);
        if (!code.find()) return null;
        return new Target("http://" + link.group(1) + ":43117", code.group(1));
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Target target;
    private volatile JSONObject last = new JSONObject();
    private volatile String error = "";
    private Thread poller;

    void addListener(Listener listener) { if (listener != null) listeners.addIfAbsent(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }

    synchronized void connect(Target next) {
        target = next;
        last = new JSONObject();
        error = "";
        if (poller == null) {
            poller = new Thread(this::pollLoop, "PocketBridge-peer-camera");
            poller.setDaemon(true);
            poller.start();
        }
        notifyListeners();
    }

    synchronized void disconnect() {
        Target old = target;
        target = null;
        if (poller != null) { poller.interrupt(); poller = null; }
        last = new JSONObject();
        if (old != null && isActive()) {
            new Thread(() -> { try { post(old, "stop", new JSONObject()); } catch (Exception ignored) { } }).start();
        }
        notifyListeners();
    }

    boolean connected() { return target != null; }
    Target target() { return target; }
    String error() { return error; }
    JSONObject status() { return last; }
    String state() { return last.optString("state", "idle"); }
    String callId() { return last.optString("id", ""); }

    boolean isActive() {
        String s = state();
        return "requested".equals(s) || "accepted".equals(s) || "live".equals(s);
    }

    /** The other phone asked and this phone has not answered yet. */
    boolean awaitingUs() {
        return "requested".equals(state()) && CameraCall.PHONE.equals(last.optString("requester"));
    }

    boolean mediaAllowed() {
        String s = state();
        return ("accepted".equals(s) || "live".equals(s)) && last.optBoolean("phoneAccepted") && last.optBoolean("remoteAccepted");
    }

    JSONObject request() throws Exception {
        JSONObject reply = post(required(), "request", new JSONObject().put("name", android.os.Build.MODEL));
        refreshNow();
        return reply;
    }

    void respond(boolean accept) throws Exception {
        post(required(), "respond", new JSONObject().put("id", callId()).put("accept", accept));
        refreshNow();
    }

    String offer(String callId, String sdp) throws Exception {
        JSONObject reply = post(required(), "offer", new JSONObject().put("id", callId).put("sdp", sdp));
        return reply.optString("sdp", "");
    }

    void stopCall() {
        Target current = target;
        if (current == null) return;
        try { post(current, "stop", new JSONObject().put("id", callId())); } catch (Exception ignored) { }
        refreshNow();
    }

    private Target required() throws Exception {
        Target current = target;
        if (current == null) throw new Exception("Enter the other phone's link and code first.");
        return current;
    }

    private JSONObject post(Target to, String action, JSONObject body) throws Exception {
        String url = to.origin + "/api/camera/" + action + "?code=" + Uri.encode(to.code);
        HelperClient.Response response = HelperClient.request("POST", url,
                body.toString().getBytes(StandardCharsets.UTF_8), HelperClient.CONNECT_TIMEOUT_MS, 35000, 1);
        JSONObject json = response.json();
        if (!response.ok()) {
            String message = json.optString("error", "");
            throw new Exception(message.isEmpty() ? "The other phone answered HTTP " + response.status + "." : message);
        }
        return json;
    }

    private void refreshNow() {
        Target current = target;
        if (current == null) return;
        try {
            HelperClient.Response response = HelperClient.request("GET",
                    current.origin + "/api/camera/status?code=" + Uri.encode(current.code), null,
                    HelperClient.CONNECT_TIMEOUT_MS, 5000, 1);
            if (target != current) return;
            if (response.ok()) {
                JSONObject next = response.json();
                boolean changed = next.optLong("version", -1) != last.optLong("version", -2)
                        || !next.optString("state").equals(last.optString("state")) || !error.isEmpty();
                last = next;
                error = "";
                if (changed) notifyListeners();
            } else {
                String message = response.json().optString("error", "The other phone answered HTTP " + response.status + ".");
                if (!message.equals(error)) { error = message; notifyListeners(); }
            }
        } catch (Exception unreachable) {
            String message = "The other phone is not reachable. Keep both phones on the same hotspot with PocketBridge sharing.";
            if (!message.equals(error)) { error = message; notifyListeners(); }
        }
    }

    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            refreshNow();
            try { Thread.sleep(POLL_MS); } catch (InterruptedException stop) { return; }
        }
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            try { listener.changed(this); } catch (RuntimeException error) {
                android.util.Log.w("PocketBridge", "Peer camera listener failed", error);
            }
        }
    }
}
