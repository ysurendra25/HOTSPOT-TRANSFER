package com.pocketbridge;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Files remain in the publishing Edge tab. Android asks for a bounded slice only when a reader needs it. */
final class EdgeFiles {
    static final int CHUNK = 1024 * 1024;
    private static final long LEASE_MS = 60000;
    private String owner = "";
    private long seen;
    private long revision;
    private final Map<String, JSONObject> files = new LinkedHashMap<>();
    private final Map<String, Slice> pending = new LinkedHashMap<>();

    static final class Failure extends IOException {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }

    private static final class Slice {
        final String id = UUID.randomUUID().toString();
        final String file;
        final long offset;
        final int size;
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        boolean dispatched;
        Slice(String file, long offset, int size) { this.file = file; this.offset = offset; this.size = size; }
    }

    synchronized void clear() {
        owner = "";
        files.clear();
        for (Slice s : pending.values()) s.result.completeExceptionally(new IOException("Sharing stopped"));
        pending.clear();
        revision++;
        notifyAll();
    }

    private void expire() {
        if (!owner.isEmpty() && SystemClock.elapsedRealtime() - seen > LEASE_MS) clear();
    }

    private void checkOwner(String key) throws Failure {
        expire();
        if (owner.isEmpty() || !owner.equals(key)) throw new Failure(410, "This file share has ended.");
        seen = SystemClock.elapsedRealtime();
    }

    synchronized void publish(String key, JSONArray items, boolean append) throws Exception {
        expire();
        if (!key.matches("[0-9a-f]{32}") || items == null || items.length() == 0 || items.length() > 2000)
            throw new Failure(400, "Choose between 1 and 2,000 files.");
        if (append) checkOwner(key);
        if (append && files.size() + items.length() > 50000) throw new Failure(400, "Choose a folder with at most 50,000 files.");
        if (!owner.isEmpty() && !owner.equals(key)) throw new Failure(409, "Another Edge tab is sharing files. Stop that share first.");
        Map<String, JSONObject> next = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject input = items.getJSONObject(i);
            String id = input.getString("id"), name = input.getString("name");
            long size = input.getLong("size");
            String path = input.optString("path", name);
            if (!id.matches("[0-9a-f]{32}") || next.containsKey(id) || (append && files.containsKey(id)) || path.length() > 4096 || name.isEmpty() || name.length() > 512 || size < 0
                    || name.indexOf('/') >= 0 || name.indexOf((char)92) >= 0 || name.chars().anyMatch(c -> c < 32 || c == 127))
                throw new Failure(400, "Invalid file description.");
            // Derive MIME locally; a peer cannot supply response headers.
            long modified = Math.max(0L, input.optLong("modified", 0L));
            next.put(id, new JSONObject().put("path", path).put("name", name).put("size", size).put("modified", modified)
                    .put("mime", MediaTypeClassifier.mimeType(name, null)).put("url", "/api/edge-media/" + id));
        }
        if (!append) clear();
        else revision++;
        owner = key;
        seen = SystemClock.elapsedRealtime();
        files.putAll(next);
    }

    synchronized long revision() { expire(); return revision; }
    synchronized List<JSONObject> snapshot() { expire(); return new ArrayList<>(files.values()); }
    synchronized JSONObject find(String id) { expire(); return files.get(id); }
    synchronized void stopOwner(String key) throws Failure { checkOwner(key); clear(); }

    synchronized JSONObject next(String key) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (true) {
            checkOwner(key);
            for (Slice s : pending.values()) {
                if (!s.dispatched) {
                    s.dispatched = true;
                    return new JSONObject().put("job", s.id).put("file", s.file).put("offset", s.offset).put("size", s.size);
                }
            }
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return new JSONObject();
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    synchronized void deliver(String key, String id, byte[] bytes) throws Failure {
        checkOwner(key);
        Slice s = pending.get(id);
        if (s == null || !s.dispatched) throw new Failure(410, "Read request expired.");
        if (bytes.length != s.size) {
            s.result.completeExceptionally(new IOException("Edge could not read the selected file."));
            throw new Failure(400, "File changed or could not be read.");
        }
        s.result.complete(bytes);
    }

    byte[] read(String file, long offset, int count) throws IOException {
        Slice slice = new Slice(file, offset, count);
        synchronized (this) {
            if (find(file) == null) throw new Failure(410, "Reopen the sharing tab in Edge.");
            if (pending.size() >= 8) throw new Failure(503, "Too many simultaneous file reads.");
            pending.put(slice.id, slice);
            notifyAll();
        }
        try { return slice.result.get(20, TimeUnit.SECONDS); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("Read interrupted", stopped); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new Failure(503, "Edge did not supply the file. Keep its sharing tab open.");
        } finally { synchronized (this) { pending.remove(slice.id); } }
    }
}
