package com.pocketbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class MediaHttpServer {
    static final int PORT = 43117;
    private static final int THUMB_TARGET = 480;
    private static final int THUMB_CACHE_BYTES = 24 * 1024 * 1024;
    private static final int PREVIEW_TARGET = 1920;
    private static final int PREVIEW_CACHE_BYTES = 32 * 1024 * 1024;
    // Keeps the request line of a multi-file ZIP download well under the 8 KB header limit.
    static final int MAX_ZIP_FILES = 300;
    private static final int MAX_JSON_BODY_BYTES = 2 * 1024 * 1024;
    static final long MAX_UPLOAD_BYTES = Long.MAX_VALUE;
    private static volatile String laptopHelperLink = "";

    static void setLaptopHelperLink(String link) { laptopHelperLink = link == null ? "" : link.trim(); }

    /** True when a POST body of this declared size may be accepted. */
    static boolean uploadAllowed(long declaredLength) {
        return declaredLength > 0 && declaredLength <= MAX_UPLOAD_BYTES;
    }

    static boolean hasEnoughStorage(long availableBytes, long requiredBytes) {
        return requiredBytes > 0 && availableBytes >= requiredBytes;
    }

    // A six digit code is short enough to type on a laptop, so it is rate limited.
    private static final int MAX_BAD_CODES_PER_WINDOW = 20;
    private static final long BAD_CODE_WINDOW_MS = 60_000L;
    private final Context context;
    private final String code;
    private final String token;
    private final MediaRepository media;
    private final EdgeFiles edgeFiles = new EdgeFiles();
    static volatile EdgeFiles activeEdgeFiles;
    private static volatile MediaRepository activeLibrary;
    static MediaRepository activeLibrary() { return activeLibrary; }
    private final VideoCompatibility videoCompatibility;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger badCodes = new AtomicInteger(0);
    private volatile long badCodeWindowStart = 0L;
    private final ExecutorService clients = newClientPool();
    /** Every open client socket, so stop() can end streams that are still playing. */
    private final Set<Socket> openSockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Open screen streams, oldest first. A reconnecting viewer replaces the oldest. */
    private final java.util.concurrent.ConcurrentLinkedDeque<Socket> screenStreams = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** When a screen-stream write started, per socket; the watchdog closes stuck ones. */
    private final Map<Socket, Long> streamWrites = new java.util.concurrent.ConcurrentHashMap<>();
    static final int MAX_SCREEN_STREAMS = 2;
    static final long STUCK_STREAM_WRITE_MS = 10_000L;
    private Thread watchdogThread;
    private final LruCache<String, byte[]> thumbCache = new LruCache<String, byte[]>(THUMB_CACHE_BYTES) {
        @Override protected int sizeOf(String key, byte[] value) { return value == null ? 0 : value.length; }
    };
    private static final java.util.concurrent.Semaphore THUMB_SLOTS = new java.util.concurrent.Semaphore(2);
    private final LruCache<String, byte[]> previewCache = new LruCache<String, byte[]>(PREVIEW_CACHE_BYTES) {
        @Override protected int sizeOf(String key, byte[] value) { return value == null ? 0 : value.length; }
    };
    private ServerSocket serverSocket;
    private Thread acceptThread;

    MediaHttpServer(Context context, String code, String token) {
        this.context = context.getApplicationContext();
        this.code = code;
        this.token = token;
        this.media = new MediaRepository(context);
        this.videoCompatibility = new VideoCompatibility(context);
    }

    static final int MAX_CLIENT_THREADS = 64;

    /**
     * Workers for client connections. Long-lived streams (the screen, a playing
     * video, a big download) each hold a worker for as long as they run. With a
     * fixed 16 workers and a waiting queue, a few streams plus a gallery of
     * thumbnails left every new request queued behind them, so the laptop page
     * and the phone's own checks hung. Workers are now created on demand up to
     * a hard cap; past the cap a request gets an immediate 503 instead of
     * waiting forever.
     */
    private static ExecutorService newClientPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(8, MAX_CLIENT_THREADS, 30L, TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "PocketBridge-http-client");
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    void start() throws Exception {
        // setReuseAddress lets a quick restart rebind the port instead of failing
        // with "Address already in use" while the old socket is still closing.
        serverSocket = java.nio.channels.ServerSocketChannel.open().socket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT), 24);
        activeEdgeFiles = edgeFiles;
        activeLibrary = media;
        running.set(true);
        acceptThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(40000);
                    socket.setTcpNoDelay(true);
                    openSockets.add(socket);
                    try {
                        clients.execute(() -> handle(socket));
                    } catch (RejectedExecutionException rejected) {
                        rejectBusy(socket);
                    }
                } catch (Exception error) {
                    if (running.get()) android.util.Log.e("PocketBridge", "HTTP accept failed", error);
                }
            }
        }, "PocketBridge-http");
        acceptThread.start();
        watchdogThread = new Thread(this::watchStreams, "PocketBridge-stream-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    /** Every worker is busy: answer at once rather than leave the browser hanging. */
    private void rejectBusy(Socket socket) {
        try (Socket closing = socket) {
            closing.setSoTimeout(1000);
            OutputStream output = closing.getOutputStream();
            respond(output, 503, "application/json; charset=utf-8",
                    "{\"error\":\"The phone is busy with other transfers. Try again in a moment.\"}", "Retry-After: 2\r\n");
        } catch (Exception ignored) {
        } finally {
            openSockets.remove(socket);
        }
    }

    /**
     * A screen viewer that stops reading (a laptop that slept, a closed lid, a
     * dropped hotspot) leaves the phone blocked in a socket write that has no
     * timeout. Close any screen stream whose write has been stuck too long so
     * its worker and its JPEG encoder are released.
     */
    private void watchStreams() {
        while (running.get()) {
            try { Thread.sleep(2000); } catch (InterruptedException interrupted) { return; }
            long now = android.os.SystemClock.elapsedRealtime();
            for (Map.Entry<Socket, Long> entry : streamWrites.entrySet()) {
                if (now - entry.getValue() > STUCK_STREAM_WRITE_MS) {
                    android.util.Log.w("PocketBridge", "Closing a screen stream whose viewer stopped reading");
                    try { entry.getKey().close(); } catch (IOException ignored) { }
                    streamWrites.remove(entry.getKey());
                }
            }
        }
    }

    /** Number of client sockets currently open; used by tests to catch leaks. */
    int openConnectionCount() { return openSockets.size(); }

    void stop() {
        edgeFiles.clear();
        if (activeEdgeFiles == edgeFiles) activeEdgeFiles = null;
        if (activeLibrary == media) activeLibrary = null;
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        // Blocking socket I/O ignores thread interrupts, so a playing video or a
        // screen stream would outlive the share unless its socket is closed.
        for (Socket socket : openSockets) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        openSockets.clear();
        if (watchdogThread != null) watchdogThread.interrupt();
        clients.shutdownNow();
        videoCompatibility.stop();
        thumbCache.evictAll();
        previewCache.evictAll();
    }

    private void handle(Socket socket) {
        try {
            handleRequest(socket);
        } finally {
            openSockets.remove(socket);
            streamWrites.remove(socket);
        }
    }

    private final LaptopCast laptopCast = new LaptopCast();

    private void castAsset(BufferedOutputStream output, String name, String mime) throws Exception {
        try (InputStream input = context.getAssets().open("www/" + name)) {
            respond(output, 200, mime, new String(readAll(input), StandardCharsets.UTF_8),
                    "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n");
        }
    }

    private void handleRequest(Socket socket) {
        try (Socket ignored = socket;
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
            String requestLine = readLine(input, 8192);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { respond(output, 400, "text/plain", "Bad request", null); return; }
            String method = parts[0].toUpperCase(Locale.ROOT);
            Uri target = Uri.parse("http://local" + parts[1]);
            String path = Uri.decode(target.getEncodedPath());
            Map<String, String> headers = readHeaders(input);
            String lengthText = headers.get("content-length");
            // Keep the declared size for validation; clamp only what is read into
            // memory. Clamping first used to hide an oversized upload, which was then
            // silently truncated and reported as a success.
            long declaredLength = 0L;
            try { if (lengthText != null) declaredLength = Long.parseLong(lengthText.trim()); } catch (Exception ignoredLength) { }
            // Refuse an oversized body before reading it. Reading first would tie up a
            // worker for the whole socket timeout while a too-large upload drained.
            boolean uploadRequest = method.equals("POST") && path.equals("/api/upload");
            long maxRequestBytes = uploadRequest ? MAX_UPLOAD_BYTES : MAX_JSON_BODY_BYTES;
            if (method.equals("POST") && declaredLength > maxRequestBytes) {
                respond(output, 413, "application/json; charset=utf-8",
                        uploadRequest ? "{\"error\":\"Upload must include a valid, non-empty file size.\"}" : "{\"error\":\"Request body is too large.\"}", null);
                return;
            }


            // The page itself carries no data, so it loads before the code is entered.
            if ((path.equals("/camera-launch.js") || path.equals("/laptop-cast.js")) && method.equals("GET")) {
                try (InputStream asset = context.getAssets().open("www" + path)) {
                    HelperLink helper = HelperLink.tryParse(laptopHelperLink);
                    String script = new String(readAll(asset), StandardCharsets.UTF_8)
                            .replace("__PB_HELPER_PORT__", String.valueOf(helper == null ? 7070 : helper.port));
                    respond(output, 200, "text/javascript; charset=utf-8", script, "Cache-Control: no-store\r\n");
                }
                return;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                respond(output, 200, "text/html; charset=utf-8", page(false), "Cache-Control: no-store\r\n");
                return;
            }
            // Old links that embedded the long token keep working.
            if (path.startsWith("/share/")) {
                String requestToken = path.substring("/share/".length());
                if (!constantTimeEquals(requestToken, token)) {
                    respond(output, 403, "text/plain; charset=utf-8", "This share link is invalid or expired.", null);
                    return;
                }
                respond(output, 200, "text/html; charset=utf-8", page(true), "Cache-Control: no-store\r\n");
                return;
            }
            if (path.equals("/health")) { respond(output, 200, "application/json", "{\"ok\":true}", null); return; }
            if (path.equals("/favicon.ico")) { writeHead(output, 204, "text/plain", 0, null, null); output.flush(); return; }

            // A local media player receives only its opaque, session-scoped file URL.
            if (path.startsWith("/api/edge-media/") && (method.equals("GET") || method.equals("HEAD"))) {
                if (!socket.getInetAddress().isLoopbackAddress()) { respond(output, 403, "text/plain", "Phone playback only", null); return; }
                streamEdgeFile(output, method, headers, path.substring("/api/edge-media/".length()));
                return;
            }
            // While locked out, no six-digit code is checked at all, not even the
            // right one; otherwise the lockout only slowed a guesser down without
            // stopping one. The long token (from the phone's own link) still works.
            if (looksLikeCodeAttempt(target) && !hasTokenCredential(target, headers) && codesLockedOut()) {
                respond(output, 429, "application/json; charset=utf-8",
                        "{\"error\":\"Too many wrong codes. Wait a minute and try again.\"}", "Retry-After: 60\r\n");
                return;
            }
            if (!authorized(target, headers)) {
                if (looksLikeCodeAttempt(target) && registerBadCode()) {
                    respond(output, 429, "application/json; charset=utf-8",
                            "{\"error\":\"Too many wrong codes. Wait a minute and try again.\"}", "Retry-After: 60\r\n");
                    return;
                }
                respond(output, 403, "application/json; charset=utf-8",
                        "{\"error\":\"Enter the code shown in PocketBridge on the phone.\"}", null);
                return;
            }

            int contentLength = uploadRequest ? 0 : (int) Math.max(0, declaredLength);
            byte[] body = method.equals("POST") && !uploadRequest ? readBytes(input, contentLength) : new byte[0];

            if (path.equals("/api/windows-helper") && (method.equals("GET") || method.equals("HEAD"))) {
                try (android.content.res.AssetFileDescriptor archive = context.getAssets().openFd("PocketBridge-Windows-Control.zip")) {
                    writeHead(output, 200, "application/zip", archive.getLength(),
                            "Content-Disposition: attachment; filename=\"PocketBridge-Windows-Control.zip\"\r\nCache-Control: no-store\r\n", null);
                    if (method.equals("GET")) {
                        try (InputStream contents = archive.createInputStream()) {
                            copyBytes(contents, output, archive.getLength());
                        }
                    }
                    output.flush();
                }
                return;
            }
            if (path.equals("/watch-laptop.html") || path.equals("/api/laptop-cast")) {
                String viewerHost = "127.0.0.1:" + PORT;
                boolean onPhone = socket.getInetAddress().isLoopbackAddress()
                        && viewerHost.equals(headers.get("host"))
                        && ("http://" + viewerHost).equals(headers.getOrDefault("origin", "http://" + viewerHost));
                if (path.equals("/watch-laptop.html") && method.equals("GET") && onPhone) {
                    castAsset(output, "watch-laptop.html", "text/html; charset=utf-8");
                } else if (path.equals("/api/laptop-cast") && method.equals("POST")) {
                    try {
                        JSONObject reply = laptopCast.exchange(new JSONObject(new String(body, StandardCharsets.UTF_8)),
                                onPhone, android.os.SystemClock.elapsedRealtime());
                        respond(output, 200, "application/json", reply.toString(), "Cache-Control: no-store\r\n");
                    } catch (LaptopCast.Refused e) {
                        respond(output, e.status, "application/json", new JSONObject().put("error", e.getMessage()).toString(), null);
                    } catch (org.json.JSONException invalid) {
                        respond(output, 400, "application/json", "{\"error\":\"Invalid presentation message.\"}", null);
                    }
                } else {
                    respond(output, 403, "text/plain", "Open the viewer on the phone.", null);
                }
                return;
            }
            if (path.equals("/api/open-vlc") && method.equals("GET")) {
                String helper = laptopHelperLink;
                Uri helperUri = Uri.parse(helper);
                String helperToken = helperUri.getQueryParameter("t");
                String mediaUrl = target.getQueryParameter("url");
                if (!"http".equalsIgnoreCase(helperUri.getScheme()) || helperUri.getHost() == null
                        || helperToken == null || helperToken.isEmpty() || mediaUrl == null
                        || !(mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://"))) {
                    respond(output, 503, "text/plain; charset=utf-8",
                            "Paste and start the Windows helper link in Connect before using VLC.", null);
                    return;
                }
                String launch = helperUri.buildUpon().path("/open-vlc").clearQuery()
                        .appendQueryParameter("t", helperToken).appendQueryParameter("url", mediaUrl).build().toString();
                respond(output, 302, "text/plain; charset=utf-8", "Opening VLC",
                        "Location: " + launch + "\r\n");
                return;
            }

            if (path.equals("/api/open-with") && method.equals("GET")) {
                String helper = laptopHelperLink;
                Uri helperUri = Uri.parse(helper);
                String helperToken = helperUri.getQueryParameter("t");
                String mediaUrl = target.getQueryParameter("url");
                if (!"http".equalsIgnoreCase(helperUri.getScheme()) || helperUri.getHost() == null
                        || helperToken == null || helperToken.isEmpty() || mediaUrl == null
                        || !(mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://"))) {
                    respond(output, 503, "text/plain; charset=utf-8",
                            "Start the Windows helper and paste its current link in Connect to choose a laptop player.", null);
                    return;
                }
                String launch = helperUri.buildUpon().path("/open-with").clearQuery()
                        .appendQueryParameter("t", helperToken).appendQueryParameter("url", mediaUrl).build().toString();
                respond(output, 302, "text/plain; charset=utf-8", "Choose a player",
                        "Location: " + launch + "\r\n");
                return;
            }

            if (path.equals("/api/edge-files") && method.equals("POST")) {
                try {
                    JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String owner = request.optString("owner");
                    JSONObject result = new JSONObject();
                    switch (request.optString("action")) {
                        case "publish": case "append":
                            edgeFiles.publish(owner, request.optJSONArray("files"), "append".equals(request.optString("action"))); break;
                        case "next": result = edgeFiles.next(owner); break;
                        case "stop": edgeFiles.stopOwner(owner); break;
                        default: throw new EdgeFiles.Failure(400, "Unknown file action.");
                    }
                    respond(output, 200, "application/json", result.toString(), "Cache-Control: no-store\r\n");
                } catch (EdgeFiles.Failure error) {
                    respond(output, error.status, "application/json", new JSONObject().put("error", error.getMessage()).toString(), null);
                } catch (org.json.JSONException invalid) { respond(output, 400, "application/json", "{}", null); }
                return;
            }
            if (path.startsWith("/api/edge-chunk/") && method.equals("POST")) {
                try {
                    edgeFiles.deliver(headers.get("x-edge-owner"), path.substring("/api/edge-chunk/".length()), body);
                    respond(output, 200, "application/json", "{}", null);
                } catch (EdgeFiles.Failure error) { respond(output, error.status, "text/plain", error.getMessage(), null); }
                return;
            }
            if (path.equals("/api/upload") && method.equals("POST")) {
                if (!uploadAllowed(declaredLength)) {
                    respond(output, 413, "application/json; charset=utf-8", "{\"error\":\"Upload must include a valid, non-empty file size.\"}", null);
                    return;
                }
                String requested = target.getQueryParameter("name");
                String safeName = requested == null ? "shared-file" : requested.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
                if (safeName.isEmpty() || safeName.equals(".") || safeName.equals("..")) safeName = "shared-file";
                File inbox = new File(context.getFilesDir(), "received");
                if (!inbox.exists() && !inbox.mkdirs()) {
                    respond(output, 500, "application/json; charset=utf-8", "{\"error\":\"Could not create the receive folder.\"}", null);
                    return;
                }
                if (!hasEnoughStorage(inbox.getUsableSpace(), declaredLength)) {
                    respond(output, 507, "application/json; charset=utf-8", "{\"error\":\"The phone needs more free storage for this file.\"}", null);
                    return;
                }
                File destination = new File(inbox, "edge-" + java.util.UUID.randomUUID().toString().replace("-", "") + "-" + safeName);
                File partial = File.createTempFile("pb-upload-", ".part", context.getCacheDir());
                long receivedBytes = 0;
                try (java.io.FileOutputStream file = new java.io.FileOutputStream(partial)) {
                    // A 1 MiB streaming buffer keeps multi-gigabyte hotspot uploads
                    // from paying a read/write round trip for every 64 KiB chunk.
                    byte[] buffer = new byte[1024 * 1024];
                    while (receivedBytes < declaredLength) {
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, declaredLength - receivedBytes));
                        if (read < 0) throw new IOException("The upload ended early.");
                        if (!hasEnoughStorage(inbox.getUsableSpace(), read)) throw new StorageFullException();
                        file.write(buffer, 0, read);
                        receivedBytes += read;
                        long remaining = declaredLength - receivedBytes;
                        if (remaining > 0 && !hasEnoughStorage(inbox.getUsableSpace(), remaining)) throw new StorageFullException();
                    }
                    file.getFD().sync();
                } catch (Exception uploadError) {
                    // Never leave a truncated file in the recipient's inbox.
                    //noinspection ResultOfMethodCallIgnored
                    partial.delete();
                    int status = uploadError instanceof StorageFullException ? 507 : 400;
                    String message = status == 507 ? "The phone ran out of free storage. Free space and try again." : "The upload did not finish. Try sending the file again.";
                    respond(output, status, "application/json; charset=utf-8", new JSONObject().put("error", message).toString(), null);
                    return;
                }
                if (!partial.renameTo(destination)) {
                    partial.delete();
                    respond(output, 500, "application/json", "{\"error\":\"Could not save the uploaded file. Try a shorter filename.\"}", null);
                    return;
                }
                ChatStore.appendFile(context, safeName, destination.getName(), receivedBytes, "laptop");
                respond(output, 201, "application/json; charset=utf-8",
                        new JSONObject().put("ok", true).put("name", safeName).put("bytes", receivedBytes).toString(), null);
                return;
            }
            if (path.equals("/api/inbox") && method.equals("GET")) {
                File inbox = new File(context.getFilesDir(), "received");
                org.json.JSONArray received = new org.json.JSONArray();
                File[] entries = inbox.listFiles(File::isFile);
                if (entries != null) {
                    java.util.Arrays.sort(entries, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (File entry : entries) received.put(new JSONObject().put("name", EdgeInbox.name(entry.getName()))
                            .put("id", entry.getName()).put("size", entry.length()).put("modified", entry.lastModified()));
                }
                respond(output, 200, "application/json; charset=utf-8", new JSONObject().put("files", received).toString(), null);
                return;
            }
            if (path.equals("/api/messages") && method.equals("GET")) {
                String json = new JSONObject().put("messages", ChatStore.read(context)).toString();
                respond(output, 200, "application/json; charset=utf-8", json, "Cache-Control: no-store\r\n");
                return;
            }
            if (path.equals("/api/messages") && method.equals("POST")) {
                JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String text = request.optString("text", "").trim();
                if (text.isEmpty() || text.length() > 2000) {
                    respond(output, 400, "application/json; charset=utf-8", "{\"error\":\"Message must be between 1 and 2000 characters.\"}", null);
                    return;
                }
                ChatStore.append(context, text, request.optString("from", "laptop"));
                respond(output, 201, "application/json; charset=utf-8", "{\"ok\":true}", null);
                return;
            }
            if (path.startsWith("/api/inbox/") && (method.equals("GET") || method.equals("HEAD"))) {
                String id = Uri.decode(path.substring("/api/inbox/".length()));
                if (id.contains("/") || id.contains("\\") || id.contains("..")) {
                    respond(output, 400, "text/plain", "Invalid file name", null); return;
                }
                File received = new File(new File(context.getFilesDir(), "received"), id);
                if (!received.isFile()) { respond(output, 404, "text/plain", "Received file not found", null); return; }
                String receivedName = EdgeInbox.name(id);
                // The real type lets the phone and the laptop open a received photo,
                // video or PDF directly instead of only offering a download.
                streamFile(socket, output, method, headers, received, MediaTypeClassifier.mimeType(receivedName, null), receivedName);
                return;
            }

            if (path.startsWith("/api/video/compatible/") && (method.equals("GET") || method.equals("HEAD"))) {
                String key = Uri.decode(path.substring("/api/video/compatible/".length()));
                MediaRepository.Item item = media.find(key);
                if (item == null || !"video".equals(item.kind)) {
                    respond(output, 404, "application/json; charset=utf-8", "{\"error\":\"Video not found or media access is missing.\"}", null);
                    return;
                }
                try {
                    VideoCompatibility.Job job = videoCompatibility.prepare(item);
                    String range = headers.get("range");
                    if (job.done || (range != null && !range.equals("bytes=0-"))) {
                        streamFile(socket, output, method, headers, job.awaitComplete(), "video/mp4", item.name + ".browser.mp4");
                    } else if (method.equals("HEAD")) {
                        writeHead(output, 200, "video/mp4", -1, null, null);
                    } else {
                        try (InputStream growing = job.openGrowing()) {
                            writeHead(output, 200, "video/mp4", -1, null, null);
                            output.flush();
                            // Headers are committed. Terminate this stream on disconnect or conversion failure.
                            try { job.copyGrowing(growing, output); } catch (IOException disconnected) { return; }
                        }
                    }
                } catch (Exception conversionError) {
                    JSONObject failure = new JSONObject().put("error", conversionError.getMessage() == null
                            ? "The phone could not prepare this video for browser playback." : conversionError.getMessage());
                    respond(output, 422, "application/json; charset=utf-8", failure.toString(), null);
                } catch (LinkageError missingNativeCodec) {
                    respond(output, 422, "application/json; charset=utf-8",
                            "{\"error\":\"Video conversion is not available for this phone's processor. Download the original video instead.\"}", null);
                }
                return;
            }

            if (path.equals("/api/session") && method.equals("GET")) {
                respond(output, 200, "application/json", "{\"ok\":true}", null);
                return;
            }
            if (path.equals("/api/files") && method.equals("GET")) {
                String kind = target.getQueryParameter("kind");
                if (kind == null) kind = "all";
                int offset = queryInt(target, "offset", 0, 0, Integer.MAX_VALUE);
                int limit = queryInt(target, "limit", 120, 1, 5_000);
                MediaRepository.Page page = media.listPage(kind, offset, limit, target.getQueryParameter("q"));
                JSONObject response = new JSONObject()
                        .put("files", media.listJson(page))
                        .put("nextOffset", page.nextOffset)
                        .put("hasMore", page.hasMore)
                        .put("access", media.accessJson());
                respond(output, 200, "application/json; charset=utf-8", response.toString(), null);
                return;
            }
            if (path.startsWith("/api/thumb/") && method.equals("GET")) {
                String key = Uri.decode(path.substring("/api/thumb/".length()));
                MediaRepository.Item item = media.find(key);
                if (item == null) { respond(output, 404, "text/plain", "Media not found or permission is missing", null); return; }
                byte[] preview = thumbnail(item);
                if (preview == null) { respond(output, 404, "text/plain", "No preview available", null); return; }
                writeHead(output, 200, "image/jpeg", preview.length,
                        "Cache-Control: private, max-age=3600\r\n", null);
                output.write(preview);
                output.flush();
                return;
            }
            if (path.startsWith("/api/preview/") && method.equals("GET")) {
                String key = Uri.decode(path.substring("/api/preview/".length()));
                MediaRepository.Item item = media.find(key);
                if (item == null) { respond(output, 404, "text/plain", "Media not found or permission is missing", null); return; }
                byte[] picture = preview(item);
                if (picture == null) { respond(output, 404, "text/plain", "No preview available", null); return; }
                writeHead(output, 200, "image/jpeg", picture.length,
                        "Cache-Control: private, max-age=3600\r\n", null);
                output.write(picture);
                output.flush();
                return;
            }
            if (path.equals("/api/zip") && method.equals("GET")) {
                streamZip(output, target);
                return;
            }
            if (path.startsWith("/api/media/") && (method.equals("GET") || method.equals("HEAD"))) {
                String key = Uri.decode(path.substring("/api/media/".length()));
                MediaRepository.Item item = media.find(key);
                if (item == null) { respond(output, 404, "text/plain", "Media not found or permission is missing", null); return; }
                streamMedia(socket, output, method, headers, item);
                return;
            }
            // The Windows helper's outbound long-poll (see LaptopRelay): it picks up
            // laptop commands queued by the phone and posts back their results.
            if (path.equals("/api/laptop-commands") && method.equals("POST")) {
                JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if ("result".equals(request.optString("action"))) {
                    String message = request.optString("message", "");
                    if (!request.optBoolean("ok", false) && !request.optString("error", "").isEmpty()) message = request.optString("error");
                    LaptopRelay.INSTANCE.complete(request.optString("id"), request.optBoolean("ok", false), message);
                    respond(output, 200, "application/json; charset=utf-8", "{\"ok\":true}", null);
                    return;
                }
                LaptopRelay.Command next = LaptopRelay.INSTANCE.poll(request.optString("version", ""), LaptopRelay.POLL_HOLD_MS);
                JSONObject reply = new JSONObject().put("command", next == null ? JSONObject.NULL : next.toJson());
                respond(output, 200, "application/json; charset=utf-8", reply.toString(), "Cache-Control: no-store\r\n");
                return;
            }
            if (path.equals("/api/airplane")) {
                JSONObject request = method.equals("POST") ? new JSONObject(new String(body, StandardCharsets.UTF_8)) : new JSONObject();
                JSONObject reply = AirplaneController.handleApi(context, method, request);
                respond(output, reply.optInt("httpStatus", 200), "application/json; charset=utf-8", reply.toString(), "Cache-Control: no-store\r\n");
                return;
            }
            if (path.equals("/api/screen/status") && method.equals("GET")) {
                JSONObject state = new JSONObject().put("ready", ScreenCaptureService.isRunning())
                        .put("starting", ScreenCaptureService.isStarting())
                        .put("iceState", ScreenCaptureService.getIceState())
                        .put("frames", ScreenCaptureService.framesSent())
                        .put("lastFrameAgeMs", ScreenCaptureService.lastFrameAgeMs())
                        .put("capture", ScreenCaptureService.captureDescription())
                        .put("encoder", ScreenCaptureService.encoderDescription());
                String setupError = ScreenCaptureService.getSetupError();
                if (setupError != null) state.put("error", setupError);
                String offerError = ScreenCaptureService.getLastOfferError();
                if (offerError != null) state.put("offerError", offerError);
                respond(output, 200, "application/json; charset=utf-8", state.toString(), null);
                return;
            }
            if ((path.equals("/api/camera/status") || path.equals("/api/camera/offer") || path.equals("/api/camera/stop")) && method.equals("OPTIONS")) {
                String cors = cameraCorsHeaders(headers);
                if (cors == null) {
                    respond(output, 403, "text/plain; charset=utf-8", "Camera calls are accepted only from the laptop's local helper page.", null);
                } else {
                    respond(output, 204, "text/plain; charset=utf-8", "", cors);
                }
                return;
            }
            if (path.equals("/api/camera/status") && method.equals("GET")) {
                String cors = cameraCorsHeaders(headers);
                if (headers.containsKey("origin") && cors == null) {
                    respond(output, 403, "application/json; charset=utf-8", "{\"error\":\"Open the camera page from the Windows helper on this laptop.\"}", null);
                    return;
                }
                JSONObject state = new JSONObject().put("ready", CameraSessionService.isRunning())
                        .put("status", CameraSessionService.statusText());
                respond(output, 200, "application/json; charset=utf-8", state.toString(), cors);
                return;
            }
            if (path.equals("/api/camera/offer") && method.equals("POST")) {
                String cors = cameraCorsHeaders(headers);
                if (headers.containsKey("origin") && cors == null) {
                    respond(output, 403, "application/json; charset=utf-8", "{\"error\":\"Open the camera page from the Windows helper on this laptop.\"}", null);
                    return;
                }
                JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String sdp = request.optString("sdp", "");
                if (sdp.isEmpty() || sdp.length() > 200_000) {
                    respond(output, 400, "application/json; charset=utf-8", "{\"error\":\"The laptop camera offer is empty or too large.\"}", cors);
                    return;
                }
                if (!CameraSessionService.isRunning()) {
                    respond(output, 409, "application/json; charset=utf-8", "{\"error\":\"Start the phone camera session and approve Android camera access first.\"}", cors);
                    return;
                }
                String answer = CameraSessionService.answerOffer(sdp);
                if (answer == null || answer.isEmpty()) {
                    String error = CameraSessionService.lastOfferError();
                    JSONObject failure = new JSONObject().put("error", error == null ? "The phone could not answer the camera call." : error);
                    respond(output, 409, "application/json; charset=utf-8", failure.toString(), cors);
                    return;
                }
                respond(output, 200, "application/json; charset=utf-8",
                        new JSONObject().put("type", "answer").put("sdp", answer).toString(), cors);
                return;
            }
            if (path.equals("/api/camera/stop") && method.equals("POST")) {
                String cors = cameraCorsHeaders(headers);
                if (headers.containsKey("origin") && cors == null) {
                    respond(output, 403, "application/json; charset=utf-8", "{\"error\":\"Open the camera page from the Windows helper on this laptop.\"}", null);
                    return;
                }
                CameraSessionService.stopPeer();
                respond(output, 200, "application/json; charset=utf-8", "{\"ok\":true}", cors);
                return;
            }
            if (path.equals("/api/screen/mjpeg") && method.equals("GET")) {
                if (!ScreenCaptureService.isRunning()) {
                    String setupError = ScreenCaptureService.getSetupError();
                    JSONObject refusal = new JSONObject().put("error", setupError == null || setupError.isEmpty()
                            ? "Start Share phone screen on the phone and approve Android's capture prompt first."
                            : setupError);
                    respond(output, 409, "application/json; charset=utf-8", refusal.toString(), null);
                    return;
                }
                // WebRTC is the low-latency path. Only allocate and encode JPEGs
                // while a browser is actually reading the compatibility stream.
                ScreenCaptureService.mjpegViewerStarted();
                screenStreams.addLast(socket);
                // One laptop reconnecting must not pile up streams: past the limit
                // the oldest stream is closed and its worker returns to the pool.
                while (screenStreams.size() > MAX_SCREEN_STREAMS) {
                    Socket oldest = screenStreams.pollFirst();
                    if (oldest == null || oldest == socket) break;
                    try { oldest.close(); } catch (IOException ignoredClose) { }
                }
                try {
                    streamScreen(socket, output);
                } finally {
                    screenStreams.remove(socket);
                    streamWrites.remove(socket);
                    ScreenCaptureService.mjpegViewerEnded();
                }
                return;
            }
            if (path.equals("/api/screen/offer") && method.equals("POST")) {
                JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String sdp = request.optString("sdp", "");
                String answer = ScreenCaptureService.answerOffer(sdp, request.optString("viewer", ""));
                if (answer == null) {
                    String error = ScreenCaptureService.getLastOfferError();
                    JSONObject failure = new JSONObject().put("error", error == null
                            ? "Screen setup did not finish. Restart screen sharing on the phone and try again."
                            : error);
                    respond(output, 409, "application/json; charset=utf-8", failure.toString(), null);
                    return;
                }
                JSONObject response = new JSONObject().put("type", "answer").put("sdp", answer);
                respond(output, 200, "application/json", response.toString(), null);
                return;
            }
            if (path.equals("/api/screen/stop") && method.equals("POST")) {
                String viewer = target.getQueryParameter("viewer");
                ScreenCaptureService.stopPeer(viewer == null ? "" : viewer);
                respond(output, 200, "application/json", "{\"ok\":true}", null);
                return;
            }
            respond(output, 404, "text/plain; charset=utf-8", "Not found", null);
        } catch (Exception error) {
            android.util.Log.w("PocketBridge", "HTTP client closed", error);
        }
    }

    /**
     * @param withToken only the legacy /share/<token> link embeds the long token;
     *                  the plain / page must never leak it, because anyone on the
     *                  network can load / and read the source.
     */
    private String page(boolean withToken) throws Exception {
        byte[] html;
        try (InputStream asset = context.getAssets().open("www/index.html")) { html = readAll(asset); }
        return new String(html, StandardCharsets.UTF_8).replace("__POCKETBRIDGE_TOKEN__", withToken ? token : "");
    }

    /** Accepts the short code, the legacy long token, or a bearer token. */
    private boolean authorized(Uri target, Map<String, String> headers) {
        String suppliedCode = target.getQueryParameter("code");
        if (suppliedCode != null && constantTimeEquals(code, suppliedCode.trim())) return true;
        String suppliedToken = target.getQueryParameter("token");
        if (suppliedToken != null && constantTimeEquals(token, suppliedToken)) return true;
        String auth = headers.get("authorization");
        return auth != null && constantTimeEquals("Bearer " + token, auth);
    }

    private static boolean looksLikeCodeAttempt(Uri target) {
        String suppliedCode = target.getQueryParameter("code");
        return suppliedCode != null && !suppliedCode.trim().isEmpty();
    }

    private boolean hasTokenCredential(Uri target, Map<String, String> headers) {
        String suppliedToken = target.getQueryParameter("token");
        if (suppliedToken != null && constantTimeEquals(token, suppliedToken)) return true;
        String auth = headers.get("authorization");
        return auth != null && constantTimeEquals("Bearer " + token, auth);
    }

    /** Counts one wrong code; true when that makes the window go over the limit. */
    private synchronized boolean registerBadCode() {
        long now = System.currentTimeMillis();
        if (now - badCodeWindowStart > BAD_CODE_WINDOW_MS) {
            badCodeWindowStart = now;
            badCodes.set(0);
        }
        return badCodes.incrementAndGet() > MAX_BAD_CODES_PER_WINDOW;
    }

    /** True while the wrong-code limit for the current window has been passed. */
    private synchronized boolean codesLockedOut() {
        long now = System.currentTimeMillis();
        if (now - badCodeWindowStart > BAD_CODE_WINDOW_MS) {
            badCodeWindowStart = now;
            badCodes.set(0);
            return false;
        }
        return badCodes.get() > MAX_BAD_CODES_PER_WINDOW;
    }

    /** Cached JPEG preview so the browser grid never downloads full-size originals. */
    private byte[] thumbnail(MediaRepository.Item item) {
        byte[] cached = thumbCache.get(item.key);
        if (cached != null) return cached;
        byte[] built = buildThumbnail(item);
        if (built != null) thumbCache.put(item.key, built);
        return built;
    }

    /** Larger JPEG for the photo viewer, cached separately from the grid thumbnails. */
    private byte[] preview(MediaRepository.Item item) {
        byte[] cached = previewCache.get(item.key);
        if (cached != null) return cached;
        byte[] built = buildPreview(item);
        if (built != null) previewCache.put(item.key, built);
        return built;
    }

    private byte[] buildThumbnail(MediaRepository.Item item) {
        // One page can ask for a hundred thumbnails at once. Running a decoder for
        // each of them in parallel exhausts the phone's memory, which is what made
        // video previews fail to load. Build at most two at a time.
        THUMB_SLOTS.acquireUninterruptibly();
        try {
            return buildThumbnailNow(item);
        } finally {
            THUMB_SLOTS.release();
        }
    }

    private byte[] buildThumbnailNow(MediaRepository.Item item) {
        try {
            if ("video".equals(item.kind)) {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    try {
                        Bitmap frame = context.getContentResolver().loadThumbnail(item.uri,
                                new android.util.Size(THUMB_TARGET * 2, THUMB_TARGET * 2), null);
                        if (frame != null) return compress(orient(null, frame, THUMB_TARGET, false), 82);
                    } catch (Exception ignored) { /* vendor providers can omit MediaStore previews */ }
                }
                return videoFrameThumbnail(item);
            }
            if ("audio".equals(item.kind)) return audioArtwork(item);
            Bitmap bitmap = decodeImage(item, THUMB_TARGET, false);
            if (bitmap == null && android.os.Build.VERSION.SDK_INT >= 29) {
                // Formats BitmapFactory cannot read (some HEIC or AVIF files) usually
                // still have a thumbnail in the media store.
                try {
                    bitmap = context.getContentResolver().loadThumbnail(item.uri,
                            new android.util.Size(THUMB_TARGET * 2, THUMB_TARGET * 2), null);
                } catch (Exception ignored) { }
                if (bitmap != null) return compress(orient(null, bitmap, THUMB_TARGET, false), 82);
            }
            if (bitmap == null) return null;
            return compress(orient(item, bitmap, THUMB_TARGET, false), 82);
        } catch (Exception | OutOfMemoryError error) {
            return null;
        }
    }

    /** Album art embedded in an audio file, so music shows a picture like videos do. */
    private byte[] audioArtwork(MediaRepository.Item item) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            try { retriever.setDataSource(context, item.uri); }
            catch (Exception uriFailed) {
                try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(item.uri, "r")) {
                    if (descriptor == null) return null;
                    retriever.setDataSource(descriptor.getFileDescriptor());
                }
            }
            byte[] embedded = retriever.getEmbeddedPicture();
            if (embedded == null || embedded.length == 0) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(embedded, 0, embedded.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_TARGET) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap art = BitmapFactory.decodeByteArray(embedded, 0, embedded.length, options);
            if (art == null) return null;
            return compress(orient(null, art, THUMB_TARGET, false), 82);
        } catch (Exception | OutOfMemoryError error) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private byte[] buildPreview(MediaRepository.Item item) {
        try {
            Bitmap bitmap = decodeImage(item, PREVIEW_TARGET, true);
            if (bitmap == null && android.os.Build.VERSION.SDK_INT >= 29) {
                try {
                    bitmap = context.getContentResolver().loadThumbnail(item.uri,
                            new android.util.Size(PREVIEW_TARGET, PREVIEW_TARGET), null);
                } catch (Exception ignored) { }
                if (bitmap != null) return compress(orient(null, bitmap, PREVIEW_TARGET, true), 86);
            }
            if (bitmap == null) return null;
            return compress(orient(item, bitmap, PREVIEW_TARGET, true), 86);
        } catch (Exception | OutOfMemoryError error) {
            return null;
        }
    }

    /**
     * Decodes an image that is still at least {@code target} pixels on the chosen
     * side: the shorter side for grid thumbnails, the longer side for the viewer.
     */
    private Bitmap decodeImage(MediaRepository.Item item, int target, boolean longSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream probe = context.getContentResolver().openInputStream(item.uri)) {
            if (probe == null) return null;
            BitmapFactory.decodeStream(probe, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (true) {
            int w = bounds.outWidth / (sample * 2);
            int h = bounds.outHeight / (sample * 2);
            int side = longSide ? Math.max(w, h) : Math.min(w, h);
            if (side < target) break;
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream stream = context.getContentResolver().openInputStream(item.uri)) {
            if (stream == null) return null;
            return BitmapFactory.decodeStream(stream, null, options);
        }
    }

    /**
     * Applies the EXIF rotation (when an item is given) and shrinks the bitmap so
     * the chosen side equals {@code target}. Bitmaps already smaller are kept.
     */
    private Bitmap orient(MediaRepository.Item item, Bitmap source, int target, boolean longSide) {
        if (source.getConfig() == Bitmap.Config.HARDWARE) {
            // Hardware bitmaps cannot be resized or rotated on the CPU; make a software copy first.
            Bitmap software = source.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) return source;
            source.recycle();
            source = software;
        }
        int rotation = item == null ? 0 : readRotation(item);
        int side = longSide ? Math.max(source.getWidth(), source.getHeight()) : Math.min(source.getWidth(), source.getHeight());
        float scale = side > target ? (float) target / side : 1f;
        if (rotation == 0 && scale == 1f) return source;
        Matrix matrix = new Matrix();
        if (rotation != 0) matrix.postRotate(rotation);
        if (scale != 1f) matrix.postScale(scale, scale);
        Bitmap result = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (result != source) source.recycle();
        return result;
    }

    /** Uses a frame from the original video when the device's thumbnail provider fails. */
    private byte[] videoFrameThumbnail(MediaRepository.Item item) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        boolean prepared = false;
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(item.uri, "r")) {
            // Some providers hand back a pipe instead of a real file, and a descriptor
            // that cannot seek makes the retriever fail. Fall back to the content URI,
            // which lets Android resolve it itself.
            if (descriptor != null) {
                try { retriever.setDataSource(descriptor.getFileDescriptor()); prepared = true; }
                catch (Exception ignoredDescriptor) { prepared = false; }
            }
            if (!prepared) {
                try { retriever.setDataSource(context, item.uri); prepared = true; }
                catch (Exception ignoredUri) { return null; }
            }
            // A frame a little way in avoids the black or fading-in first frame
            // that many videos start with.
            long timeUs = 0;
            try {
                long durationMs = Long.parseLong(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
                timeUs = durationMs > 2000 ? 1_000_000L : durationMs * 500L;
            } catch (Exception ignoredDuration) { }
            Bitmap frame = retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null && timeUs != 0) frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                // A video whose only sync frames sit far away can still be read with
                // the non-sync option, which decodes forward from the nearest keyframe.
                frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST);
            }
            if (frame == null) return null;
            return compress(orient(null, frame, THUMB_TARGET, false), 82);
        } catch (Exception | OutOfMemoryError error) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private int readRotation(MediaRepository.Item item) {
        try (InputStream stream = context.getContentResolver().openInputStream(item.uri)) {
            if (stream == null) return 0;
            android.media.ExifInterface exif = new android.media.ExifInterface(stream);
            int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270) return 270;
            return 0;
        } catch (Exception error) {
            return 0;
        }
    }

    private static byte[] compress(Bitmap bitmap, int quality) {
        if (bitmap == null) return null;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            return out.toByteArray();
        } catch (Exception error) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private void streamEdgeFile(OutputStream out, String method, Map<String, String> headers, String id) throws Exception {
        JSONObject item = edgeFiles.find(id);
        if (item == null) { respond(out, 404, "text/plain", "The Edge tab is no longer sharing this file.", null); return; }
        long size = item.getLong("size");
        ByteRange range = ByteRange.parse(headers.get("range"), size);
        if (range.unsatisfiable) { writeHead(out, 416, "text/plain", 0, "Content-Range: bytes */" + size + "\r\n", null); return; }
        long offset = range.start;
        long remaining = size == 0 ? 0 : range.end - offset + 1;
        String extra = "Cache-Control: private, no-store\r\nAccept-Ranges: bytes\r\n";
        if (range.partial) extra += "Content-Range: bytes " + offset + "-" + range.end + "/" + size + "\r\n";
        byte[] part = null;
        if (remaining > 0 && !"HEAD".equals(method)) {
            try { part = edgeFiles.read(id, offset, (int)Math.min(remaining, EdgeFiles.CHUNK)); }
            catch (EdgeFiles.Failure unavailable) { respond(out, unavailable.status, "text/plain", unavailable.getMessage(), null); return; }
        }
        writeHead(out, range.partial ? 206 : 200, item.getString("mime"), remaining, extra, null);
        out.flush();
        try {
            while (part != null) {
                out.write(part);
                out.flush();
                offset += part.length;
                remaining -= part.length;
                part = remaining == 0 ? null : edgeFiles.read(id, offset, (int)Math.min(remaining, EdgeFiles.CHUNK));
            }
        } catch (IOException ended) { /* A phone seek, stopped transfer or closed Edge tab ends this response. */ }
    }

    private void streamMedia(Socket socket, OutputStream output, String method, Map<String, String> headers, MediaRepository.Item item) throws Exception {
        long total = Math.max(0, item.size);
        if (total == 0) {
            // A provider can expose a readable file but omit its size. Do not
            // send Content-Length: 0, which makes video playback fail instantly.
            String safeName = item.name.replace("\"", "_").replace("\r", "_").replace("\n", "_");
            writeHead(output, 200, item.mime, -1,
                    "Content-Disposition: inline; filename=\"" + safeName + "\"\r\n", null);
            output.flush();
            if (!method.equals("HEAD")) copyMediaRange(socket, output, item, 0, Long.MAX_VALUE);
            return;
        }
        ByteRange range = ByteRange.parse(headers.get("range"), total);
        if (range.unsatisfiable) {
            writeHead(output, 416, "text/plain", 0, "Content-Range: bytes */" + total + "\r\n", null);
            return;
        }
        long start = range.start;
        long end = range.end;
        boolean partial = range.partial;
        long length = total == 0 ? 0 : end - start + 1;
        String safeName = item.name.replace("\"", "_").replace("\r", "_").replace("\n", "_");
        String encodedName = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20");
        String extra = "Accept-Ranges: bytes\r\nCache-Control: private, max-age=300\r\nContent-Disposition: inline; filename=\"" + safeName + "\"; filename*=UTF-8''" + encodedName + "\r\n";
        if (partial) extra += "Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n";
        writeHead(output, partial ? 206 : 200, item.mime, length, extra, null);
        output.flush();
        if (method.equals("HEAD") || length == 0) return;
        copyMediaRange(socket, output, item, start, length);
    }

    private void streamFile(Socket socket, OutputStream output, String method, Map<String, String> headers, File file,
                            String mime, String downloadName) throws Exception {
        long total = file.length();
        ByteRange range = ByteRange.parse(headers.get("range"), total);
        if (range.unsatisfiable) {
            writeHead(output, 416, "text/plain", 0, "Content-Range: bytes */" + total + "\r\n", null);
            return;
        }
        long length = total == 0 ? 0 : range.end - range.start + 1;
        String safeName = downloadName.replace("\"", "_").replace("\r", "_").replace("\n", "_");
        String extra = "Accept-Ranges: bytes\r\nCache-Control: private, max-age=3600\r\nContent-Disposition: inline; filename=\"" + safeName + "\"\r\n";
        if (range.partial) extra += "Content-Range: bytes " + range.start + "-" + range.end + "/" + total + "\r\n";
        writeHead(output, range.partial ? 206 : 200, mime, length, extra, null);
        output.flush();
        if (method.equals("HEAD") || length == 0) return;
        try (FileInputStream input = new FileInputStream(file)) {
            sendFileRange(socket, input, output, range.start, length);
        }
    }

    /**
     * Sends the newest JPEG frame whenever one is ready. Frames produced while
     * a write is still going are skipped, never queued, so a slow link shows a
     * lower frame rate instead of an ever-growing delay.
     */
    private void streamScreen(Socket socket, OutputStream output) throws Exception {
        socket.setSendBufferSize(128 * 1024);
        // Do not stop/start the MediaProjection capture on viewer connection.
        // Rebuilding its virtual display stalls the first frame on some phones.
        ScreenCaptureService.JpegFrame frame = ScreenCaptureService.awaitJpegFrame(-1, 6000);
        if (frame == null) {
            respond(output, 503, "application/json; charset=utf-8",
                    "{\"error\":\"Android did not produce a screen frame. Stop screen sharing on the phone, start it again, and retry.\"}", null);
            return;
        }
        writeHead(output, 200, "multipart/x-mixed-replace; boundary=pocketbridge-frame", -1,
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n", null);
        long lastSequence = frame.sequence;
        byte[] lastBytes = frame.bytes;
        timedFrameWrite(socket, output, lastBytes);
        long lastWrite = android.os.SystemClock.elapsedRealtime();
        while (running.get() && ScreenCaptureService.isRunning() && !socket.isClosed()) {
            ScreenCaptureService.JpegFrame next = ScreenCaptureService.awaitJpegFrame(lastSequence, 250);
            if (next == null && ScreenCaptureService.mjpegViewers() > 0) {
                // The encoder can be switched off by a direct WebRTC viewer
                // connecting; this viewer still needs it.
                ScreenCaptureService.prepareMjpegFallback();
            }
            boolean wrote = false;
            if (next != null) {
                lastSequence = next.sequence;
                lastBytes = next.bytes;
                timedFrameWrite(socket, output, lastBytes);
                lastWrite = android.os.SystemClock.elapsedRealtime();
                wrote = true;
            }
            // Android only hands over a frame when the screen changes, so a still
            // screen would leave the browser on one frozen picture and could let
            // the connection look dead. Repeat the last picture now and then.
            if (!wrote && android.os.SystemClock.elapsedRealtime() - lastWrite >= 5000) {
                timedFrameWrite(socket, output, lastBytes);
                lastWrite = android.os.SystemClock.elapsedRealtime();
            }
        }
        output.write("--pocketbridge-frame--\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private void timedFrameWrite(Socket socket, OutputStream output, byte[] jpeg) throws IOException {
        streamWrites.put(socket, android.os.SystemClock.elapsedRealtime());
        try {
            writeMjpegFrame(output, jpeg);
        } finally {
            streamWrites.remove(socket);
        }
    }

    static void writeMjpegFrame(OutputStream output, byte[] jpeg) throws IOException {
        output.write("--pocketbridge-frame\r\nContent-Type: image/jpeg\r\nContent-Length: ".getBytes(StandardCharsets.US_ASCII));
        output.write(Integer.toString(jpeg.length).getBytes(StandardCharsets.US_ASCII));
        output.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        output.write(jpeg);
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    /**
     * Streams the chosen files as one ZIP so a whole selection downloads in a
     * single click. Photos and videos are already compressed, so entries are
     * written without recompression, which keeps the phone fast.
     */
    private void streamZip(OutputStream output, Uri target) throws Exception {
        String ids = target.getQueryParameter("ids");
        List<MediaRepository.Item> items = new ArrayList<>();
        if (ids != null) {
            for (String id : ids.split(",")) {
                if (items.size() >= MAX_ZIP_FILES) break;
                MediaRepository.Item item = media.find(id.trim());
                if (item != null) items.add(item);
            }
        }
        if (items.isEmpty()) {
            respond(output, 404, "application/json; charset=utf-8", "{\"error\":\"None of the selected files are available.\"}", null);
            return;
        }
        // The size is not known up front, so the body runs until the connection closes.
        writeHead(output, 200, "application/zip", -1,
                "Content-Disposition: attachment; filename=\"PocketBridge-files.zip\"\r\n", null);
        output.flush();
        ZipOutputStream zip = new ZipOutputStream(output);
        zip.setLevel(Deflater.NO_COMPRESSION);
        Set<String> used = new HashSet<>();
        for (MediaRepository.Item item : items) {
            zip.putNextEntry(new ZipEntry(uniqueEntryName(item.name, used)));
            copyMediaRange(null, zip, item, 0, Long.MAX_VALUE);
            zip.closeEntry();
        }
        zip.finish();
        output.flush();
    }

    static String uniqueEntryName(String name, Set<String> used) {
        String clean = name == null ? "file" : name.replace('/', '_').replace('\\', '_').trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean = "file";
        String candidate = clean;
        int dot = clean.lastIndexOf('.');
        String base = dot > 0 ? clean.substring(0, dot) : clean;
        String extension = dot > 0 ? clean.substring(dot) : "";
        int counter = 2;
        while (!used.add(candidate)) candidate = base + " (" + counter++ + ")" + extension;
        return candidate;
    }

    private void copyMediaRange(Socket socket, OutputStream output, MediaRepository.Item item, long start, long length) throws Exception {
        ParcelFileDescriptor descriptor = null;
        FileInputStream file = null;
        try {
            try { descriptor = context.getContentResolver().openFileDescriptor(item.uri, "r"); }
            catch (IOException unavailableDescriptor) { /* fall back to the stream API */ }
            if (descriptor != null) {
                file = new FileInputStream(descriptor.getFileDescriptor());
                FileChannel channel = file.getChannel();
                boolean seekable = false;
                try { channel.position(start); seekable = true; }
                catch (IOException notSeekable) { /* provider returned a pipe */ }
                if (seekable) {
                    sendFileRange(socket, file, output, start, length);
                    return;
                }
            }
        } finally {
            try { if (file != null) file.close(); } catch (Exception ignored) { }
            try { if (descriptor != null) descriptor.close(); } catch (Exception ignored) { }
        }
        try (InputStream fallback = context.getContentResolver().openInputStream(item.uri)) {
            if (fallback == null) return;
            skipFully(fallback, start);
            copyBytes(fallback, output, length);
        }
    }

    // Headers are already flushed by the caller. Transfer directly from disk to the
    // socket when the platform supports it. ZIP and provider pipes use the stream path.
    private static void sendFileRange(Socket socket, FileInputStream input, OutputStream output,
                                      long start, long length) throws IOException {
        FileChannel file = input.getChannel();
        long position = start;
        long remaining = length;
        if (socket != null && socket.getChannel() != null && length != Long.MAX_VALUE) {
            while (remaining > 0) {
                long sent;
                try {
                    sent = file.transferTo(position, Math.min(remaining, 8L * 1024 * 1024), socket.getChannel());
                } catch (UnsupportedOperationException unavailable) {
                    break;
                }
                if (sent == 0) break;
                position += sent;
                remaining -= sent;
            }
        }
        if (remaining > 0) {
            file.position(position);
            copyBytes(input, output, remaining);
        }
    }

    private static void copyBytes(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[512 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        output.flush();
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) { if (input.read() < 0) break; skipped = 1; }
            remaining -= skipped;
        }
    }

    private static int queryInt(Uri target, String name, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(target.getQueryParameter(name)))); }
        catch (Exception ignored) { return fallback; }
    }

    static final class ByteRange {
        final long start;
        final long end;
        final boolean partial;
        final boolean unsatisfiable;

        private ByteRange(long start, long end, boolean partial, boolean unsatisfiable) {
            this.start = start; this.end = end; this.partial = partial; this.unsatisfiable = unsatisfiable;
        }

        static ByteRange parse(String header, long total) {
            if (header == null || !header.startsWith("bytes=") || total <= 0 || header.indexOf(',') >= 0) {
                return new ByteRange(0, Math.max(0, total - 1), false, false);
            }
            String[] bounds = header.substring(6).trim().split("-", 2);
            if (bounds.length != 2) return new ByteRange(0, Math.max(0, total - 1), false, false);
            try {
                long start;
                long end;
                if (bounds[0].isEmpty()) {
                    long suffix = Long.parseLong(bounds[1]);
                    if (suffix <= 0) return new ByteRange(0, 0, false, true);
                    start = Math.max(0, total - suffix);
                    end = total - 1;
                } else {
                    start = Long.parseLong(bounds[0]);
                    end = bounds[1].isEmpty() ? total - 1 : Math.min(total - 1, Long.parseLong(bounds[1]));
                }
                if (start < 0 || start >= total || end < start) return new ByteRange(0, 0, false, true);
                return new ByteRange(start, end, true, false);
            } catch (NumberFormatException malformed) {
                return new ByteRange(0, Math.max(0, total - 1), false, false);
            }
        }
    }

    private static void respond(OutputStream output, int status, String contentType, String body, String extra) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        writeHead(output, status, contentType, data.length, extra, null);
        output.write(data);
        output.flush();
    }

    static String reasonPhrase(int status) {
        switch (status) {
            case 200: return "OK";
            case 302: return "Found";
            case 201: return "Created";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 410: return "Gone";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return status >= 500 ? "Server Error" : "Bad Request";
        }
    }

    static void writeHead(OutputStream output, int status, String type, long length, String extra, String unused) throws Exception {
        String reason = reasonPhrase(status);
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                (length >= 0 ? "Content-Length: " + length + "\r\n" : "") +
                ((extra != null && extra.toLowerCase(java.util.Locale.ROOT).contains("cache-control:")) ? "" : "Cache-Control: no-store\r\n") +
                "Connection: close\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                (extra == null ? "" : extra) + "\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> readHeaders(InputStream input) throws Exception {
        Map<String, String> headers = new HashMap<>();
        String line;
        int count = 0;
        while ((line = readLine(input, 8192)) != null && !line.isEmpty() && count++ < 100) {
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        return headers;
    }

    private static String readLine(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') output.write(value);
            if (output.size() > limit) throw new IllegalArgumentException("HTTP header line too long");
        }
        if (value == -1 && output.size() == 0) return null;
        return output.toString("UTF-8");
    }

    private static byte[] readBytes(InputStream input, int length) throws Exception {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(result, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        if (offset == length) return result;
        byte[] shortened = new byte[offset];
        System.arraycopy(result, 0, shortened, 0, offset);
        return shortened;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static final class StorageFullException extends IOException {
        StorageFullException() { super("Not enough free storage for the remaining file."); }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /** Allow browser camera signaling only from the helper's secure localhost origin. */
    private static String cameraCorsHeaders(Map<String, String> headers) {
        String origin = headers.get("origin");
        if (("http://" + headers.get("host")).equalsIgnoreCase(origin)) return "";
        if (origin == null || origin.isEmpty()) return "";
        Uri parsed = Uri.parse(origin);
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
        if (!"http".equalsIgnoreCase(scheme) || !loopback || origin.indexOf('\r') >= 0 || origin.indexOf('\n') >= 0) return null;
        return "Access-Control-Allow-Origin: " + origin + "\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Access-Control-Allow-Private-Network: true\r\n"
                + "Vary: Origin\r\n";
    }
}
