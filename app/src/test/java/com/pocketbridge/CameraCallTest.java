package com.pocketbridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** idle → requested → (accepted | declined | expired) → live → stopped, for both directions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CameraCallTest {
    private long now;
    private CameraCall call;

    @Before public void setUp() {
        now = 1_000L;
        call = new CameraCall(() -> now);
    }

    @Test public void laptopAsksPhoneAcceptsThenLiveThenStopped() throws Exception {
        assertEquals(CameraCall.State.IDLE, call.state());
        String id = call.request(CameraCall.REMOTE, "Laptop");
        assertEquals(CameraCall.State.REQUESTED, call.state());
        assertTrue("asking counts as the asker's consent", call.accepted(CameraCall.REMOTE));
        assertFalse(call.accepted(CameraCall.PHONE));
        assertTrue(call.awaiting(CameraCall.PHONE));
        assertFalse(call.awaiting(CameraCall.REMOTE));
        assertFalse("no media before both accept", call.mediaAllowed(id));
        refusedWith(409, () -> call.requireMediaAllowed(id));
        refusedWith(409, () -> call.respond(CameraCall.REMOTE, id, true));
        call.respond(CameraCall.PHONE, id, true);
        assertEquals(CameraCall.State.ACCEPTED, call.state());
        assertTrue(call.mediaAllowed(id));
        call.requireMediaAllowed(id);
        call.markLive(id);
        assertEquals(CameraCall.State.LIVE, call.state());
        call.stop(CameraCall.REMOTE, null);
        assertEquals(CameraCall.State.STOPPED, call.state());
        assertFalse(call.mediaAllowed(id));
        assertEquals("remote", call.toJson().getString("endedBy"));
    }

    @Test public void phoneAsksRemoteDeclines() throws Exception {
        String id = call.request(CameraCall.PHONE, "");
        assertTrue(call.awaiting(CameraCall.REMOTE));
        call.respond(CameraCall.REMOTE, id, false);
        assertEquals(CameraCall.State.DECLINED, call.state());
        assertFalse(call.mediaAllowed(id));
        assertTrue(call.reason().contains("declined"));
        refusedWith(409, () -> call.respond(CameraCall.REMOTE, id, true));
    }

    @Test public void unansweredRequestExpiresAfterThirtySeconds() throws Exception {
        String id = call.request(CameraCall.PHONE, "");
        now += CameraCall.REQUEST_TIMEOUT_MS - 1;
        assertEquals(CameraCall.State.REQUESTED, call.state());
        assertEquals(1, call.expiresInMs());
        now += 1;
        assertEquals(CameraCall.State.EXPIRED, call.state());
        refusedWith(409, () -> call.respond(CameraCall.REMOTE, id, true));
        assertFalse(call.mediaAllowed(id));
        // A new request is possible after expiry and gets a new id.
        String next = call.request(CameraCall.REMOTE, "");
        assertNotEquals(id, next);
    }

    @Test public void staleIdsAreRefusedAndBusyRequestsConflict() throws Exception {
        String id = call.request(CameraCall.REMOTE, "");
        refusedWith(409, () -> call.request(CameraCall.PHONE, ""));
        refusedWith(410, () -> call.respond(CameraCall.PHONE, "old", true));
        refusedWith(410, () -> call.requireMediaAllowed("old"));
        call.respond(CameraCall.PHONE, id, true);
        assertFalse(call.mediaAllowed("old"));
        refusedWith(409, () -> call.request(CameraCall.REMOTE, ""));
    }

    @Test public void eitherSideCanStopAtAnyStageAndListenersHear() throws Exception {
        AtomicInteger changes = new AtomicInteger();
        call.addListener(c -> changes.incrementAndGet());
        String id = call.request(CameraCall.PHONE, "");
        call.stop(CameraCall.PHONE, null);
        assertEquals(CameraCall.State.STOPPED, call.state());
        int before = changes.get();
        call.stop(CameraCall.REMOTE, null);
        assertEquals("stopping an ended call changes nothing", before, changes.get());
        call.markLive(id);
        assertEquals(CameraCall.State.STOPPED, call.state());
        assertTrue(changes.get() >= 2);
        long version = call.version();
        call.tick();
        assertEquals(version, call.version());
    }

    @Test public void jsonCarriesWhatBothSidesNeed() throws Exception {
        String id = call.request(CameraCall.REMOTE, "Laptop");
        org.json.JSONObject json = call.toJson();
        assertEquals("requested", json.getString("state"));
        assertEquals(id, json.getString("id"));
        assertEquals("remote", json.getString("requester"));
        assertTrue(json.getBoolean("remoteAccepted"));
        assertFalse(json.getBoolean("phoneAccepted"));
        assertEquals(CameraCall.REQUEST_TIMEOUT_MS, json.getLong("expiresInMs"));
    }

    private interface Action { void run() throws Exception; }

    private static void refusedWith(int status, Action action) throws Exception {
        try {
            action.run();
            fail("expected a refusal with HTTP " + status);
        } catch (CameraCall.Refused refused) {
            assertEquals(status, refused.status);
        }
    }
}
