package com.pocketbridge;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;
import org.webrtc.SessionDescription;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class MediaHttpServer {
    private static final int PORT = 43117;
    private final Context context;
    private final String token;
    private final MediaRepository media;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService clients = Executors.newFixedThreadPool(6);
    private ServerSocket serverSocket;
    private Thread acceptThread;

    MediaHttpServer(Context context, String token) {
        this.context = context.getApplicationContext();
        this.token = token;
        this.media = new MediaRepository(context);
    }

    void start() throws Exception {
        serverSocket = new ServerSocket(PORT, 12, InetAddress.getByName("0.0.0.0"));
        running.set(true);
        acceptThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(20000);
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

            if (path.startsWith("/share/")) {
                String requestToken = path.substring("/share/".length());
                if (!constantTimeEquals(requestToken, token)) { respond(output, 403, "text/plain; charset=utf-8", "This share link is invalid or expired.", null); return; }
                byte[] html;
                try (InputStream asset = context.getAssets().open("www/index.html")) { html = readAll(asset); }
                String page = new String(html, StandardCharsets.UTF_8).replace("__POCKETBRIDGE_TOKEN__", token);
                respond(output, 200, "text/html; charset=utf-8", page, "Cache-Control: no-store\r\n");
                return;
            }
            if (path.equals("/health")) { respond(output, 200, "application/json", "{\"ok\":true}", null); return; }

            String auth = headers.get("authorization");
            String queryToken = target.getQueryParameter("token");
            if (!constantTimeEquals(token, queryToken) && (auth == null || !constantTimeEquals("Bearer " + token, auth))) {
                respond(output, 403, "application/json", "{\"error\":\"Invalid share link\"}", null);
                return;
            }

            if (path.equals("/api/files") && method.equals("GET")) {
                String kind = target.getQueryParameter("kind");
                if (kind == null) kind = "all";
                JSONObject response = new JSONObject().put("files", media.listJson(kind));
                respond(output, 200, "application/json; charset=utf-8", response.toString(), null);
                return;
            }
            if (path.startsWith("/api/media/") && (method.equals("GET") || method.equals("HEAD"))) {
                String key = Uri.decode(path.substring("/api/media/".length()));
                MediaRepository.Item item = media.find(key);
                if (item == null) { respond(output, 404, "text/plain", "Media not found or permission is missing", null); return; }
                streamMedia(output, input, method, headers, item);
                return;
            }
            if (path.equals("/api/screen/status") && method.equals("GET")) {
                respond(output, 200, "application/json", "{\"ready\":" + ScreenCaptureService.isRunning() + "}", null);
                return;
            }
            if (path.equals("/api/screen/offer") && method.equals("POST")) {
                JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String sdp = request.optString("sdp", "");
                String answer = ScreenCaptureService.answerOffer(sdp);
                if (answer == null) { respond(output, 409, "application/json", "{\"error\":\"Start screen sharing on the phone first\"}", null); return; }
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

    private void streamMedia(OutputStream output, InputStream input, String method, Map<String, String> headers, MediaRepository.Item item) throws Exception {
        long total = Math.max(0, item.size);
        long start = 0;
        long end = Math.max(0, total - 1);
        boolean partial = false;
        String range = headers.get("range");
        if (range != null && range.startsWith("bytes=") && total > 0) {
            String[] bounds = range.substring(6).split("-", 2);
            try {
                start = Long.parseLong(bounds[0]);
                if (bounds.length > 1 && !bounds[1].isEmpty()) end = Math.min(end, Long.parseLong(bounds[1]));
                if (start < 0 || start >= total || end < start) { writeHead(output, 416, "text/plain", 0, "Content-Range: bytes */" + total + "\r\n", null); return; }
                partial = true;
            } catch (Exception invalidRange) { start = 0; end = Math.max(0, total - 1); }
        }
        long length = total == 0 ? 0 : end - start + 1;
        String safeName = item.name.replace("\"", "_").replace("\r", "_").replace("\n", "_");
        String encodedName = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20");
        String extra = "Accept-Ranges: bytes\r\nContent-Disposition: inline; filename=\"" + safeName + "\"; filename*=UTF-8''" + encodedName + "\r\n";
        if (partial) extra += "Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n";
        writeHead(output, partial ? 206 : 200, item.mime, length, extra, null);
        output.flush();
        if (method.equals("HEAD") || length == 0) return;
        try (InputStream mediaStream = context.getContentResolver().openInputStream(item.uri)) {
            if (mediaStream == null) return;
            skipFully(mediaStream, start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = mediaStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
        output.flush();
    }

    private static void skipFully(InputStream input, long count) throws Exception {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) { if (input.read() < 0) break; skipped = 1; }
            remaining -= skipped;
        }
    }

    private static void respond(OutputStream output, int status, String contentType, String body, String extra) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        writeHead(output, status, contentType, data.length, extra, null);
        output.write(data);
        output.flush();
    }

    private static void writeHead(OutputStream output, int status, String type, long length, String extra, String unused) throws Exception {
        String reason = status == 200 ? "OK" : status == 206 ? "Partial Content" : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 409 ? "Conflict" : status == 416 ? "Range Not Satisfiable" : "Bad Request";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + length + "\r\n" +
                "Cache-Control: no-store\r\n" +
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
