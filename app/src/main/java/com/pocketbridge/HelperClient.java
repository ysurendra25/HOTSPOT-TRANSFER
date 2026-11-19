package com.pocketbridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Talks to the Windows helper over the hotspot. One place for timeouts,
 * retries, error messages and finding the laptop, so files, the screen viewer
 * and the power buttons behave the same way.
 *
 * Never call it on the main thread: every method may block on the network.
 */
final class HelperClient {
    static final int CONNECT_TIMEOUT_MS = 4000;
    static final int READ_TIMEOUT_MS = 15000;

    private static volatile String cachedRaw;
    private static volatile HelperLink cachedResolved;

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
        IOException last = null;
        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
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
                String text = stream == null ? "" : readText(stream, 32 * 1024 * 1024);
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

    /**
     * Turns the pasted link into one that answers from this phone. A loopback
     * link (127.0.0.1) or an address on another adapter is replaced by the
     * laptop's address on this hotspot, found by asking each neighbour for the
     * same access code.
     */
    static HelperLink resolve(String raw, StatusListener status) throws HelperException {
        HelperLink link;
        try { link = HelperLink.parse(raw); }
        catch (IllegalArgumentException invalid) { throw new HelperException(invalid.getMessage(), 0); }
        String key = raw == null ? "" : raw.trim();
        HelperLink cached = cachedResolved;
        if (cached != null && key.equals(cachedRaw) && answers(cached, 1500, 2500) == 200) return cached;

        // Try the link as given first. On the phone a loopback link fails at
        // once (nothing listens on the phone's own port 7070), so this costs
        // nothing, and a link that works is never second-guessed.
        int code = answers(link, link.isLoopback() ? 800 : 2500, 4000);
        if (code == 200 || code == 404) { remember(key, link); return link; }
        if (code == 401 || code == 403) {
            throw new HelperException("The helper rejected this link's access code. Copy the current link from the Windows helper and paste it again.", code);
        }
        if (status != null) {
            status.update(link.isLoopback()
                    ? "That is the laptop's own address. Looking for the laptop on this hotspot…"
                    : "The laptop did not answer at " + link.host + ". Looking for it on this hotspot…");
        }
        String found = discover(link.token, link.port, localPrefixes());
        if (found == null) {
            throw new HelperException(link.isLoopback()
                    ? "That is the laptop's own address (127.0.0.1), so the phone cannot use it, and the laptop was not found on this hotspot. Copy a LAN link from the helper window instead, for example http://192.168.43.42:7070/?t=…"
                    : "Could not reach the laptop helper at " + link.host + " or anywhere on this hotspot. Check that the helper is running, that the laptop joined this phone's hotspot, and that Windows Firewall allows port 7070 on private networks.", 0);
        }
        HelperLink resolved = link.withHost(found);
        remember(key, resolved);
        return resolved;
    }

    private static void remember(String raw, HelperLink link) {
        cachedRaw = raw;
        cachedResolved = link;
    }

    static void forget() {
        cachedRaw = null;
        cachedResolved = null;
    }

    /** HTTP status of a cheap request, or -1 when the laptop could not be reached. */
    static int answers(HelperLink link, int connectMs, int readMs) {
        try {
            int code = request("GET", link.url("/ping"), null, connectMs, readMs, 1).status;
            // Helpers older than 0.6.0.3 have no /ping; /screen is just as cheap.
            if (code == 404) code = request("GET", link.url("/screen"), null, connectMs, readMs, 1).status;
            return code;
        } catch (IOException unreachable) {
            return -1;
        }
    }

    interface StatusListener { void update(String message); }

    /** "192.168.43." for every IPv4 interface of this phone, hotspot first. */
    static List<String> localPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> list = interfaces == null ? new ArrayList<>() : Collections.list(interfaces);
            for (NetworkInterface network : list) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName() == null ? "" : network.getName();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (!(ip instanceof Inet4Address)) continue;
                    String text = ip.getHostAddress();
                    prefixes.add(text.substring(0, text.lastIndexOf('.') + 1));
                }
            }
        } catch (Exception ignored) { }
        return new ArrayList<>(prefixes);
    }

    /** Scans each /24 for a helper that accepts this token. Returns the host or null. */
    static String discover(String token, int port, List<String> prefixes) {
        if (prefixes.isEmpty()) return null;
        AtomicReference<String> found = new AtomicReference<>(null);
        ExecutorService pool = Executors.newFixedThreadPool(32, runnable -> {
            Thread thread = new Thread(runnable, "PocketBridge-helper-scan");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (String prefix : prefixes) {
                for (int host = 1; host <= 254; host++) {
                    final String candidate = prefix + host;
                    pool.execute(() -> {
                        if (found.get() != null) return;
                        HttpURLConnection probe = null;
                        try {
                            String url = "http://" + candidate + ":" + port + "/ping?t=" + HelperLink.encode(token);
                            probe = (HttpURLConnection) new URL(url).openConnection();
                            probe.setConnectTimeout(350);
                            probe.setReadTimeout(900);
                            probe.setRequestProperty("Accept", "application/json");
                            int code = probe.getResponseCode();
                            // 404 is an older helper without /ping that still accepted the token.
                            if (code == 200 || code == 404) found.compareAndSet(null, candidate);
                        } catch (Exception unreachable) {
                            // Not this host.
                        } finally {
                            if (probe != null) probe.disconnect();
                        }
                    });
                }
            }
            pool.shutdown();
            long deadline = System.currentTimeMillis() + 9000;
            while (found.get() == null && System.currentTimeMillis() < deadline && !pool.isTerminated()) {
                pool.awaitTermination(100, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return found.get();
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
