package com.pocketbridge;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The share chat: what is stored, what is rejected, and how the log is capped. */
public class ChatStoreTest {

    @Test public void trimsTextAndRejectsEmptyMessages() {
        assertEquals("hello", ChatStore.sanitise("  hello \n"));
        assertNull(ChatStore.sanitise("   "));
        assertNull(ChatStore.sanitise(""));
        assertNull(ChatStore.sanitise(null));
    }

    @Test public void capsVeryLongMessagesAtTheServerLimit() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 5000; i++) longText.append('a');
        String clean = ChatStore.sanitise(longText.toString());
        assertEquals(2000, clean.length());
    }

    @Test public void acceptsOnlyKnownSenders() {
        assertEquals("phone", ChatStore.normalizeSender("phone"));
        assertEquals("laptop", ChatStore.normalizeSender("laptop"));
        assertEquals("laptop", ChatStore.normalizeSender("someone-else"));
        assertEquals("laptop", ChatStore.normalizeSender(null));
    }

    @Test public void keepsTheNewestHundredMessagesOldestFirst() throws Exception {
        JSONArray all = new JSONArray();
        for (int i = 0; i < 150; i++) all.put(new JSONObject().put("text", "m" + i).put("time", i).put("from", "phone"));
        JSONArray kept = ChatStore.trimmed(all, ChatStore.MAX_MESSAGES);
        assertEquals(ChatStore.MAX_MESSAGES, kept.length());
        assertEquals("m50", kept.getJSONObject(0).getString("text"));
        assertEquals("m149", kept.getJSONObject(kept.length() - 1).getString("text"));
    }

    @Test public void trimmingHandlesShortAndEmptyLogs() throws Exception {
        JSONArray shortLog = new JSONArray().put(new JSONObject().put("text", "only"));
        assertEquals(1, ChatStore.trimmed(shortLog, 100).length());
        assertEquals(0, ChatStore.trimmed(new JSONArray(), 100).length());
        assertEquals(0, ChatStore.trimmed(null, 100).length());
    }

    @Test public void trimmingSkipsBrokenEntries() throws Exception {
        JSONArray log = new JSONArray();
        log.put(new JSONObject().put("text", "good"));
        log.put("not an object");
        JSONArray kept = ChatStore.trimmed(log, 10);
        assertEquals(1, kept.length());
        assertTrue(kept.getJSONObject(0).has("text"));
    }
}
