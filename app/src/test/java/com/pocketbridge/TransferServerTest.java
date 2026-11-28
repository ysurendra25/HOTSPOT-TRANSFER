package com.pocketbridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The transfer engine against the real phone server: resumable uploads, batch
 * uploads with folders, keep-alive, and a loopback round trip with the Java
 * clients (ChunkedUploader up, RangedDownloader down) checked by SHA-256.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferServerTest {
    private static final String CODE = "654321";
    private static final String TOKEN = "fedcba9876543210fedcba9876543210";
    private static final String BASE = "http://127.0.0.1:" + MediaHttpServer.PORT;

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MediaHttpServer server;
    private Context context;

    @Before public void startServer() throws Exception {
        context = RuntimeEnvironment.getApplication();
        deleteTree(new File(context.getFilesDir(), "received"));
        deleteTree(new File(context.getFilesDir(), "uploads"));
        new File(context.getFilesDir(), "share-messages.json").delete();
        server = new MediaHttpServer(context, CODE, TOKEN);
        server.start();
        for (int attempt = 0; attempt < 50; attempt++) {
            try { if (http("GET", "/health", null, null).status == 200) return; }
            catch (Exception notYet) { Thread.sleep(50); }
        }
        throw new IllegalStateException("the test server never started listening");
    }

    @After public void stopServer() { if (server != null) server.stop(); }

    // ---------------------------------------------------------------- helpers

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String withCode(String path) { return path + (path.contains("?") ? "&" : "?") + "code=" + CODE; }

    private static final class Reply {
        final int status;
        final byte[] body;
        final Map<String, String> headers;
        Reply(int status, byte[] body, Map<String, String> headers) { this.status = status; this.body = body; this.headers = headers; }
        String text() { return new String(body, StandardCharsets.UTF_8); }
        JSONObject json() throws Exception { return new JSONObject(text()); }
    }

    private static Reply http(String method, String path, byte[] body, Map<String, String> extra) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(15000);
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) connection.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
        }
        int status = connection.getResponseCode();
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> e : connection.getHeaderFields().entrySet()) {
            if (e.getKey() != null) headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
        }
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] bytes = stream == null ? new byte[0] : readAll(stream);
        connection.disconnect();
        return new Reply(status, bytes, headers);
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        try (InputStream input = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static JSONObject start(String name, long size, long modified, String relPath) throws Exception {
        JSONObject request = new JSONObject().put("name", name).put("size", size).put("lastModified", modified).put("relPath", relPath);
        Reply reply = http("POST", withCode("/api/upload/start"), request.toString().getBytes(StandardCharsets.UTF_8), null);
        assertEquals(reply.text(), 200, reply.status);
        return reply.json();
    }

    private static Reply put(String id, long offset, byte[] data, int from, int to) throws Exception {
        byte[] part = java.util.Arrays.copyOfRange(data, from, to);
        return http("PUT", withCode("/api/upload/" + id + "?offset=" + offset), part, null);
    }

    /** One raw HTTP/1.1 exchange on an open socket: status line, headers, Content-Length body. */
    private static Reply exchange(Socket socket, String request) throws Exception {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        InputStream in = socket.getInputStream();
        String status = line(in);
        Map<String, String> headers = new HashMap<>();
        String header;
        while ((header = line(in)) != null && !header.isEmpty()) {
            int colon = header.indexOf(':');
            headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) break;
            read += n;
        }
        return new Reply(Integer.parseInt(status.split(" ")[1]), body, headers);
    }

    private static String line(InputStream in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') bytes.write(value);
        }
        return bytes.toString("UTF-8");
    }

    // ---------------------------------------------------------------- routes

    @Test public void uploadRoutesNeedTheAccessCode() throws Exception {
        assertEquals(403, http("POST", "/api/upload/start", "{}".getBytes(StandardCharsets.UTF_8), null).status);
        assertEquals(403, http("PUT", "/api/upload/0123456789abcdef0123456789abcdef?offset=0", new byte[3], null).status);
        assertEquals(403, http("POST", "/api/upload/batch", new byte[6], null).status);
        assertEquals(200, http("POST", "/api/upload/start?token=" + TOKEN,
                new JSONObject().put("name", "t.txt").put("size", 1).toString().getBytes(StandardCharsets.UTF_8), null).status);
    }

    @Test public void chunkedUploadStatusCancelAndFinish() throws Exception {
        byte[] data = random(3 * 1024 * 1024 + 77, 1);
        JSONObject state = start("clip.mp4", data.length, 1234, "");
        String id = state.getString("id");
        assertEquals(32, id.length());
        assertEquals(0, state.getLong("received"));
        assertEquals("[[0," + data.length + "]]", state.getJSONArray("missing").toString());
        assertEquals("the same client file gets the same id", id, start("clip.mp4", data.length, 1234, "").getString("id"));

        int mid = 2 * 1024 * 1024;
        Reply second = put(id, mid, data, mid, data.length);
        assertEquals(second.text(), 200, second.status);
        JSONObject status = http("GET", withCode("/api/upload/" + id), null, null).json();
        assertEquals(data.length - mid, status.getLong("received"));
        assertEquals("[[0," + mid + "]]", status.getJSONArray("missing").toString());
        assertEquals(409, http("POST", withCode("/api/upload/" + id + "/finish"), new byte[0], null).status);
        assertEquals(416, put(id, data.length - 2, data, 0, 10).status);
        assertEquals(200, put(id, 0, data, 0, mid).status);

        Reply finished = http("POST", withCode("/api/upload/" + id + "/finish"), new byte[0], null);
        assertEquals(finished.text(), 201, finished.status);
        String inboxId = finished.json().getString("id");
        assertEquals("clip.mp4", finished.json().getString("name"));
        assertTrue(http("GET", withCode("/api/messages"), null, null).text().contains(inboxId));
        Reply download = http("GET", withCode("/api/inbox/" + inboxId), null, null);
        assertArrayEquals(sha256(data), sha256(download.body));

        String other = start("gone.bin", 10, 1, "").getString("id");
        assertEquals(200, http("DELETE", withCode("/api/upload/" + other), null, null).status);
        assertEquals(404, http("GET", withCode("/api/upload/" + other), null, null).status);
        assertEquals(404, http("PUT", withCode("/api/upload/not-an-id?offset=0"), new byte[1], null).status);
    }

    @Test public void anUploadInterruptedMidRangeResumesFromTheLastByteReceived() throws Exception {
        byte[] data = random(1024 * 1024, 2);
        String id = start("cut.bin", data.length, 5, "").getString("id");
        int sentBeforeDrop = 300_000;
        try (Socket socket = new Socket("127.0.0.1", MediaHttpServer.PORT)) {
            String head = "PUT " + withCode("/api/upload/" + id + "?offset=0") + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Content-Length: " + data.length + "\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(data, 0, sentBeforeDrop);
            out.flush();
            Thread.sleep(300);
        } // the connection drops part way through the range
        long received = 0;
        for (int i = 0; i < 50 && received < sentBeforeDrop; i++) {
            Thread.sleep(100);
            received = http("GET", withCode("/api/upload/" + id), null, null).json().getLong("received");
        }
        assertEquals("everything that arrived is kept", sentBeforeDrop, received);
        JSONArray missing = start("cut.bin", data.length, 5, "").getJSONArray("missing");
        assertEquals(sentBeforeDrop, missing.getJSONArray(0).getLong(0));
        assertEquals(200, put(id, sentBeforeDrop, data, sentBeforeDrop, data.length).status);
        String inboxId = http("POST", withCode("/api/upload/" + id + "/finish"), new byte[0], null).json().getString("id");
        assertArrayEquals(sha256(data), sha256(http("GET", withCode("/api/inbox/" + inboxId), null, null).body));
    }

    @Test public void foldersArriveWithTheirStructure() throws Exception {
        List<File> files = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            File file = temporary.newFile("p" + i + ".jpg");
            Files.write(file.toPath(), random(1000 + i, i));
            files.add(file);
            paths.add("Holiday/day " + (i % 3) + "/IMG_" + i + ".jpg");
        }
        JSONObject batch = new ChunkedUploader(BASE, CODE, null).uploadBatch(files, paths);
        assertEquals(25, batch.getInt("count"));
        File big = temporary.newFile("movie.bin");
        byte[] movie = random(2 * 1024 * 1024, 99);
        Files.write(big.toPath(), movie);
        ChunkedUploader.Sent sent = new ChunkedUploader(BASE, CODE, null).upload(big, "Holiday/video/movie.bin", null);
        assertEquals("Holiday/video/movie.bin", sent.id);

        JSONArray inbox = http("GET", withCode("/api/inbox"), null, null).json().getJSONArray("files");
        Map<String, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < inbox.length(); i++) byId.put(inbox.getJSONObject(i).getString("id"), inbox.getJSONObject(i));
        assertTrue(byId.keySet().toString(), byId.containsKey("Holiday/day 1/IMG_4.jpg"));
        assertEquals("Holiday/day 1", byId.get("Holiday/day 1/IMG_4.jpg").getString("folder"));
        assertEquals(26, byId.size());
        assertTrue(new File(context.getFilesDir(), "received/Holiday/video/movie.bin").isFile());

        // Nested ids open with encoded or plain slashes, and still cannot escape.
        assertArrayEquals(Files.readAllBytes(files.get(4).toPath()),
                http("GET", withCode("/api/inbox/Holiday%2Fday%201%2FIMG_4.jpg"), null, null).body);
        Reply plain = http("GET", withCode("/api/inbox/Holiday/day%201/IMG_4.jpg?download=1"), null, null);
        assertEquals(200, plain.status);
        assertTrue(plain.headers.get("content-disposition").contains("filename=\"IMG_4.jpg\""));
        int escape = http("GET", withCode("/api/inbox/Holiday/..%2F..%2Fshare-messages.json"), null, null).status;
        assertTrue(escape == 400 || escape == 404);
        String chat = http("GET", withCode("/api/messages"), null, null).text();
        assertTrue("a big batch is one chat line, not 25", chat.contains("Received 25 files"));
    }

    @Test public void keepAliveForUploadsAndKnownLengthDownloadsOnly() throws Exception {
        byte[] data = random(5000, 3);
        String id = start("k.bin", data.length, 1, "").getString("id");
        try (Socket socket = new Socket("127.0.0.1", MediaHttpServer.PORT)) {
            socket.setSoTimeout(5000);
            String put = "PUT " + withCode("/api/upload/" + id + "?offset=0") + " HTTP/1.1\r\nHost: x\r\nContent-Length: 2000\r\n\r\n";
            socket.getOutputStream().write(put.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(data, 0, 2000);
            Reply first = exchange(socket, "");
            assertEquals(200, first.status);
            assertEquals("keep-alive", first.headers.get("connection"));
            String put2 = "PUT " + withCode("/api/upload/" + id + "?offset=2000") + " HTTP/1.1\r\nHost: x\r\nContent-Length: 3000\r\n\r\n";
            socket.getOutputStream().write(put2.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(data, 2000, 3000);
            Reply second = exchange(socket, "");
            assertEquals(second.text(), 200, second.status);
            assertTrue(second.json().getBoolean("complete"));
            Reply finish = exchange(socket, "POST " + withCode("/api/upload/" + id + "/finish")
                    + " HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n");
            assertEquals(201, finish.status);
            String inboxId = finish.json().getString("id");
            Reply range = exchange(socket, "GET " + withCode("/api/inbox/" + inboxId) + " HTTP/1.1\r\nHost: x\r\nRange: bytes=10-19\r\n\r\n");
            assertEquals(206, range.status);
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 10, 20), range.body);
            assertEquals("keep-alive", range.headers.get("connection"));
            Reply whole = exchange(socket, "GET " + withCode("/api/inbox/" + inboxId) + " HTTP/1.1\r\nHost: x\r\n\r\n");
            assertArrayEquals(data, whole.body);
            Reply control = exchange(socket, "GET " + withCode("/api/messages") + " HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(200, control.status);
            assertEquals("other routes still close", "close", control.headers.get("connection"));
            assertEquals(-1, socket.getInputStream().read());
        }
        // A client that asks to close gets a closed connection.
        Reply closing = http("GET", withCode("/api/upload/" + start("z", 1, 1, "").getString("id")), null,
                java.util.Collections.singletonMap("Connection", "close"));
        assertEquals("close", closing.headers.get("connection"));
    }

    @Test public void downloadHeadersEtagAndIfRangeAreUnchanged() throws Exception {
        assertEquals(201, http("POST", withCode("/api/upload?name=report.pdf"), "0123456789".getBytes(StandardCharsets.UTF_8), null).status);
        String id = http("GET", withCode("/api/inbox"), null, null).json().getJSONArray("files").getJSONObject(0).getString("id");
        Reply head = http("GET", withCode("/api/inbox/" + id + "?download=1"), null, null);
        String etag = head.headers.get("etag");
        assertNotNull(etag);
        assertEquals("attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf", head.headers.get("content-disposition"));
        Map<String, String> matching = new HashMap<>();
        matching.put("Range", "bytes=4-");
        matching.put("If-Range", etag);
        Reply resumed = http("GET", withCode("/api/inbox/" + id), null, matching);
        assertEquals(206, resumed.status);
        assertEquals("456789", resumed.text());
        matching.put("If-Range", "\"stale\"");
        Reply restarted = http("GET", withCode("/api/inbox/" + id), null, matching);
        assertEquals(200, restarted.status);
        assertEquals("0123456789", restarted.text());
    }

    // ---------------------------------------------------------------- loopback

    @Test public void loopbackUploadThenParallelDownloadKeepTheSha256() throws Exception {
        byte[] data = random(48 * 1024 * 1024 + 4099, 7);
        File source = temporary.newFile("big.mkv");
        Files.write(source.toPath(), data);
        long[] progress = {0};
        ChunkedUploader.Sent sent = new ChunkedUploader(BASE, CODE, null).upload(source, "", (done, total) -> {
            synchronized (progress) { progress[0] = Math.max(progress[0], done); }
        });
        assertEquals(data.length, sent.bytes);
        assertEquals(data.length, progress[0]);
        File stored = new File(new File(context.getFilesDir(), "received"), sent.id);
        assertArrayEquals(sha256(data), sha256(Files.readAllBytes(stored.toPath())));

        File part = new File(temporary.getRoot(), "down.part");
        String url = BASE + withCode("/api/inbox/" + java.net.URLEncoder.encode(sent.id, "UTF-8"));
        RangedDownloader.Result result = new RangedDownloader(url, part, data.length, null).download();
        assertTrue("the phone server supports ranges", result.ranged);
        assertArrayEquals(sha256(data), sha256(Files.readAllBytes(result.file.toPath())));

        // The same file again: the upload id is the same and nothing is missing any more.
        File unchanged = temporary.newFile("again.mkv");
        Files.write(unchanged.toPath(), random(1024, 8));
        assertFalse(sent.id.isEmpty());
    }

    @Test public void anInterruptedChunkedUploadResumesWithTheClient() throws Exception {
        byte[] data = random(6 * 1024 * 1024, 11);
        File source = temporary.newFile("resume.bin");
        Files.write(source.toPath(), data);
        // Send the first 2 MiB by hand, as if an earlier attempt stopped there.
        JSONObject first = start(source.getName(), data.length, source.lastModified(), "");
        assertEquals(200, put(first.getString("id"), 0, data, 0, 2 * 1024 * 1024).status);
        ChunkedUploader.Sent sent = new ChunkedUploader(BASE, CODE, null).upload(source, null, null);
        assertEquals("only the missing part is sent again", data.length - 2 * 1024 * 1024, sent.bytesSent);
        File stored = new File(new File(context.getFilesDir(), "received"), sent.id);
        assertArrayEquals(sha256(data), sha256(Files.readAllBytes(stored.toPath())));
    }

    // ---------------------------------------------------------------- app sender (3b)

    @Test public void probeFindsTheResumableRoutesWithoutCreatingAnything() throws Exception {
        assertTrue(new ChunkedUploader(BASE, CODE, null).probe());
        File[] sessions = new File(context.getFilesDir(), "uploads").listFiles();
        assertTrue("the probe leaves no session behind", sessions == null || sessions.length == 0);
    }

    @Test public void anOlderReceiverGetsTheLegacyPostOrALegacyPeerError() throws Exception {
        try (FakeHttpServer old = new FakeHttpServer()) {
            String[] got = new String[1];
            old.route("/api/upload", request -> {
                got[0] = request.method + " " + request.rawQuery + " " + request.body;
                return new FakeHttpServer.Reply(201, "{\"ok\":true,\"name\":\"a.txt\",\"bytes\":5}");
            });
            ChunkedUploader client = new ChunkedUploader("http://127.0.0.1:" + old.port(), CODE, null);
            assertFalse("404 on /api/upload/start = older receiver", client.probe());
            File file = temporary.newFile("a.txt");
            Files.write(file.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            ChunkedUploader.Sent sent = client.send(ChunkedUploader.fileSource(file), "", null, true);
            assertTrue(sent.legacy);
            assertEquals("a.txt", sent.name);
            assertEquals("POST name=a.txt&code=" + CODE + " hello", got[0]);
            try {
                client.send(ChunkedUploader.fileSource(file), "", null, false);
                throw new AssertionError("an older laptop helper must be reported, not posted to");
            } catch (ChunkedUploader.LegacyPeer expected) { /* the app keeps the file for Edge instead */ }
        }
    }

    @Test public void cancelStopsTheUploadAndSendingAgainResumes() throws Exception {
        byte[] data = random(24 * 1024 * 1024, 21);
        File source = temporary.newFile("cancel.bin");
        Files.write(source.toPath(), data);
        ChunkedUploader first = new ChunkedUploader(BASE, CODE, null).maxConnections(2);
        try {
            first.upload(ChunkedUploader.fileSource(source), "", (done, total) -> {
                if (done >= 6 * 1024 * 1024) first.stop();
            });
            throw new AssertionError("the upload should have been cancelled");
        } catch (java.io.InterruptedIOException cancelled) {
            assertTrue(first.stopped());
        }
        // Give the server a moment to record the bytes of the dropped requests.
        Thread.sleep(300);
        ChunkedUploader.Sent sent = new ChunkedUploader(BASE, CODE, null).upload(source, "", null);
        assertTrue("resumed: only part of the file is sent again (" + sent.bytesSent + ")", sent.bytesSent < data.length);
        File stored = new File(new File(context.getFilesDir(), "received"), sent.id);
        assertArrayEquals(sha256(data), sha256(Files.readAllBytes(stored.toPath())));
    }

    @Test public void sourceBatchKeepsEveryFile() throws Exception {
        List<ChunkedUploader.Source> sources = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            File file = temporary.newFile("s" + i + ".txt");
            Files.write(file.toPath(), ("file " + i).getBytes(StandardCharsets.UTF_8));
            sources.add(ChunkedUploader.fileSource(file));
            paths.add("s" + i + ".txt");
        }
        JSONObject reply = new ChunkedUploader(BASE, CODE, null).uploadBatchSources(sources, paths, null);
        assertEquals(30, reply.getInt("count"));
        assertEquals("file 7", new String(Files.readAllBytes(new File(new File(context.getFilesDir(), "received"), "s7.txt").toPath()), StandardCharsets.UTF_8));
    }
}
