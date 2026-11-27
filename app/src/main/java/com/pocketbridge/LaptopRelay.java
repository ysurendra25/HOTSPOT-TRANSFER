package com.pocketbridge;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Commands for the Windows helper that travel over the helper's own
 * connection to this phone (an outbound long-poll from the laptop to
 * /api/laptop-commands). Used for the laptop power buttons when the phone
 * cannot open a connection to the laptop (Windows Firewall, no pasted link),
 * and to ask the laptop to rejoin the hotspot after an airplane reset.
 *
 * Plain Java with an injectable clock, so it is unit tested.
 */
final class LaptopRelay {
    interface Clock { long now(); }

    static final LaptopRelay INSTANCE = new LaptopRelay(System::currentTimeMillis);

    /** The helper counts as connected while it polled within this window. */
    static final long CONNECTED_WINDOW_MS = 45_000;
    /** How long the helper's poll is held open when there is nothing to send. */
    static final long POLL_HOLD_MS = 20_000;

    static final class Command {
        final String id;
        final String action;
        final boolean confirm;
        final JSONObject data;
        boolean delivered;
        boolean done;
        boolean ok;
        String message = "";

        Command(String id, String action, boolean confirm, JSONObject data) {
            this.id = id;
            this.action = action;
            this.confirm = confirm;
            this.data = data;
        }

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            try {
                out.put("id", id).put("action", action).put("confirm", confirm);
                if (data != null) out.put("data", data);
            } catch (Exception ignored) { }
            return out;
        }
    }

    private final Clock clock;
    private final Object lock = new Object();
    private Command queued;
    private final Map<String, Command> sent = new HashMap<>();
    private long lastPollAt;
    private String helperVersion = "";

    LaptopRelay(Clock clock) { this.clock = clock; }

    boolean helperConnected() {
        synchronized (lock) { return lastPollAt > 0 && clock.now() - lastPollAt < CONNECTED_WINDOW_MS; }
    }

    String helperVersion() { synchronized (lock) { return helperVersion; } }

    /** Queue one command. A newer command replaces one the helper has not picked up yet. */
    Command enqueue(String action, boolean confirm, JSONObject data) {
        Command command = new Command(UUID.randomUUID().toString(), action, confirm, data);
        synchronized (lock) {
            if (queued != null) {
                queued.done = true;
                queued.ok = false;
                queued.message = "Replaced by a newer laptop command.";
            }
            queued = command;
            lock.notifyAll();
        }
        return command;
    }

    /** The helper's long-poll: returns the next command, or null after {@code holdMs}. */
    Command poll(String version, long holdMs) throws InterruptedException {
        long deadline = clock.now() + holdMs;
        synchronized (lock) {
            lastPollAt = clock.now();
            if (version != null) helperVersion = version;
            while (queued == null) {
                long remaining = deadline - clock.now();
                if (remaining <= 0) { lastPollAt = clock.now(); return null; }
                lock.wait(remaining);
                lastPollAt = clock.now();
            }
            Command next = queued;
            queued = null;
            next.delivered = true;
            sent.put(next.id, next);
            return next;
        }
    }

    /** The helper reports what happened. Unknown ids are ignored. */
    void complete(String id, boolean ok, String message) {
        synchronized (lock) {
            lastPollAt = clock.now();
            Command command = sent.remove(id);
            if (command == null) return;
            command.ok = ok;
            command.message = message == null ? "" : message;
            command.done = true;
            lock.notifyAll();
        }
    }

    /** Waits for the helper's answer. Returns false on timeout (the command is then withdrawn if not yet delivered). */
    boolean await(Command command, long timeoutMs) throws InterruptedException {
        long deadline = clock.now() + timeoutMs;
        synchronized (lock) {
            while (!command.done) {
                long remaining = deadline - clock.now();
                if (remaining <= 0) {
                    if (queued == command) queued = null;
                    sent.remove(command.id);
                    return false;
                }
                lock.wait(remaining);
            }
            return true;
        }
    }
}
