package com.pocketbridge;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The phone's laptop power buttons (lock, wake, sleep, restart, shut down).
 *
 * 0.7.0.1 fixes: the buttons only worked when the phone could open a
 * connection to the laptop's helper (a pasted LAN link and a Windows Firewall
 * rule for port 7070), and sleep/restart/shut down always showed success
 * because the helper answers before it acts. Now:
 *  1. The direct link is tried first when one is pasted.
 *  2. If there is no link, or the laptop cannot be reached, the command goes
 *     through the helper's own outbound connection to this phone
 *     ({@link LaptopRelay}), which needs no firewall rule and is
 *     authenticated by the phone code.
 *  3. For deferred actions the phone checks back with the helper and shows
 *     the error Windows reported, if any.
 *
 * Never call on the main thread.
 */
final class LaptopPowerClient {
    static final class Outcome {
        final boolean ok;
        final String message;
        final String route;
        Outcome(boolean ok, String message, String route) { this.ok = ok; this.message = message; this.route = route; }
    }

    interface Sleeper { void sleep(long ms) throws InterruptedException; }

    static long followUpDelayMs = 2500;
    static long relayTimeoutMs = 15_000;
    static Sleeper sleeper = Thread::sleep;

    private LaptopPowerClient() { }

    /** Parses a pasted helper link, requiring a private LAN address; null when it is empty or unusable. */
    static HelperLink lanLink(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        HelperLink link = HelperLink.tryParse(raw);
        if (link == null) return null;
        try { link.requireLanAddress(); } catch (IllegalArgumentException notLan) { return null; }
        return link;
    }

    static Outcome run(HelperLink link, String action, boolean confirmed, LaptopRelay relay, HelperClient.StatusListener status)
            throws InterruptedException {
        String directProblem = null;
        if (link != null) {
            try {
                if (status != null) status.update("Sending to the laptop at " + link.host + "…");
                HelperClient.verifyIdentity(link);
                return direct(link, action, confirmed);
            } catch (HelperClient.HelperException refused) {
                // A helper that answered and refused (wrong code, unknown action)
                // must not be retried another way.
                if (refused.status != 0) return new Outcome(false, refused.getMessage(), "direct");
                directProblem = refused.getMessage();
            } catch (IOException unreachable) {
                directProblem = HelperClient.networkMessage(unreachable);
            }
        }
        if (relay != null && relay.helperConnected()) {
            if (status != null) status.update("Sending through the laptop helper's connection to this phone…");
            LaptopRelay.Command command = relay.enqueue(action, confirmed, null);
            if (!relay.await(command, relayTimeoutMs)) {
                return new Outcome(false, "The laptop helper did not answer within " + (relayTimeoutMs / 1000)
                        + " s. Keep the laptop awake with the helper running, then try again.", "relay");
            }
            String message = command.message == null || command.message.isEmpty()
                    ? (command.ok ? "Done." : "The laptop refused the command.") : command.message;
            return new Outcome(command.ok, message, "relay");
        }
        if (directProblem != null) {
            return new Outcome(false, directProblem + " The helper's connection to this phone is not open either: "
                    + "on the laptop, open the PocketBridge workspace from the helper and enter this phone's code.", "none");
        }
        return new Outcome(false, "No laptop is connected. Start PocketBridge Windows Control on the laptop and open its workspace "
                + "with this phone's code, or paste the helper's LAN link in Connect.", "none");
    }

    private static Outcome direct(HelperLink link, String action, boolean confirmed)
            throws IOException, HelperClient.HelperException, InterruptedException {
        byte[] payload;
        try {
            payload = new JSONObject().put("action", action).put("confirm", confirmed).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception impossible) {
            throw new HelperClient.HelperException("Could not build the request.", 0);
        }
        // Never retry a power action automatically: a lost reply does not mean
        // the laptop ignored it, and a second shutdown is not wanted.
        HelperClient.Response response = HelperClient.request("POST", link.url("/power"), payload,
                HelperClient.CONNECT_TIMEOUT_MS, 10000, 1);
        if (!response.ok()) {
            return new Outcome(false, HelperClient.errorMessage(response.status, response.body), "direct");
        }
        JSONObject reply = response.json();
        String message = reply.optString("message", "Windows command sent: " + action + ".");
        if (!reply.optBoolean("scheduled", false)) return new Outcome(true, message, "direct");
        // Sleep, restart and shut down run a moment after the answer. Ask how it went.
        sleeper.sleep(followUpDelayMs);
        try {
            HelperClient.Response check = HelperClient.request("GET", link.url("/power"), null, 2500, 3000, 1);
            JSONObject last = check.json().optJSONObject("last");
            if (check.ok() && last != null && action.equals(last.optString("action"))
                    && !last.optBoolean("pending", false) && !last.optBoolean("ok", true)) {
                return new Outcome(false, "Windows did not " + verb(action) + ": " + last.optString("error", "unknown error"), "direct");
            }
            return new Outcome(true, message, "direct");
        } catch (IOException goneOffline) {
            return new Outcome(true, message + " The laptop has gone offline, as expected.", "direct");
        }
    }

    private static String verb(String action) {
        switch (action) {
            case "sleep": return "sleep";
            case "restart": return "restart";
            case "shutdown": return "shut down";
            default: return action;
        }
    }
}
