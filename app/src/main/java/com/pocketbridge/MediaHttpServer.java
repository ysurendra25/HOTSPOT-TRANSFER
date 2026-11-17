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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class MediaHttpServer {
    static final int PORT = 43117;
    private static final int THUMB_TARGET = 384;
    private static final int THUMB_CACHE_BYTES = 24 * 1024 * 1024;
    // A six digit code is short enough to type on a laptop, so it is rate limited.
    private static final int MAX_BAD_CODES_PER_WINDOW = 20;
    private static final long BAD_CODE_WINDOW_MS = 60_000L;
    private final Context context;
    private final String code;
    private final String token;
    private final MediaRepository media;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger badCodes = new AtomicInteger(0);
    private volatile long badCodeWindowStart = 0L;
    private final ExecutorService clients = new ThreadPoolExecutor(
            4, 24, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            runnable -> {
                Thread thread = new Thread(runnable, "PocketBridge-http-client");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
    private final LruCache<String, byte[]> thumbCache = new LruCache<String, byte[]>(THUMB_CACHE_BYTES) {
        @Override protected int sizeOf(String key, byte[] value) { return value == null ? 0 : value.length; }
    };
    private ServerSocket serverSocket;
    private Thread acceptThread;

    MediaHttpServer(Context context, String code, String token) {
        this.context = context.getApplicationContext();
        this.code = code;
        this.token = token;
        this.media = new MediaRepository(context);
    }

    void start() throws Exception {
        // setReuseAddress lets a quick restart rebind the port instead of failing
        // with "Address already in use" while the old socket is still closing.
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT), 24);
        running.set(true);
        acceptThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(40000);
                    socket.setTcpNoDelay(true);
                    socket.setSendBufferSize(512 * 1024);
                    clients.execute(() -> handle(socket));
                } catch (Exception error) {
                    if (running.get()) android.util.Log.e("PocketBridge", "HTTP accept failed", error);
                }
            }
        }, "PocketBridge-http");
        acceptThread.start();
    }

    void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        clients.shutdownNow();
        thumbCache.evictAll();
    }

    private void handle(Socket socket) {
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
            int contentLength = 0;
            try { if (lengthText != null) contentLength = Math.min(Integer.parseInt(lengthText), 2 * 1024 * 1024); } catch (Exception ignoredLength) { }
            byte[] body = method.equals("POST") ? readBytes(input, contentLength) : new byte[0];

            // The page itself carries no data, so it loads before the code is entered.
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

            if (!authorized(target, headers)) {
                if (looksLikeCodeAttempt(target)) {
                    if (tooManyBadCodes()) {
                        respond(output, 429, "application/json; charset=utf-8",
                                "{\"error\":\"Too many wrong codes. Wait a minute and try again.\"}", null);
                        return;
                    }
                }
                respond(output, 403, "application/json; charset=utf-8",
                        "{\"error\":\"Enter the code shown in PocketBridge on the phone.\"}", null);
                return;
            }
            resetBadCodes();

            if (path.equals("/api/session") && method.equals("GET")) {
                respond(output, 200, "application/json", "{\"ok\":true}", null);
                return;
            }
            if (path.equals("/api/files") && method.equals("GET")) {
                String kind = target.getQueryParameter("kind");
                if (kind == null) kind = "all";
                int offset = queryInt(target, "offset", 0, 0, 20_000);
                int limit = queryInt(target, "limit", 120, 1, 5_000);
                MediaRepository.Page page = media.listPage(kind, offset, limit);
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
            if (path.startsWith("/api/media/") && (method.equals("GET") || method.equals("HEAD"))) {
                String key = Uri.decode(path.substring("/api/media/".length()));
                MediaRepository.Item item = media.find(key);
                if (item == null) { respond(output, 404, "text/plain", "Media not found or permission is missing", null); return; }
                streamMedia(output, method, headers, item);
                return;
            }
            if (path.equals("/api/screen/status") && method.equals("GET")) {
                JSONObject state = new JSONObject().put("ready", ScreenCaptureService.isRunning())
                        .put("iceState", ScreenCaptureService.getIceState());
                String setupError = ScreenCaptureService.getSetupError();
                if (setupError != null) state.put("error", setupError);
                String offerError = ScreenCaptureService.getLastOfferError();
                if (offerError != null) state.put("offerError", offerError);
                respond(output, 200, "application/json; charset=utf-8", state.toString(), null);
                return;
            }
            if (path.equals("/api/screen/offer") && method.equals("POST")) {
                JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String sdp = request.optString("sdp", "");
                String answer = ScreenCaptureService.answerOffer(sdp);
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
                ScreenCaptureService.stopPeer();
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

    private boolean tooManyBadCodes() {
        long now = System.currentTimeMillis();
        if (now - badCodeWindowStart > BAD_CODE_WINDOW_MS) {
            badCodeWindowStart = now;
            badCodes.set(0);
        }
        return badCodes.incrementAndGet() > MAX_BAD_CODES_PER_WINDOW;
    }

    private void resetBadCodes() {
        badCodes.set(0);
        badCodeWindowStart = System.currentTimeMillis();
    }

    /** Cached JPEG preview so the browser grid never downloads full-size originals. */
    private byte[] thumbnail(MediaRepository.Item item) {
        byte[] cached = thumbCache.get(item.key);
        if (cached != null) return cached;
        byte[] built = buildThumbnail(item);
        if (built != null) thumbCache.put(item.key, built);
        return built;
    }

    private byte[] buildThumbnail(MediaRepository.Item item) {
        try {
            if ("video".equals(item.kind)) {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    try {
                        Bitmap frame = context.getContentResolver().loadThumbnail(item.uri,
                                new android.util.Size(THUMB_TARGET, THUMB_TARGET), null);
                        if (frame != null) return compress(frame);
                    } catch (Exception ignored) { /* vendor providers can omit MediaStore previews */ }
                }
                return videoFrameThumbnail(item);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream probe = context.getContentResolver().openInputStream(item.uri)) {
                if (probe == null) return null;
                BitmapFactory.decodeStream(probe, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= THUMB_TARGET && bounds.outHeight / (sample * 2) >= THUMB_TARGET) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap;
            try (InputStream stream = context.getContentResolver().openInputStream(item.uri)) {
                if (stream == null) return null;
                bitmap = BitmapFactory.decodeStream(stream, null, options);
            }
            if (bitmap == null) return null;
            int rotation = readRotation(item);
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                bitmap = rotated;
            }
            return compress(bitmap);
        } catch (Exception | OutOfMemoryError error) {
            return null;
        }
    }

    /** Uses a frame from the original video when the device's thumbnail provider fails. */
    private byte[] videoFrameThumbnail(MediaRepository.Item item) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(item.uri, "r")) {
            if (descriptor == null) return null;
            retriever.setDataSource(descriptor.getFileDescriptor());
            Bitmap frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int width = frame.getWidth();
            int height = frame.getHeight();
            float scale = Math.min(1f, (float) THUMB_TARGET / Math.max(width, height));
            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)), true);
                if (scaled != frame) frame.recycle();
                frame = scaled;
            }
            return compress(frame);
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

    private static byte[] compress(Bitmap bitmap) {
        if (bitmap == null) return null;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
            return out.toByteArray();
        } catch (Exception error) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private void streamMedia(OutputStream output, String method, Map<String, String> headers, MediaRepository.Item item) throws Exception {
        long total = Math.max(0, item.size);
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
        copyMediaRange(output, item, start, length);
    }

    private void copyMediaRange(OutputStream output, MediaRepository.Item item, long start, long length) throws Exception {
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
                    copyBytes(file, output, length);
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

    private static void copyBytes(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[256 * 1024];
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

    private static final class ByteRange {
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

    private static void writeHead(OutputStream output, int status, String type, long length, String extra, String unused) throws Exception {
        String reason = status == 200 ? "OK"
                : status == 204 ? "No Content"
                : status == 206 ? "Partial Content"
                : status == 403 ? "Forbidden"
                : status == 404 ? "Not Found"
                : status == 409 ? "Conflict"
                : status == 416 ? "Range Not Satisfiable"
                : status == 429 ? "Too Many Requests"
                : "Bad Request";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + length + "\r\n" +
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

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
