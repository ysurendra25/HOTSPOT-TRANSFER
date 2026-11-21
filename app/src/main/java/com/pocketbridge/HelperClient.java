package com.pocketbridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the Windows helper over the hotspot. One place for timeouts,
 * retries, error messages and checking the pasted LAN link, so files, the screen viewer
 * and the power buttons behave the same way.
 *
 * Never call it on the main thread: every method may block on the network.
 */
final class HelperClient {
    static final int CONNECT_TIMEOUT_MS = 4000;
    static final int READ_TIMEOUT_MS = 15000;

    private HelperClient() { }

    static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
        boolean ok() { return status >= 200 && status < 300; }
        JSONObject json() {
            try { return new JSONObject(body); } catch (Exception notJson) { return new JSONObject(); }
        }
    }

    /** A failure with a sentence the phone can show as it is. */
    static final class HelperException extends Exception {
        final int status;
        HelperException(String message, int status) { super(message); this.status = status; }
    }

    /** One HTTP exchange, retried on network errors and on 502/503/504. */
    static Response request(String method, String url, byte[] body, int connectTimeoutMs, int readTimeoutMs, int attempts)
            throws IOException {
        return request(method, url, body, connectTimeoutMs, readTimeoutMs, attempts, 32 * 1024 * 1024);
    }

    private static Response request(String method, String url, byte[] body, int connectTimeoutMs, int readTimeoutMs,
                                    int attempts, int maxResponseBytes) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                // A JSON Accept keeps the helper from answering with its login page.
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Connection", "close");
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setFixedLengthStreamingMode(body.length);
                    try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = stream == null ? "" : readText(stream, maxResponseBytes);
                if ((status == 502 || status == 503 || status == 504) && attempt < attempts) {
                    sleepQuietly(400L * attempt);
                    continue;
                }
                return new Response(status, text);
            } catch (IOException error) {
                last = error;
                if (attempt < attempts) sleepQuietly(500L * attempt);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last == null ? new IOException("no response") : last;
    }

    static String readText(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > limit) throw new IOException("The laptop sent more data than expected.");
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    /** The helper's own words when it gave any, otherwise a plain explanation of the status. */
    static String errorMessage(int status, String body) {
        String text = body == null ? "" : body.trim();
        if (text.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(text);
                String message = json.optString("error", json.optString("message", ""));
                if (!message.isEmpty()) return message;
            } catch (Exception ignored) { }
        }
        if (status == 401 || status == 403) {
            return "The helper rejected this link's access code. Copy the current link from the Windows helper and paste it again.";
        }
        if (!text.isEmpty() && !text.startsWith("<") && text.length() <= 300) return text;
        if (status == 404) return "The helper does not know this request. Update the Windows helper to the version that matches this app.";
        return "The Windows helper answered HTTP " + status + ".";
    }

    static String networkMessage(IOException error) {
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (error instanceof java.net.SocketTimeoutException) {
            return "The laptop did not answer in time. Keep it awake and on the same hotspot, then try again.";
        }
        if (error instanceof java.net.ConnectException || detail.contains("ECONNREFUSED") || detail.contains("EHOSTUNREACH")
                || detail.contains("ENETUNREACH")) {
            return "Could not reach the laptop helper. Check that it is running, that both devices use the same hotspot, and that Windows Firewall allows PocketBridge on private networks.";
        }
        return "Network error talking to the laptop: " + detail;
    }

    interface StatusListener { void update(String message); }

    /** Verify only the LAN address the owner copied from the Windows helper. */
    static HelperLink resolve(String raw, StatusListener status) throws HelperException {
        HelperLink link;
        try {
            link = HelperLink.parse(raw);
            link.requireLanAddress();
        } catch (IllegalArgumentException invalid) {
            throw new HelperException(invalid.getMessage(), 0);
        }
        if (status != null) status.update("Checking the Windows helper at " + link.host + "…");
        verifyIdentity(link);
        return link;
    }

    static void verifyIdentity(HelperLink link) throws HelperException {
        Response reply;
        try {
            reply = request("GET", link.url("/ping"), null, 2500, 4000, 1, 4096);
        } catch (IOException unavailable) {
            throw new HelperException("Could not verify the Windows helper at " + link.host
                    + ". Keep the laptop on the hotspot, check its firewall and copy the LAN link for its Wi-Fi adapter.", 0);
        }
        if (reply.status == 401 || reply.status == 403) {
            throw new HelperException(errorMessage(reply.status, ""), reply.status);
        }
        JSONObject identity = reply.json();
        if (reply.status != 200 || !Boolean.TRUE.equals(identity.opt("ok"))
                || !"PocketBridge Windows Control".equals(identity.opt("app"))) {
            throw new HelperException("This address did not identify itself as PocketBridge Windows Control. "
                    + "Start the helper bundled with this app and paste its current LAN link.", reply.status);
        }
    }

    /** One page of the helper's shared-folder listing. */
    static final class FilesPage {
        final List<JSONObject> files = new ArrayList<>();
        int total;
        int nextOffset;
        boolean hasMore;
        boolean truncated;
        String root = "";
    }

    static FilesPage parseFilesPage(String json, int offset) throws HelperException {
        JSONObject data;
        try { data = new JSONObject(json); }
        catch (Exception notJson) {
            throw new HelperException("The laptop sent a file list this app could not read. Update the Windows helper to match this app.", 0);
        }
        FilesPage page = new FilesPage();
        JSONArray items = data.optJSONArray("files");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && !item.optString("url", "").isEmpty()) page.files.add(item);
            }
        }
        page.total = data.optInt("total", page.files.size());
        page.nextOffset = data.optInt("nextOffset", offset + page.files.size());
        // Older helpers return everything at once and no paging fields.
        page.hasMore = data.optBoolean("hasMore", false) && page.nextOffset > offset;
        page.truncated = data.optBoolean("truncated", false);
        page.root = data.optString("root", "");
        return page;
    }
}
