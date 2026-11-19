package com.pocketbridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Runs the phone's helper client against a small fake Windows helper, the
 * way the Library tab and the power buttons use it.
 */
public class HelperClientTest {
    private FakeHttpServer helper;
    private String base;
    private final AtomicInteger busyAnswers = new AtomicInteger();
    private final List<String> powerBodies = new ArrayList<>();

    @Before public void startFakeHelper() throws Exception {
        HelperClient.forget();
        helper = new FakeHttpServer();
        helper.route("/ping", request -> "t=tok".equals(request.rawQuery)
                ? new FakeHttpServer.Reply(200, "{\"ok\":true}")
                : new FakeHttpServer.Reply(401, "unauthorized"));
        helper.route("/files", request -> {
            if (busyAnswers.getAndDecrement() > 0) return new FakeHttpServer.Reply(503, "{\"error\":\"busy\"}");
            int offset = request.rawQuery.contains("offset=2") ? 2 : 0;
            return new FakeHttpServer.Reply(200, offset == 0
                    ? "{\"files\":[{\"name\":\"a b.mp4\",\"url\":\"/file/a%20b.mp4\",\"mime\":\"video/mp4\"},{\"name\":\"c.jpg\",\"url\":\"/file/c.jpg\"}],\"total\":3,\"nextOffset\":2,\"hasMore\":true}"
                    : "{\"files\":[{\"name\":\"d.pdf\",\"url\":\"/file/d.pdf\"}],\"total\":3,\"nextOffset\":3,\"hasMore\":false,\"truncated\":true}");
        });
        helper.route("/power", request -> {
            synchronized (powerBodies) { powerBodies.add(request.body); }
            return new FakeHttpServer.Reply(200, "{\"ok\":true,\"message\":\"Laptop locked.\"}");
        });
        base = "http://127.0.0.1:" + helper.port();
    }

    @After public void stopFakeHelper() throws Exception {
        helper.close();
        HelperClient.forget();
    }

    /** A link to the fake helper; 127.0.0.1 here plays the laptop's LAN address. */
    private HelperLink link() {
        return HelperLink.parse(base + "/?t=tok");
    }

    @Test public void pagesThroughALargeSharedFolder() throws Exception {
        HelperLink link = link();
        HelperClient.FilesPage first = HelperClient.parseFilesPage(
                HelperClient.request("GET", link.url("/files", "offset", "0", "limit", "2"), null, 2000, 4000, 1).body, 0);
        assertEquals(2, first.files.size());
        assertTrue(first.hasMore);
        assertEquals(3, first.total);
        HelperClient.FilesPage second = HelperClient.parseFilesPage(
                HelperClient.request("GET", link.url("/files", "offset", String.valueOf(first.nextOffset), "limit", "2"), null, 2000, 4000, 1).body, first.nextOffset);
        assertEquals(1, second.files.size());
        assertFalse(second.hasMore);
        assertTrue(second.truncated);
    }

    @Test public void retriesWhileTheHelperIsBusy() throws Exception {
        busyAnswers.set(2);
        HelperClient.Response response = HelperClient.request("GET", link().url("/files"), null, 2000, 4000, 3);
        assertEquals(200, response.status);
    }

    @Test public void readsOldHelpersThatSendEverythingAtOnce() throws Exception {
        HelperClient.FilesPage page = HelperClient.parseFilesPage("{\"files\":[{\"name\":\"x\",\"url\":\"/file/x\"}]}", 0);
        assertEquals(1, page.files.size());
        assertFalse(page.hasMore);
    }

    @Test public void refusesAListItCannotRead() {
        try {
            HelperClient.parseFilesPage("<html>login</html>", 0);
            fail("an HTML page is not a file list");
        } catch (HelperClient.HelperException expected) {
            assertTrue(expected.getMessage().contains("could not read"));
        }
    }

    @Test public void resolvesAReachableLinkWithoutScanning() throws Exception {
        HelperLink resolved = HelperClient.resolve(base + "/?t=tok", null);
        assertEquals(link(), resolved);
    }

    @Test public void aWrongCodeIsExplainedNotRetried() {
        try {
            HelperClient.resolve(base + "/?t=wrong", null);
            fail("a rejected code must not count as reachable");
        } catch (HelperClient.HelperException expected) {
            assertEquals(401, expected.status);
            assertTrue(expected.getMessage().contains("access code"));
        }
    }

    @Test public void sendsPowerActionsAsJson() throws Exception {
        byte[] payload = "{\"action\":\"lock\",\"confirm\":false}".getBytes(StandardCharsets.UTF_8);
        HelperClient.Response response = HelperClient.request("POST", link().url("/power"), payload, 2000, 4000, 1);
        assertEquals(200, response.status);
        assertEquals("Laptop locked.", response.json().optString("message"));
        assertEquals("{\"action\":\"lock\",\"confirm\":false}", powerBodies.get(0));
    }

    @Test public void errorMessagesUseTheHelpersOwnWords() {
        assertEquals("Confirm Sleep on the phone first.",
                HelperClient.errorMessage(400, "{\"ok\":false,\"error\":\"Confirm Sleep on the phone first.\"}"));
        assertTrue(HelperClient.errorMessage(401, "unauthorized").contains("access code"));
        assertEquals("share folder unavailable", HelperClient.errorMessage(404, "share folder unavailable\n"));
        assertTrue(HelperClient.errorMessage(500, "<html>x</html>").contains("HTTP 500"));
    }

    @Test public void unreachableLaptopsGetAPlainExplanation() {
        String message = HelperClient.networkMessage(new java.net.ConnectException("ECONNREFUSED"));
        assertTrue(message, message.contains("Could not reach the laptop helper"));
        assertTrue(HelperClient.networkMessage(new java.net.SocketTimeoutException()).contains("did not answer in time"));
    }
}
