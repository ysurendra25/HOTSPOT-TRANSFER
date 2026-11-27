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

    @Test public void matchingPortableHelperDownloadsOverPairedConnection() throws Exception {
        String path = "/api/windows-helper";
        assertEquals(403, send("GET", path, null, null).status);
        HttpURLConnection download = (HttpURLConnection) new URL(BASE + withCode(path)).openConnection();
        try {
            assertEquals(200, download.getResponseCode());
            assertEquals("application/zip", download.getContentType());
            assertTrue(download.getContentLengthLong() > 0);
            assertTrue(download.getHeaderField("Content-Disposition").contains("PocketBridge-Windows-Control.zip"));
            java.util.Set<String> entries = new java.util.HashSet<>();
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(download.getInputStream())) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries.add(entry.getName());
                    if (entry.getName().equals("PocketBridge-Windows-Control.exe")) {
                        assertEquals('M', zip.read());
                        assertEquals('Z', zip.read());
                    }
                    zip.closeEntry();
                }
            }
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("PocketBridge-Windows-Control.exe",
                    "Start-Control.bat", "README.md", "UPSTREAM-LICENSE.txt")), entries);
        } finally {
            download.disconnect();
        }
    }

    @Test public void laptopScreenPageAndSignalingUseActivePairing() throws Exception {
        MediaHttpServer.setLaptopHelperLink("http://192.168.1.20:7654/?t=helpersecret");
        Response launch = send("GET", "/laptop-cast.js", null, null);
        assertTrue(launch.body.contains("http://127.0.0.1:7654"));
        assertFalse(launch.body.contains("helpersecret"));
        assertEquals(403, send("GET", "/watch-laptop.html", null, null).status);
        assertTrue(send("GET", "/watch-laptop.html?code=" + CODE, null, null).body.contains("RTCPeerConnection"));
        String path = "/api/laptop-cast";
        assertEquals(403, send("POST", path, "{\"action\":\"start\"}", null).status);
        Response start = send("POST", path + "?code=" + CODE, "{\"action\":\"start\"}", null);
        assertEquals(200, start.status);
        assertTrue(start.body.contains("publisher"));
        assertEquals(200, send("POST", path + "?code=" + CODE, "{\"action\":\"view\"}", null).status);
        assertEquals(410, send("POST", path + "?code=" + CODE, "{\"action\":\"poll\"}", null).status);
    }

    @Test public void largeMovieSupportsResumingBeyondFourGiB() throws Exception {
        File inbox = new File(context.getFilesDir(), "received");
        assertTrue(inbox.mkdirs() || inbox.isDirectory());
        File movie = new File(inbox, "movie.mkv");
        long length = 30L * 1024 * 1024 * 1024;
        byte[] marker = "final frame".getBytes(StandardCharsets.UTF_8);
        try (java.nio.channels.FileChannel file = java.nio.channels.FileChannel.open(movie.toPath(),
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.SPARSE)) {
            file.position(length - marker.length);
            file.write(java.nio.ByteBuffer.wrap(marker));
        }
        String path = withCode("/api/inbox/movie.mkv");
        HttpURLConnection head = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            head.setRequestMethod("HEAD");
            assertEquals(200, head.getResponseCode());
            assertEquals(length, head.getContentLengthLong());
            assertEquals("bytes", head.getHeaderField("Accept-Ranges"));
        } finally { head.disconnect(); }
        Response tail = send("GET", path, null, "bytes=" + (length - marker.length) + "-");
        assertEquals(206, tail.status);
        assertEquals("final frame", tail.body);
        assertEquals(416, send("GET", path, null, "bytes=" + length + "-").status);
    }

    @Test public void libraryPagesBeyondFiveThousandAndFindsUnclassifiedMovies() {
        try (android.database.MatrixCursor rows = new android.database.MatrixCursor(
                new String[]{"_id", "_display_name", "mime_type", "_size", "date_modified", "media_type"})) {
            for (int i = 0; i < 5010; i++)
                rows.addRow(new Object[]{i, i == 5007 ? "Old movie_1.mkv" : "clip" + i + ".mp4",
                        "application/octet-stream", 100L, 123L, i == 5007 ? 0 : 3});
            MediaRepository.Page page = MediaRepository.readPage(rows, "videos", 5006, 2, "");
            assertEquals(5008, page.nextOffset);
            assertTrue(page.hasMore);
            assertEquals("file:5007", page.items.get(1).key);
            rows.moveToPosition(-1);
            page = MediaRepository.readPage(rows, "videos", 0, 10, "MOVIE_1");
            assertEquals(1, page.items.size());
            assertFalse(page.hasMore);
            assertEquals("video", page.items.get(0).kind);
        }
    }

    @Test public void pickedDocumentIsSearchableAndStreamedFromTheOriginal() throws Exception {
        File document = new File(context.getCacheDir(), "picker-test.mkv");
        java.nio.file.Files.write(document.toPath(), "movie-bytes".getBytes(StandardCharsets.UTF_8));
        android.net.Uri uri = android.net.Uri.parse("content://pocketbridge-test/document/23");
        android.content.ContentProvider provider = new android.content.ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public String getType(android.net.Uri target) { return "application/octet-stream"; }
            @Override public android.database.Cursor query(android.net.Uri target, String[] columns,
                    String where, String[] args, String order) {
                android.database.MatrixCursor rows = new android.database.MatrixCursor(columns);
                rows.addRow(new Object[]{"Picked Movie.mkv", document.length()});
                return rows;
            }
            @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri target, String mode)
                    throws java.io.FileNotFoundException {
                return android.os.ParcelFileDescriptor.open(document, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            }
            @Override public android.net.Uri insert(android.net.Uri target, android.content.ContentValues values) { throw new UnsupportedOperationException(); }
            @Override public int update(android.net.Uri target, android.content.ContentValues v, String w, String[] a) { throw new UnsupportedOperationException(); }
            @Override public int delete(android.net.Uri target, String w, String[] a) { throw new UnsupportedOperationException(); }
        };
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = uri.getAuthority();
        provider.attachInfo(context, info);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(info.authority, provider);
        MediaHttpServer.activeLibrary().addDocument(uri);
        Response found = send("GET", withCode("/api/files?kind=videos&q=picked"), null, null);
        assertEquals(200, found.status);
        org.json.JSONObject item = new org.json.JSONObject(found.body).getJSONArray("files").getJSONObject(0);
        assertEquals("Picked Movie.mkv", item.getString("name"));
        assertEquals("video", item.getString("kind"));
        Response range = send("GET", withCode(item.getString("url")), null, "bytes=6-10");
        assertEquals(206, range.status);
        assertEquals("bytes", range.body);
    }

    /** Registers a one-file document provider and returns the library item for it. */
    private org.json.JSONObject pickDocument(String authority, String name, byte[] bytes) throws Exception {
        File document = new File(context.getCacheDir(), authority + ".bin");
        java.nio.file.Files.write(document.toPath(), bytes);
        android.net.Uri uri = android.net.Uri.parse("content://" + authority + "/document/1");
        android.content.ContentProvider provider = new android.content.ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public String getType(android.net.Uri target) { return "application/octet-stream"; }
            @Override public android.database.Cursor query(android.net.Uri target, String[] columns,
                    String where, String[] args, String order) {
                android.database.MatrixCursor rows = new android.database.MatrixCursor(columns);
                rows.addRow(new Object[]{name, document.length()});
                return rows;
            }
            @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri target, String mode)
                    throws java.io.FileNotFoundException {
                return android.os.ParcelFileDescriptor.open(document, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            }
            @Override public android.net.Uri insert(android.net.Uri target, android.content.ContentValues values) { throw new UnsupportedOperationException(); }
            @Override public int update(android.net.Uri target, android.content.ContentValues v, String w, String[] a) { throw new UnsupportedOperationException(); }
            @Override public int delete(android.net.Uri target, String w, String[] a) { throw new UnsupportedOperationException(); }
        };
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = authority;
        provider.attachInfo(context, info);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider);
        MediaHttpServer.activeLibrary().addDocument(uri);
        Response found = send("GET", withCode("/api/files?kind=all&q=" + java.net.URLEncoder.encode(name.substring(0, 4), "UTF-8")), null, null);
        assertEquals(200, found.status);
        org.json.JSONArray files = new org.json.JSONObject(found.body).getJSONArray("files");
        for (int i = 0; i < files.length(); i++) if (name.equals(files.getJSONObject(i).getString("name"))) return files.getJSONObject(i);
        throw new AssertionError("picked document not listed: " + found.body);
    }

    @Test public void downloadQueryMakesMediaAnAttachmentWithAUtf8Name() throws Exception {
        org.json.JSONObject item = pickDocument("pocketbridge-dl", "Résumé clip.mp4", "0123456789".getBytes(StandardCharsets.UTF_8));
        HttpURLConnection play = (HttpURLConnection) new URL(BASE + withCode(item.getString("url"))).openConnection();
        try {
            assertEquals(200, play.getResponseCode());
            assertTrue(play.getHeaderField("Content-Disposition").startsWith("inline; "));
        } finally { play.disconnect(); }
        HttpURLConnection save = (HttpURLConnection) new URL(BASE + withCode(item.getString("url") + "?download=1")).openConnection();
        try {
            assertEquals(200, save.getResponseCode());
            assertEquals("attachment; filename=\"R_sum_ clip.mp4\"; filename*=UTF-8''R%C3%A9sum%C3%A9%20clip.mp4",
                    save.getHeaderField("Content-Disposition"));
            assertEquals(10, save.getContentLengthLong());
            assertEquals("bytes", save.getHeaderField("Accept-Ranges"));
            assertNotNull("resumable downloads need a validator", save.getHeaderField("ETag"));
        } finally { save.disconnect(); }
        Response resumed = send("GET", withCode(item.getString("url") + "?download=1"), null, "bytes=4-");
        assertEquals(206, resumed.status);
        assertEquals("456789", resumed.body);
    }

    @Test public void zipOfKnownSizesSendsContentLengthAndOpens() throws Exception {
        org.json.JSONObject first = pickDocument("pocketbridge-zip1", "Zipone.txt", "first file".getBytes(StandardCharsets.UTF_8));
        org.json.JSONObject second = pickDocument("pocketbridge-zip2", "Ziptwo.txt", "second".getBytes(StandardCharsets.UTF_8));
        String ids = java.net.URLEncoder.encode(first.getString("id") + "," + second.getString("id"), "UTF-8");
        HttpURLConnection zip = (HttpURLConnection) new URL(BASE + withCode("/api/zip?ids=" + ids)).openConnection();
        byte[] body;
        try {
            assertEquals(200, zip.getResponseCode());
            assertTrue(zip.getHeaderField("Content-Disposition").startsWith("attachment; filename=\"PocketBridge-files.zip\""));
            try (InputStream in = zip.getInputStream()) { body = in.readAllBytes(); }
            assertEquals(body.length, zip.getContentLengthLong());
        } finally { zip.disconnect(); }
        File out = new File(context.getCacheDir(), "downloaded.zip");
        java.nio.file.Files.write(out.toPath(), body);
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(out)) {
            assertEquals("first file", new String(archive.getInputStream(archive.getEntry("Zipone.txt")).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("second", new String(archive.getInputStream(archive.getEntry("Ziptwo.txt")).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test public void edgeFolderListsWithoutCopyingAndStreamsTheRequestedMovieBytes() throws Exception {
        String owner = "a".repeat(32), id = "b".repeat(32), second = "c".repeat(32);
        long size = 30L * 1024 * 1024 * 1024;
        String api = withCode("/api/edge-files");
        String file = "/api/edge-media/" + id;
        org.json.JSONObject item = new org.json.JSONObject().put("id", id).put("name", "Film.mp4")
                .put("size", size).put("path", "Movies/A/Film.mp4");
        org.json.JSONObject command = new org.json.JSONObject().put("owner", owner).put("action", "publish")
                .put("files", new org.json.JSONArray().put(item));
        assertEquals(403, send("POST", "/api/edge-files", command.toString(), null).status);
        assertEquals(200, send("POST", api, command.toString(), null).status);
        assertEquals(200, send("POST", api, command.put("action", "append")
                .put("files", new org.json.JSONArray().put(item.put("id", second).put("path", "Movies/B/Film.mp4"))).toString(), null).status);
        java.util.List<org.json.JSONObject> files = MediaHttpServer.activeEdgeFiles.snapshot();
        assertEquals(2, files.size());
        assertEquals("Movies/A/Film.mp4", files.get(0).getString("path"));
        assertEquals("Movies/B/Film.mp4", files.get(1).getString("path"));
        assertEquals(200, send("HEAD", file, null, null).status);
        assertEquals(416, send("GET", file, null, "bytes=" + size + "-").status);
        String expected = "frame at end";
        long offset = size - expected.length();
        java.util.concurrent.CompletableFuture<Response> reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return send("GET", file, null, "bytes=" + offset + "-"); }
            catch (Exception err) { throw new RuntimeException(err); }
        });
        Response next = send("POST", api, command.put("action", "next").toString(), null);
        org.json.JSONObject request = new org.json.JSONObject(next.body);
        assertEquals(offset, request.getLong("offset"));
        assertEquals(id, request.getString("file"));
        HttpURLConnection chunk = (HttpURLConnection) new URL(BASE + withCode("/api/edge-chunk/" + request.getString("job"))).openConnection();
        try {
            chunk.setRequestMethod("POST");
            chunk.setRequestProperty("X-Edge-Owner", owner);
            chunk.setDoOutput(true);
            try (OutputStream out = chunk.getOutputStream()) { out.write(expected.getBytes(StandardCharsets.UTF_8)); }
            assertEquals(200, chunk.getResponseCode());
        } finally { chunk.disconnect(); }
        Response delivered = reader.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(206, delivered.status);
        assertEquals(expected, delivered.body);
        assertEquals(200, send("POST", api, command.put("action", "stop").toString(), null).status);
        assertEquals(404, send("GET", file, null, null).status);
        assertTrue(MediaHttpServer.activeEdgeFiles.snapshot().isEmpty());
    }

    @Test public void receivedEdgeFileOpensAfterDisconnectWithOriginalNameAndBytes() throws Exception {
        String name = "Lecture part 1..final.pdf";
        assertEquals(201, send("POST", withCode("/api/upload?name=" + java.net.URLEncoder.encode(name, "UTF-8")),
                "pdf-bytes", null).status);
        org.json.JSONObject received = EdgeInbox.list(context).get(0);
        assertEquals(name, received.getString("name"));
        server.stop();
        server = null;
        android.net.Uri uri = new android.net.Uri.Builder().scheme("content").authority(context.getPackageName() + ".received")
                .appendPath(received.getString("id")).build();
        assertEquals("application/pdf", context.getContentResolver().getType(uri));
        try (InputStream bytes = context.getContentResolver().openInputStream(uri)) { assertEquals("pdf-bytes", readAll(bytes)); }
    }

    @Test public void libraryEchoesTheSortItApplied() throws Exception {
        org.json.JSONObject page = new org.json.JSONObject(send("GET", withCode("/api/files?kind=videos&sort=name_asc"), null, null).body);
        assertEquals("name_asc", page.getString("sort"));
        page = new org.json.JSONObject(send("GET", withCode("/api/files?kind=documents&sort=size_desc&offset=0&limit=10"), null, null).body);
        assertEquals("size_desc", page.getString("sort"));
        assertFalse(page.getBoolean("hasMore"));
        page = new org.json.JSONObject(send("GET", withCode("/api/files?sort=nonsense"), null, null).body);
        assertEquals("date_desc", page.getString("sort"));
    }

    @Test public void cameraCallNeedsBothSidesBeforeAnyOffer() throws Exception {
        CameraCall.INSTANCE.reset();
        try {
            assertEquals(403, send("GET", "/api/camera/status", null, null).status);
            org.json.JSONObject status = new org.json.JSONObject(send("GET", withCode("/api/camera/status"), null, null).body);
            assertEquals("idle", status.getString("state"));
            assertFalse(status.getBoolean("ready"));

            // The laptop asks: it is the requester, the phone has to answer.
            Response asked = send("POST", withCode("/api/camera/request"), "{\"name\":\"Laptop (Edge)\"}", null);
            assertEquals(200, asked.status);
            org.json.JSONObject request = new org.json.JSONObject(asked.body);
            String id = request.getString("id");
            assertEquals("requested", request.getString("state"));
            assertEquals("remote", request.getString("requester"));
            assertTrue(CameraCall.INSTANCE.awaiting(CameraCall.PHONE));
            assertEquals(409, send("POST", withCode("/api/camera/request"), "{}", null).status);

            // No offer is answered before the phone accepts, and stale ids are refused.
            Response early = send("POST", withCode("/api/camera/offer"), "{\"id\":\"" + id + "\",\"sdp\":\"v=0\"}", null);
            assertEquals(409, early.status);
            assertEquals("requested", new org.json.JSONObject(early.body).getString("state"));
            assertEquals(410, send("POST", withCode("/api/camera/offer"), "{\"id\":\"old\",\"sdp\":\"v=0\"}", null).status);
            assertEquals(400, send("POST", withCode("/api/camera/offer"), "{\"id\":\"" + id + "\"}", null).status);
            // The requester cannot answer its own request over HTTP.
            assertEquals(409, send("POST", withCode("/api/camera/respond"), "{\"id\":\"" + id + "\",\"accept\":true}", null).status);

            // Either side stops: the call ends for both and offers stay refused.
            assertEquals(200, send("POST", withCode("/api/camera/stop"), "{\"id\":\"" + id + "\"}", null).status);
            assertEquals("stopped", CameraCall.INSTANCE.state().name().toLowerCase(java.util.Locale.ROOT));
            assertEquals(409, send("POST", withCode("/api/camera/offer"), "{\"id\":\"" + id + "\",\"sdp\":\"v=0\"}", null).status);

            // The phone asks; the laptop declines from Edge.
            String phoneCall = CameraCall.INSTANCE.request(CameraCall.PHONE, "");
            org.json.JSONObject incoming = new org.json.JSONObject(send("GET", withCode("/api/camera/status"), null, null).body);
            assertEquals("phone", incoming.getString("requester"));
            assertTrue(incoming.getLong("expiresInMs") > 0);
            Response declined = send("POST", withCode("/api/camera/respond"), "{\"id\":\"" + phoneCall + "\",\"accept\":false}", null);
            assertEquals(200, declined.status);
            assertEquals("declined", new org.json.JSONObject(declined.body).getString("state"));

            // The phone asks again; the laptop accepts: media is now allowed for that id only.
            String second = CameraCall.INSTANCE.request(CameraCall.PHONE, "");
            assertEquals(200, send("POST", withCode("/api/camera/respond"), "{\"id\":\"" + second + "\",\"accept\":true}", null).status);
            assertTrue(CameraCall.INSTANCE.mediaAllowed(second));
            assertFalse(CameraCall.INSTANCE.mediaAllowed(id));
            assertEquals(405, send("GET", withCode("/api/camera/request"), null, null).status);
            assertEquals(404, send("POST", withCode("/api/camera/hijack"), "{}", null).status);
        } finally {
            CameraCall.INSTANCE.reset();
        }
    }

    @Test public void cameraSignalingAcceptsOnlyTheLocalHelperOrigin() throws Exception {
        // HttpURLConnection drops Origin (a restricted header), so speak HTTP directly.
        String preflight = raw("OPTIONS " + withCode("/api/camera/request") + " HTTP/1.1\r\nHost: 127.0.0.1:" + MediaHttpServer.PORT
                + "\r\nOrigin: http://127.0.0.1:7070\r\nConnection: close\r\n\r\n");
        assertTrue(preflight, preflight.startsWith("HTTP/1.1 204"));
        assertTrue(preflight, preflight.contains("Access-Control-Allow-Origin: http://127.0.0.1:7070"));
        String foreign = raw("GET " + withCode("/api/camera/status") + " HTTP/1.1\r\nHost: 127.0.0.1:" + MediaHttpServer.PORT
                + "\r\nOrigin: http://evil.example\r\nConnection: close\r\n\r\n");
        assertTrue(foreign, foreign.startsWith("HTTP/1.1 403"));
    }

    private static String raw(String request) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", MediaHttpServer.PORT)) {
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return readAll(socket.getInputStream());
        }
    }

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

    /** HttpURLConnection blocks the Origin header as browser-controlled; send it on a raw socket for CORS coverage. */
    private static String rawOptions(String path, String origin) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", MediaHttpServer.PORT)) {
            socket.setSoTimeout(4000);
            String request = "OPTIONS " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: " + origin
                    + "\r\nAccess-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: content-type"
                    + "\r\nAccess-Control-Request-Private-Network: true\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readAll(socket.getInputStream());
        }
    }

    private static String rawGet(String path, String origin) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", MediaHttpServer.PORT)) {
            socket.setSoTimeout(4000);
            String request = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: " + origin
                    + "\r\nAccept: application/json\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readAll(socket.getInputStream());
        }
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

    @Test public void theLockoutStopsEvenTheRightCodeButNotThePhonesOwnToken() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) send("GET", "/api/files?code=999999", null, null);
        assertEquals("a locked-out gate must not test codes at all", 429, send("GET", withCode("/api/files"), null, null).status);
        assertEquals(200, send("GET", "/api/files?token=" + TOKEN, null, null).status);
    }

    @Test public void aFewWrongCodesDoNotLockTheRightOneOut() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) send("GET", "/api/files?code=999999", null, null);
        assertEquals(200, send("GET", withCode("/api/files"), null, null).status);
    }

    // ------------------------------------------------------------- threads

    @Test public void idleConnectionsCannotStarveTheServer() throws Exception {
        // Twenty connections that never send a request used to occupy all 16
        // fixed workers, so every later request (the page, the code check,
        // the phone's own self test) hung until they timed out.
        java.util.List<java.net.Socket> idle = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) idle.add(new java.net.Socket("127.0.0.1", MediaHttpServer.PORT));
            Thread.sleep(200);
            long started = System.currentTimeMillis();
            assertEquals(200, send("GET", "/health", null, null).status);
            assertEquals(200, send("GET", withCode("/api/messages"), null, null).status);
            assertTrue("requests must not wait behind idle connections", System.currentTimeMillis() - started < 3000);
        } finally {
            for (java.net.Socket socket : idle) socket.close();
        }
    }

    @Test public void stoppingTheShareClosesOpenConnections() throws Exception {
        Thread.sleep(300); // let the start-up health checks finish
        int before = server.openConnectionCount();
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", MediaHttpServer.PORT)) {
            socket.setSoTimeout(5000);
            // Half a request: the worker is now blocked reading, like a stalled viewer.
            socket.getOutputStream().write("GET /api/".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            for (int i = 0; i < 100 && server.openConnectionCount() <= before; i++) Thread.sleep(20);
            assertTrue(server.openConnectionCount() > before);
            server.stop();
            long started = System.currentTimeMillis();
            try {
                InputStream in = socket.getInputStream();
                while (in.read() != -1) { /* drain until the server closes the socket */ }
            } catch (java.net.SocketException closed) {
                // reset by the server: also closed
            }
            assertTrue("the socket should close at once, not at its read timeout", System.currentTimeMillis() - started < 3000);
            server = null;
        }
    }

    // ---------------------------------------------------------------- chat

    @Test public void browserAssetsExposeScrollableChatAndExpandedVideoControls() throws Exception {
        Response page = send("GET", "/", null, null);
        assertEquals(200, page.status);
        assertTrue("chat should scroll naturally on touch screens", page.body.contains("#chatMessages{max-height:min(58vh,560px)")
                && page.body.contains("overscroll-behavior-y:contain") && page.body.contains("touch-action:pan-y"));
        assertTrue("video controls should include speed and volume", page.body.contains("playbackSpeed") && page.body.contains("videoVolume"));
        assertTrue("expanded aspect ratios should be present", page.body.contains("21-9") && page.body.contains("16-10") && page.body.contains("9-16") && page.body.contains("3-2"));
        assertFalse("incoming files must not trigger an automatic download prompt", page.body.contains("Download all to this laptop now?"));
    }

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
        HttpURLConnection typed = (HttpURLConnection) new URL(BASE + withCode("/api/inbox/" + id)).openConnection();
        assertTrue("a received file is served with its real type so it can open in place",
                typed.getContentType().startsWith("text/plain"));
        typed.disconnect();
        HttpURLConnection saved = (HttpURLConnection) new URL(BASE + withCode("/api/inbox/" + id + "?download=1")).openConnection();
        assertEquals(200, saved.getResponseCode());
        assertEquals("attachment; filename=\"note.txt\"; filename*=UTF-8''note.txt", saved.getHeaderField("Content-Disposition"));
        assertEquals(content.length(), saved.getContentLengthLong());
        saved.disconnect();
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
        assertTrue(status.body.contains("\"starting\":false"));
        assertEquals(403, send("GET", "/api/screen/status", null, null).status);
    }

    @Test public void cameraLaunchUsesHelperPortWithoutDisclosingItsToken() throws Exception {
        MediaHttpServer.setLaptopHelperLink("http://192.168.12.2:7090/?t=helper-secret");
        Response script = send("GET", "/camera-launch.js", null, null);
        assertEquals(200, script.status);
        assertTrue(script.body.contains("http://127.0.0.1:7090"));
        assertFalse(script.body.contains("helper-secret"));
        assertFalse(script.body.contains(TOKEN));
    }

    @Test public void cameraAcceptsAuthenticatedOfferFromItsOwnHotspotPage() throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", MediaHttpServer.PORT)) {
            socket.setSoTimeout(4000);
            String host = "192.168.43.1:" + MediaHttpServer.PORT;
            String request = "POST " + withCode("/api/camera/offer") + " HTTP/1.1\r\nHost: " + host
                    + "\r\nOrigin: http://" + host + "\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            String reply = readAll(socket.getInputStream());
            assertTrue(reply, reply.startsWith("HTTP/1.1 400"));
            assertTrue(reply, reply.contains("offer is empty"));
        }
    }

    @Test public void cameraSignalingRequiresThePhoneCodeAndAllowsOnlyTheLocalHelperOrigin() throws Exception {
        String allowed = rawOptions(withCode("/api/camera/offer"), "http://127.0.0.1:7070");
        assertTrue(allowed, allowed.startsWith("HTTP/1.1 204"));
        assertTrue(allowed, allowed.toLowerCase(java.util.Locale.ROOT).contains("access-control-allow-origin: http://127.0.0.1:7070"));
        assertTrue(allowed, allowed.toLowerCase(java.util.Locale.ROOT).contains("access-control-allow-private-network: true"));

        String rejected = rawOptions(withCode("/api/camera/offer"), "https://untrusted.example");
        assertTrue(rejected, rejected.startsWith("HTTP/1.1 403"));
        String status = rawGet(withCode("/api/camera/status"), "http://127.0.0.1:7070");
        assertTrue(status, status.startsWith("HTTP/1.1 200"));
        assertTrue(status, status.toLowerCase(java.util.Locale.ROOT).contains("access-control-allow-origin: http://127.0.0.1:7070"));

        assertEquals("camera APIs still require the six-digit access code", 403,
                send("OPTIONS", "/api/camera/offer", null, null).status);
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
