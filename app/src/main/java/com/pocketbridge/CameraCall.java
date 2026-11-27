package com.pocketbridge;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The two-sided approval for a camera call, kept by the phone that runs the
 * PocketBridge server. "phone" is this phone; "remote" is whoever reaches it
 * over the hotspot: the laptop's Edge camera window or another phone's app.
 *
 * <pre>
 * idle → requested → accepted → live → stopped
 *               ↘ declined
 *               ↘ expired (no answer within 30 s)
 * </pre>
 *
 * Asking counts as consent for the side that asks. The other side must accept
 * explicitly. No camera opens and no offer is answered before both sides have
 * accepted. Either side may stop at any moment, which ends the call for both.
 * Each request gets a fresh id; calls that name an old id are refused, so a
 * late answer can never revive a call that already ended.
 */
final class CameraCall {
    static final long REQUEST_TIMEOUT_MS = 30_000L;
    static final String PHONE = "phone";
    static final String REMOTE = "remote";

    enum State { IDLE, REQUESTED, ACCEPTED, LIVE, DECLINED, EXPIRED, STOPPED }

    interface Clock { long now(); }
    interface Listener { void changed(CameraCall call); }

    static final class Refused extends Exception {
        final int status;
        Refused(int status, String message) { super(message); this.status = status; }
    }

    static final CameraCall INSTANCE = new CameraCall(() -> android.os.SystemClock.elapsedRealtime());

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private State state = State.IDLE;
    private String id = "";
    private String requester = "";
    private String endedBy = "";
    private String reason = "";
    private long requestedAt;
    private long version;
    private boolean phoneAccepted;
    private boolean remoteAccepted;
    private String remoteName = "";

    CameraCall(Clock clock) { this.clock = clock; }

    void addListener(Listener listener) { if (listener != null) listeners.addIfAbsent(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }

    /** A new request from {@code side}. Refused while another call is pending or live. */
    String request(String side, String name) throws Refused {
        String who = side(side);
        synchronized (this) {
            expireIfDue();
            if (state == State.REQUESTED || state == State.ACCEPTED || state == State.LIVE) {
                throw new Refused(409, "A camera call is already " + label(state) + ". Stop it first.");
            }
            byte[] bytes = new byte[12];
            random.nextBytes(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            id = hex.toString();
            requester = who;
            requestedAt = clock.now();
            phoneAccepted = PHONE.equals(who);
            remoteAccepted = REMOTE.equals(who);
            remoteName = name == null ? "" : name.trim();
            if (remoteName.length() > 60) remoteName = remoteName.substring(0, 60);
            endedBy = "";
            reason = "";
            state = State.REQUESTED;
            version++;
        }
        notifyListeners();
        return id;
    }

    /** The answer of the side that did not ask. */
    void respond(String side, String callId, boolean accept) throws Refused {
        String who = side(side);
        synchronized (this) {
            expireIfDue();
            requireId(callId);
            if (state != State.REQUESTED) throw new Refused(409, "This camera call is " + label(state) + ".");
            if (who.equals(requester)) throw new Refused(409, "Waiting for the other device to answer.");
            if (accept) {
                if (PHONE.equals(who)) phoneAccepted = true; else remoteAccepted = true;
                state = State.ACCEPTED;
            } else {
                state = State.DECLINED;
                endedBy = who;
                reason = (PHONE.equals(who) ? "The phone" : "The other device") + " declined the camera call.";
            }
            version++;
        }
        notifyListeners();
    }

    /** True when media may flow: both sides accepted and nobody stopped. */
    synchronized boolean mediaAllowed(String callId) {
        expireIfDue();
        return (state == State.ACCEPTED || state == State.LIVE) && phoneAccepted && remoteAccepted
                && callId != null && callId.equals(id);
    }

    /** Throws unless an offer for {@code callId} may be answered now. */
    void requireMediaAllowed(String callId) throws Refused {
        synchronized (this) {
            expireIfDue();
            requireId(callId);
            if (!mediaAllowed(callId)) {
                throw new Refused(409, state == State.REQUESTED
                        ? "Waiting for both sides to accept the camera call."
                        : "This camera call is " + label(state) + ".");
            }
        }
    }

    /** The WebRTC link carries video. */
    void markLive(String callId) {
        synchronized (this) {
            if (!mediaAllowed(callId) || state == State.LIVE) return;
            state = State.LIVE;
            version++;
        }
        notifyListeners();
    }

    /** Ends the current call for both sides. Stopping an ended call is a no-op. */
    void stop(String side, String why) {
        synchronized (this) {
            if (state != State.REQUESTED && state != State.ACCEPTED && state != State.LIVE) return;
            state = State.STOPPED;
            endedBy = side == null ? "" : side;
            reason = why == null || why.isEmpty()
                    ? (PHONE.equals(side) ? "The phone ended the camera call." : REMOTE.equals(side) ? "The other device ended the camera call." : "The camera call ended.")
                    : why;
            version++;
        }
        notifyListeners();
    }

    /** Called by status reads and a timer: moves an unanswered request to expired. */
    void tick() {
        boolean changed;
        synchronized (this) { changed = expireIfDue(); }
        if (changed) notifyListeners();
    }

    private boolean expireIfDue() {
        if (state == State.REQUESTED && clock.now() - requestedAt >= REQUEST_TIMEOUT_MS) {
            state = State.EXPIRED;
            endedBy = "";
            reason = "Nobody answered the camera request within 30 seconds.";
            version++;
            return true;
        }
        return false;
    }

    synchronized State state() { expireIfDue(); return state; }
    synchronized String id() { return id; }
    synchronized String requester() { return requester; }
    synchronized long version() { return version; }
    synchronized String reason() { return reason; }
    synchronized String remoteName() { return remoteName; }
    synchronized boolean accepted(String side) { return PHONE.equals(side) ? phoneAccepted : remoteAccepted; }

    /** True while the call holds the camera or is about to. */
    synchronized boolean active() {
        expireIfDue();
        return state == State.ACCEPTED || state == State.LIVE;
    }

    /** True while {@code side} has to answer a pending request. */
    synchronized boolean awaiting(String side) {
        expireIfDue();
        return state == State.REQUESTED && !side(side).equals(requester);
    }

    synchronized long expiresInMs() {
        if (state != State.REQUESTED) return 0;
        return Math.max(0, REQUEST_TIMEOUT_MS - (clock.now() - requestedAt));
    }

    JSONObject toJson() {
        synchronized (this) {
            expireIfDue();
            JSONObject json = new JSONObject();
            try {
                json.put("state", state.name().toLowerCase(java.util.Locale.ROOT))
                        .put("id", id)
                        .put("requester", requester)
                        .put("phoneAccepted", phoneAccepted)
                        .put("remoteAccepted", remoteAccepted)
                        .put("expiresInMs", expiresInMs())
                        .put("version", version)
                        .put("endedBy", endedBy)
                        .put("reason", reason);
            } catch (JSONException ignored) { }
            return json;
        }
    }

    /** Back to idle; tests and a stopped share use it. */
    void reset() {
        synchronized (this) {
            state = State.IDLE;
            id = requester = endedBy = reason = remoteName = "";
            phoneAccepted = remoteAccepted = false;
            version++;
        }
        notifyListeners();
    }

    private void requireId(String callId) throws Refused {
        if (callId == null || callId.isEmpty() || !callId.equals(id)) {
            throw new Refused(410, "That camera call has ended. Start a new one.");
        }
    }

    private static String side(String value) {
        return PHONE.equals(value) ? PHONE : REMOTE;
    }

    static String label(State state) {
        switch (state) {
            case REQUESTED: return "waiting for an answer";
            case ACCEPTED: return "connecting";
            case LIVE: return "live";
            case DECLINED: return "declined";
            case EXPIRED: return "expired";
            case STOPPED: return "stopped";
            default: return "not started";
        }
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            try { listener.changed(this); } catch (RuntimeException error) {
                android.util.Log.w("PocketBridge", "Camera call listener failed", error);
            }
        }
    }
}
