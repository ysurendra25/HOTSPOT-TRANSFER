package com.pocketbridge;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Starts the real phone web server and talks HTTP to it, the way the laptop does
 * once it has joined the hotspot. This is the closest we can get to a connected
 * session without a device: routing, the code gate, chat, transfers and ranges.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ServerFunctionalTest {

    private static final String CODE = "123456";
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String BASE = "http://127.0.0.1:" + MediaHttpServer.PORT;

    private MediaHttpServer server;
    private Context context;

    @Before public void startServer() throws Exception {
        MediaHttpServer.setLaptopHelperLink("");
        context = RuntimeEnvironment.getApplication();
        deleteTree(new File(context.getFilesDir(), "received"));
        new File(context.getFilesDir(), "share-messages.json").delete();
        server = new MediaHttpServer(context, CODE, TOKEN);
        server.start();
        waitUntilListening();
    }

    @After public void stopServer() {
        if (server != null) server.stop();
        MediaHttpServer.setLaptopHelperLink("");
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private void waitUntilListening() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                if (send("GET", "/health", null, null).status == 200) return;
            } catch (Exception notYet) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("the test server never started listening");
    }

    private static final class Response {
        final int status;
        final String body;
        final String location;
        Response(int status, String body, String location) { this.status = status; this.body = body; this.location = location; }
    }

    private static Response send(String method, String path, String body, String range) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Connection", "close");
        if (range != null) connection.setRequestProperty("Range", range);
        if (body != null) {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
        }
        int status = connection.getResponseCode();
        String location = connection.getHeaderField("Location");
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = stream == null ? "" : readAll(stream);
        connection.disconnect();
        return new Response(status, text, location);
    }

    private static String readAll(InputStream stream) throws Exception {
        try (InputStream input = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String withCode(String path) {
        return path + (path.contains("?") ? "&" : "?") + "code=" + CODE;
    }

    // ---------------------------------------------------------------- page

    @Test public void thePageLoadsWithoutLeakingTheToken() throws Exception {
        Response page = send("GET", "/", null, null);
        assertEquals(200, page.status);
        assertTrue(page.body.contains("PocketBridge"));
        assertFalse("the page must not carry the long token", page.body.contains(TOKEN));
    }

    @Test public void theLegacyTokenLinkStillOpensTheUnlockedPage() throws Exception {
        assertEquals(200, send("GET", "/share/" + TOKEN, null, null).status);
        assertEquals(403, send("GET", "/share/wrong-token", null, null).status);
    }

    // ---------------------------------------------------------------- gate

    @Test public void apiCallsNeedTheCode() throws Exception {
        assertEquals(403, send("GET", "/api/files", null, null).status);
        assertEquals(403, send("GET", "/api/files?code=000000", null, null).status);
        Response allowed = send("GET", withCode("/api/files"), null, null);
        assertEquals(200, allowed.status);
        assertTrue(allowed.body.contains("\"files\""));
        assertTrue(allowed.body.contains("\"access\""));
    }

    @Test public void theLongTokenAlsoUnlocksTheApi() throws Exception {
        assertEquals(200, send("GET", "/api/files?token=" + TOKEN, null, null).status);
    }

    @Test public void guessingTheCodeIsRateLimited() throws Exception {
        int lastStatus = 0;
        for (int attempt = 0; attempt < 25; attempt++) {
            lastStatus = send("GET", "/api/files?code=999999", null, null).status;
        }
        assertEquals(429, lastStatus);
    }

    // ---------------------------------------------------------------- chat

    @Test public void chatWorksFromBothSides() throws Exception {
        Response empty = send("GET", withCode("/api/messages"), null, null);
        assertEquals(200, empty.status);
        assertTrue(empty.body.contains("\"messages\""));

        assertEquals(201, send("POST", withCode("/api/messages"), "{\"text\":\"hello from the laptop\"}", null).status);

        // The phone writes to the same store from its Chat tab.
        ChatStore.append(context, "hello from the phone", "phone");

        Response thread = send("GET", withCode("/api/messages"), null, null);
        assertTrue(thread.body.contains("hello from the laptop"));
        assertTrue(thread.body.contains("hello from the phone"));
        assertTrue("the laptop message is tagged", thread.body.contains("\"from\":\"laptop\""));
        assertTrue("the phone message is tagged", thread.body.contains("\"from\":\"phone\""));
    }

    @Test public void emptyChatMessagesAreRefused() throws Exception {
        assertEquals(400, send("POST", withCode("/api/messages"), "{\"text\":\"   \"}", null).status);
    }

    // ------------------------------------------------------------ transfers

    @Test public void aFileSentFromTheLaptopLandsInTheInbox() throws Exception {
        String content = "hello phone";
        Response upload = send("POST", withCode("/api/upload?name=note.txt"), content, null);
        assertEquals(201, upload.status);
        assertTrue(upload.body.contains("note.txt"));

        Response inbox = send("GET", withCode("/api/inbox"), null, null);
        assertEquals(200, inbox.status);
        assertTrue(inbox.body.contains("note.txt"));
        Response messages = send("GET", withCode("/api/messages"), null, null);
        assertTrue("the transfer should be visible in chat", messages.body.contains("note.txt"));
        assertTrue("the chat entry should carry its inbox id", messages.body.contains("\"attachment\""));

        String id = extractInboxId(inbox.body);
        assertNotNull(id);
        Response download = send("GET", withCode("/api/inbox/" + id), null, null);
        assertEquals(200, download.status);
        assertEquals(content, download.body);
    }

    @Test public void aReceivedFileCanBeSeekedWithRanges() throws Exception {
        send("POST", withCode("/api/upload?name=clip.bin"), "0123456789", null);
        String id = extractInboxId(send("GET", withCode("/api/inbox"), null, null).body);
        Response partial = send("GET", withCode("/api/inbox/" + id), null, "bytes=2-5");
        assertEquals(206, partial.status);
        assertEquals("2345", partial.body);
    }

    @Test public void emptyUploadsAreRejected() throws Exception {
        // A raw socket makes it possible to exercise an empty upload with an explicit
        // Content-Length, which ordinary browser file pickers never produce.
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", MediaHttpServer.PORT)) {
            socket.setSoTimeout(8000);
            String request = "POST " + withCode("/api/upload?name=big.bin") + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String statusLine = readLine(socket.getInputStream());
            assertTrue("expected a 413, got: " + statusLine, statusLine.contains("413"));
        }
    }

    private static String readLine(InputStream stream) throws Exception {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = stream.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') line.append((char) value);
        }
        return line.toString();
    }

    @Test public void inboxDownloadCannotEscapeTheFolder() throws Exception {
        int status = send("GET", withCode("/api/inbox/..%2Fshare-messages.json"), null, null).status;
        assertTrue("path traversal must not be served", status == 400 || status == 404);
    }

    // ---------------------------------------------------------------- media

    @Test public void unknownMediaIsReportedClearly() throws Exception {
        assertEquals(404, send("GET", withCode("/api/thumb/image:999"), null, null).status);
        assertEquals(404, send("GET", withCode("/api/media/image:999"), null, null).status);
        assertEquals(404, send("GET", withCode("/api/zip"), null, null).status);
    }

    @Test public void screenStreamExplainsItselfWhenNotSharing() throws Exception {
        Response response = send("GET", withCode("/api/screen/mjpeg"), null, null);
        assertEquals(409, response.status);
        assertTrue(response.body.contains("Share phone screen"));

        Response status = send("GET", withCode("/api/screen/status"), null, null);
        assertEquals(200, status.status);
        assertTrue(status.body.contains("\"ready\":false"));
    }

    @Test public void healthAndFaviconAnswerWithoutTheCode() throws Exception {
        assertEquals(200, send("GET", "/health", null, null).status);
        assertEquals(204, send("GET", "/favicon.ico", null, null).status);
        assertEquals(200, send("GET", withCode("/api/session"), null, null).status);
    }

    @Test public void openingWithLaptopPlayerExplainsWhenTheHelperIsMissing() throws Exception {
        String mediaUrl = java.net.URLEncoder.encode("http://192.168.0.4:43117/api/media/video%3A1?code=" + CODE, "UTF-8");
        Response response = send("GET", withCode("/api/open-with?url=" + mediaUrl), null, null);
        assertEquals(503, response.status);
        assertTrue(response.body.contains("Start the Windows helper"));
    }

    @Test public void openingWithLaptopPlayerRedirectsToTheSavedHelperAndKeepsTheMediaUrl() throws Exception {
        MediaHttpServer.setLaptopHelperLink("http://192.168.43.1:7070/?t=helper-token");
        String originalMediaUrl = "http://192.168.43.2:43117/api/media/video%3A1?code=" + CODE;
        String encodedMediaUrl = java.net.URLEncoder.encode(originalMediaUrl, "UTF-8");
        Response response = send("GET", withCode("/api/open-with?url=" + encodedMediaUrl), null, null);
        assertEquals(302, response.status);
        assertTrue(response.location.startsWith("http://192.168.43.1:7070/open-with?t=helper-token&url="));
        assertTrue(java.net.URLDecoder.decode(response.location, "UTF-8").contains(originalMediaUrl));
    }

    private static String extractInboxId(String json) {
        int idIndex = json.indexOf("\"id\":\"");
        if (idIndex < 0) return null;
        int start = idIndex + "\"id\":\"".length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }
}
