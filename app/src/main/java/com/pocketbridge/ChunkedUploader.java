package com.pocketbridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends files with the resumable upload protocol (phone to laptop helper, and
 * phone to another phone's PocketBridge server):
 * <pre>
 * POST /api/upload/start {name, size, lastModified, relPath} -> {id, missing:[[a,b],...], chunk, connections}
 * PUT  /api/upload/{id}?offset=a   (one chunk per request, several in parallel)
 * POST /api/upload/{id}/finish
 * POST /api/upload/batch           (PBB1, many small files in one body)
 * </pre>
 * Starting the same file again returns the same id and only the missing ranges,
 * so an interrupted upload continues where it stopped.
 */
final class ChunkedUploader {
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int READ_TIMEOUT_MS = 40000;
    static final int RETRIES_PER_CHUNK = 3;

    interface Progress { void update(long done, long total); }

    /** What the receiver reported for a finished file. */
    static final class Sent {
        final String id;
        final String name;
        final long bytes;
        final long bytesSent;
        Sent(String id, String name, long bytes, long bytesSent) {
            this.id = id; this.name = name; this.bytes = bytes; this.bytesSent = bytesSent;
        }
    }

    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status, String message) { super(message); this.status = status; }
    }

    private final String base;
    private final String authQuery;
    private final String bearer;
    private int maxConnections = TransferPlan.MAX_CONNECTIONS;

    /**
     * @param baseUrl "http://host:port" of the receiver
     * @param code    its six-digit access code (sent as ?code=), or null
     * @param token   its long token (sent as a Bearer header), or null
     */
    ChunkedUploader(String baseUrl, String code, String token) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authQuery = code == null || code.isEmpty() ? "" : "code=" + encode(code);
        this.bearer = token == null || token.isEmpty() ? null : "Bearer " + token;
    }

    ChunkedUploader maxConnections(int max) {
        this.maxConnections = Math.max(1, Math.min(TransferPlan.MAX_CONNECTIONS, max));
        return this;
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    private URL url(String path, String query) throws IOException {
        String q = query == null || query.isEmpty() ? authQuery : (authQuery.isEmpty() ? query : query + "&" + authQuery);
        return new URL(base + path + (q.isEmpty() ? "" : "?" + q));
    }

    private HttpURLConnection open(String method, String path, String query) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url(path, query).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        if (bearer != null) connection.setRequestProperty("Authorization", bearer);
        return connection;
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
        return bytes.toString("UTF-8");
    }

    private static JSONObject reply(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        String text;
        try (InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            text = readAll(body);
        }
        if (status >= 400) {
            String message = "HTTP " + status;
            try { message = new JSONObject(text).optString("error", message); } catch (JSONException notJson) { /* keep */ }
            throw new HttpFailure(status, message);
        }
        try { return text.isEmpty() ? new JSONObject() : new JSONObject(text); }
        catch (JSONException invalid) { throw new IOException("The receiver sent an unreadable reply."); }
    }

    private JSONObject json(String method, String path, JSONObject body) throws IOException {
        HttpURLConnection connection = open(method, path, null);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            return reply(connection);
        } finally {
            connection.disconnect();
        }
    }

    /** Sends one file (optionally with its folder path) and returns where it landed. */
    Sent upload(File file, String relPath, Progress progress) throws IOException {
        long size = file.length();
        JSONObject start;
        try {
            start = json("POST", "/api/upload/start", new JSONObject().put("name", file.getName()).put("size", size)
                    .put("lastModified", file.lastModified()).put("relPath", relPath == null ? "" : relPath));
        } catch (JSONException impossible) { throw new IOException(impossible); }
        String id = start.optString("id");
        if (!UploadSessions.validId(id)) throw new IOException("The receiver did not start the upload.");
        List<long[]> missing = ranges(start.optJSONArray("missing"));
        long chunk = TransferPlan.clampChunk(start.optLong("chunk", TransferPlan.MIN_CHUNK));
        int connections = Math.max(1, Math.min(maxConnections, start.optInt("connections", 1)));
        long already = size - total(missing);
        AtomicLong sent = new AtomicLong();
        if (!missing.isEmpty()) sendRanges(file, id, size, missing, chunk, connections, already, sent, progress);
        else if (progress != null) progress.update(size, size);
        JSONObject done = json("POST", "/api/upload/" + id + "/finish", null);
        return new Sent(done.optString("id"), done.optString("name"), done.optLong("bytes", -1), sent.get());
    }

    /** The receiver's view of an upload (for a caller that wants to show resume state). */
    JSONObject status(String id) throws IOException { return json("GET", "/api/upload/" + id, null); }

    void cancel(String id) throws IOException { json("DELETE", "/api/upload/" + id, null); }

    private static long total(List<long[]> ranges) {
        long sum = 0;
        for (long[] range : ranges) sum += range[1] - range[0];
        return sum;
    }

    private static List<long[]> ranges(JSONArray array) {
        List<long[]> list = new ArrayList<>();
        if (array == null) return list;
        for (int i = 0; i < array.length(); i++) {
            JSONArray pair = array.optJSONArray(i);
            if (pair != null && pair.length() == 2) list.add(new long[]{pair.optLong(0), pair.optLong(1)});
        }
        return list;
    }

    private void sendRanges(File file, String id, long size, List<long[]> missing, long firstChunk, int connections,
                            long already, AtomicLong sent, Progress progress) throws IOException {
        Deque<long[]> queue = new ArrayDeque<>();
        for (long[] range : missing) queue.addAll(TransferPlan.split(range[0], range[1], firstChunk));
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicLong done = new AtomicLong(already);
        List<Thread> workers = new ArrayList<>();
        try (FileInputStream input = new FileInputStream(file)) {
            FileChannel channel = input.getChannel();
            for (int i = 0; i < connections; i++) {
                Thread worker = new Thread(() -> {
                    ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
                    while (failure.get() == null) {
                        long[] range;
                        synchronized (queue) { range = queue.pollFirst(); }
                        if (range == null) return;
                        try {
                            putWithRetries(channel, id, range[0], range[1], buffer, sent, done, size, progress);
                        } catch (IOException error) {
                            failure.compareAndSet(null, error);
                            return;
                        }
                    }
                }, "PocketBridge-upload");
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            }
            for (Thread worker : workers) {
                try { worker.join(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, new java.io.InterruptedIOException("Upload cancelled."));
                }
            }
        }
        if (failure.get() != null) throw failure.get();
    }

    private void putWithRetries(FileChannel channel, String id, long start, long end, ByteBuffer buffer,
                                AtomicLong sent, AtomicLong done, long size, Progress progress) throws IOException {
        IOException last = null;
        long at = start;
        for (int attempt = 0; attempt <= RETRIES_PER_CHUNK; attempt++) {
            try {
                put(channel, id, at, end, buffer, sent, done, size, progress);
                return;
            } catch (HttpFailure refused) {
                if (refused.status != 400 && refused.status < 500) throw refused;
                last = refused;
            } catch (IOException error) {
                last = error;
            }
            // Ask the receiver what actually arrived, then send only the rest of this range.
            try {
                List<long[]> missing = ranges(status(id).optJSONArray("missing"));
                at = firstMissingWithin(missing, start, end);
                if (at >= end) return;
            } catch (IOException unreachable) { /* resend the whole range */ }
            try { Thread.sleep(300L * (attempt + 1)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        throw last != null ? last : new IOException("Upload stopped.");
    }

    /** First byte in [start, end) the receiver still misses; end when none. */
    static long firstMissingWithin(List<long[]> missing, long start, long end) {
        for (long[] gap : missing) {
            if (gap[1] <= start || gap[0] >= end) continue;
            return Math.max(gap[0], start);
        }
        return end;
    }

    private void put(FileChannel channel, String id, long start, long end, ByteBuffer buffer,
                     AtomicLong sent, AtomicLong done, long size, Progress progress) throws IOException {
        long length = end - start;
        HttpURLConnection connection = open("PUT", "/api/upload/" + id, "offset=" + start);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setFixedLengthStreamingMode(length);
            try (OutputStream output = connection.getOutputStream()) {
                long position = start;
                while (position < end) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), end - position));
                    int read = channel.read(buffer, position);
                    if (read < 0) throw new IOException("The file got shorter while it was being sent.");
                    output.write(buffer.array(), 0, read);
                    position += read;
                    sent.addAndGet(read);
                    if (progress != null) progress.update(Math.min(size, done.get() + (position - start)), size);
                }
            }
            reply(connection);
            long now = done.addAndGet(length);
            if (progress != null) progress.update(Math.min(size, now), size);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Sends many small files in one PBB1 body. {@code paths} are the names the
     * receiver keeps, folders included ("Trip/day 1/a.jpg").
     */
    JSONObject uploadBatch(List<File> files, List<String> paths) throws IOException {
        if (files.size() != paths.size()) throw new IllegalArgumentException("one path per file");
        long length = BatchStream.length(paths, files);
        HttpURLConnection connection = open("POST", "/api/upload/batch", null);
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-pocketbridge-batch");
            connection.setFixedLengthStreamingMode(length);
            try (OutputStream output = connection.getOutputStream()) {
                BatchStream.write(output, paths, files);
            }
            return reply(connection);
        } finally {
            connection.disconnect();
        }
    }
}
