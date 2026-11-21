package com.pocketbridge;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class LaptopCastTest {
    private final LaptopCast cast = new LaptopCast();
    private long now = 1000;
    private JSONObject call(boolean phone, String action, String... fields) throws Exception {
        JSONObject req = new JSONObject().put("action", action);
        for (int i = 0; i < fields.length; i += 2) req.put(fields[i], fields[i + 1]);
        return cast.exchange(req, phone, now++);
    }

    @Test public void browserAndLocalViewerNegotiateAndLeave() throws Exception {
        String publisher = call(false, "start").getString("publisher");
        String session = call(true, "view").getString("session");
        String viewer = call(true, "offer", "session", session, "sdp", "v=0\r\no=viewer").getString("viewer");
        assertEquals("v=0\r\no=viewer", call(false, "poll", "publisher", publisher).getString("offer"));
        assertFalse(call(false, "poll", "publisher", publisher, "previous", viewer).has("offer"));
        call(false, "answer", "viewer", viewer, "publisher", publisher, "sdp", "v=0\r\no=screen");
        assertFalse(call(true, "view", "viewer", "wrong").has("answer"));
        assertEquals("v=0\r\no=screen", call(true, "view", "viewer", viewer).getString("answer"));
        call(true, "leave", "viewer", viewer);
        assertEquals("", call(false, "poll", "publisher", publisher).getString("viewer"));
        call(false, "stop", "publisher", publisher);
        assertEquals("", call(true, "view").getString("session"));
    }

    @Test public void onlyPhoneCanSubscribeAndOnlyPresenterCanStop() throws Exception {
        call(false, "start");
        assertEquals(403, assertThrows(LaptopCast.Refused.class, () -> call(false, "view")).status);
        assertEquals(410, assertThrows(LaptopCast.Refused.class, () -> call(false, "stop", "publisher", "wrong")).status);
        assertEquals(409, assertThrows(LaptopCast.Refused.class, () -> call(false, "start")).status);
        now += LaptopCast.LEASE_MS + 1;
        assertEquals("", call(true, "view").getString("session"));
        assertTrue(call(false, "start").has("publisher"));
    }
}
