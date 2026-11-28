package com.pocketbridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resumable uploads into the phone. Each upload is a session with a stable id
 * derived from what the client knows about its file (name, size, lastModified,
 * relative path), so a client that lost its connection, reloaded the page or
 * restarted simply starts the same session again and is told which ranges are
 * still missing.
 *
 * Data goes into a preallocated sparse file in filesDir/uploads with positional
 * FileChannel writes, so several connections can fill different ranges of one
 * file at once. Progress is recorded in a {@link ResumeManifest} beside it, and
 * only after the data it covers was forced to disk (every 64 MiB or 2 s). A
 * finished upload is renamed into received/ (keeping its folder path).
 */
final class UploadSessions {
    static final long FLUSH_BYTES = 64L * 1024 * 1024;
    static final long FLUSH_NANOS = 2_000_000_000L;
    /** Sessions untouched for this long are deleted when the server starts. */
    static final long STALE_MS = 7L * 24 * 60 * 60 * 1000;

    /** An upload failure with the HTTP status that describes it. */
    static final class Failure extends IOException {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }

    /** Free space for a path; replaced in tests. */
    interface Space { long usable(File folder); }

    final File uploads;
    final File received;
    private final Space space;
    private final Map<String, Session> open = new ConcurrentHashMap<>();

    UploadSessions(File filesDir) { this(filesDir, File::getUsableSpace); }

    UploadSessions(File filesDir, Space space) {
        this.uploads = new File(filesDir, "uploads");
        this.received = new File(filesDir, "received");
        this.space = space;
    }

    static final class Session {
        final String id;
        final String name;
        /** Sanitized folder path ("Trip/day 1/a.jpg"), or null for a single file. */
        final String relPath;
        final long size;
        final long lastModified;
        final File data;
        final File manifestFile;
        final ResumeManifest manifest;
        final boolean resumed;
        private FileChannel channel;
        private final AtomicInteger writers = new AtomicInteger();
        private long unflushed;
        private long lastFlush = System.nanoTime();
        private boolean closed;

        Session(String id, String name, String relPath, long size, long lastModified, File data, File manifestFile,
                ResumeManifest manifest, boolean resumed) {
            this.id = id; this.name = name; this.relPath = relPath; this.size = size; this.lastModified = lastModified;
            this.data = data; this.manifestFile = manifestFile; this.manifest = manifest; this.resumed = resumed;
        }

        synchronized FileChannel channel() throws IOException {
            if (closed) throw new Failure(410, "This upload was cancelled or already finished.");
            if (channel == null || !channel.isOpen()) {
                @SuppressWarnings("resource") RandomAccessFile file = new RandomAccessFile(data, "rw");
                channel = file.getChannel();
            }
            return channel;
        }

        /** Counts written bytes; forces data then saves the manifest every 64 MiB or 2 s. */
        synchronized void wrote(long bytes, boolean force) throws IOException {
            unflushed += bytes;
            long now = System.nanoTime();
            if (!force && unflushed < FLUSH_BYTES && now - lastFlush < FLUSH_NANOS) return;
            // The snapshot only holds ranges whose writes have returned, and force()
            // runs after it, so the saved manifest never claims unforced data.
            String snapshot = manifest.encode();
            if (channel != null && channel.isOpen()) channel.force(false);
            ResumeManifest.writeText(manifestFile, snapshot);
            unflushed = 0;
            lastFlush = now;
        }

        synchronized void close() {
            closed = true;
            try { if (channel != null) channel.close(); } catch (IOException ignored) { }
        }

        String destination() { return relPath != null ? relPath : name; }
    }

    /** The client key: what identifies one file on the sending side. */
    static String clientKey(String name, long size, long lastModified, String relPath) {
        return name + "\n" + size + "\n" + lastModified + "\n" + (relPath == null ? "" : relPath);
    }

    /** Stable session id: the first 32 hex digits of SHA-256 of the client key. */
    static String sessionId(String clientKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(clientKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", hash[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{32}"); }

    static String safeName(String raw) {
        String value = raw == null ? "" : raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return value.isEmpty() || value.equals(".") || value.equals("..") ? "shared-file" : value;
    }

    /**
     * Starts or resumes the upload of one file. A matching earlier session (same
     * id, same size and lastModified, data file still there) is resumed;
     * anything else starts from zero. Free space is checked once, here.
     */
    synchronized Session start(String rawName, long size, long lastModified, String rawRelPath) throws IOException {
        if (size < 0) throw new Failure(400, "Upload must include the file size.");
        String name = safeName(rawName);
        String relPath = rawRelPath == null || rawRelPath.trim().isEmpty() ? null : RelPath.sanitize(rawRelPath);
        if (rawRelPath != null && !rawRelPath.trim().isEmpty() && relPath == null) throw new Failure(400, "Invalid folder path.");
        String id = sessionId(clientKey(name, size, lastModified, relPath));
        Session existing = open.get(id);
        if (existing != null) return existing;
        if (!uploads.isDirectory() && !uploads.mkdirs()) throw new Failure(500, "Could not create the upload folder.");
        File data = new File(uploads, id + ".data");
        File manifestFile = new File(uploads, id + ".pbresume");
        String validator = ResumeManifest.sizeMtime(size, lastModified);
        ResumeManifest manifest = ResumeManifest.read(manifestFile);
        boolean resumed = manifest != null && manifest.matches(size, validator) && data.isFile() && data.length() == size;
        if (!resumed) {
            //noinspection ResultOfMethodCallIgnored
            data.delete();
            manifest = new ResumeManifest(size, validator, (relPath != null ? "rel:" + relPath : "name:" + name));
        }
        long needed = size - manifest.completedBytes();
        if (needed > 0 && space.usable(uploads) < needed) {
            throw new Failure(507, "The phone needs more free storage for this file.");
        }
        if (!resumed) {
            // Sparse preallocation: the size is reserved in the file system's view
            // without writing zeros, and positional writes can land anywhere.
            try (RandomAccessFile file = new RandomAccessFile(data, "rw")) { file.setLength(size); }
            manifest.write(manifestFile);
        }
        Session session = new Session(id, name, relPath, size, lastModified, data, manifestFile, manifest, resumed);
        open.put(id, session);
        return session;
    }

    /** An open session, reloaded from its manifest after a restart; null when unknown. */
    Session find(String id) {
        if (!validId(id)) return null;
        Session session = open.get(id);
        if (session != null) return session;
        synchronized (this) {
            session = open.get(id);
            if (session != null) return session;
            File data = new File(uploads, id + ".data");
            File manifestFile = new File(uploads, id + ".pbresume");
            ResumeManifest manifest = ResumeManifest.read(manifestFile);
            if (manifest == null || !data.isFile() || data.length() != manifest.size) return null;
            String source = manifest.source;
            String name = null, relPath = null;
            if (source.startsWith("rel:")) { relPath = RelPath.sanitize(source.substring(4)); }
            else if (source.startsWith("name:")) { name = safeName(source.substring(5)); }
            if (relPath == null && name == null) return null;
            if (name == null) name = safeName(relPath.substring(relPath.lastIndexOf('/') + 1));
            long lastModified = 0;
            int colon = manifest.validator.indexOf(':');
            if (colon > 0) try { lastModified = Long.parseLong(manifest.validator.substring(colon + 1)); } catch (NumberFormatException ignored) { }
            session = new Session(id, name, relPath, manifest.size, lastModified, data, manifestFile, manifest, true);
            open.put(id, session);
            return session;
        }
    }

    Session require(String id) throws Failure {
        Session session = find(id);
        if (session == null) throw new Failure(404, "Unknown upload. Start it again.");
        return session;
    }

    /**
     * Writes {@code length} bytes from {@code body} at {@code offset}. Bytes that
     * arrived before a dropped connection stay recorded, so the client resumes
     * from the first missing byte. Returns the bytes now complete.
     */
    long write(String id, long offset, long length, InputStream body, BufferPool pool) throws IOException {
        Session session = require(id);
        if (offset < 0 || length < 0 || offset + length > session.size) {
            throw new Failure(416, "Range " + offset + "+" + length + " is outside the file (" + session.size + " bytes).");
        }
        session.writers.incrementAndGet();
        ByteBuffer buffer = pool.acquire(30_000);
        try {
            FileChannel channel = session.channel();
            byte[] scratch = buffer.hasArray() ? buffer.array() : new byte[Math.min(256 * 1024, buffer.capacity())];
            long position = offset;
            long left = length;
            while (left > 0) {
                buffer.clear();
                int want = (int) Math.min(left, buffer.capacity());
                int filled = 0;
                IOException dropped = null;
                // Fill the buffer (or reach the end of the range) before one positional write.
                while (filled < want) {
                    int read;
                    try {
                        read = body.read(scratch, buffer.hasArray() ? filled : 0,
                                buffer.hasArray() ? want - filled : Math.min(scratch.length, want - filled));
                    } catch (IOException connectionLost) {
                        // Keep what already arrived: it is written and recorded below.
                        dropped = connectionLost;
                        break;
                    }
                    if (read < 0) break;
                    if (!buffer.hasArray()) buffer.put(scratch, 0, read);
                    filled += read;
                }
                if (filled > 0) {
                    if (buffer.hasArray()) buffer.position(0).limit(filled); else buffer.flip();
                    long at = position;
                    while (buffer.hasRemaining()) at += channel.write(buffer, at);
                    session.manifest.add(position, position + filled);
                    session.wrote(filled, false);
                    position += filled;
                    left -= filled;
                }
                if (dropped != null) {
                    session.wrote(0, true);
                    throw dropped;
                }
                if (filled < want) {
                    session.wrote(0, true);
                    throw new Failure(400, "The upload ended early at byte " + position + ". Resume from there.");
                }
            }
            return session.manifest.completedBytes();
        } catch (IOException failure) {
            if (failure instanceof Failure) throw failure;
            try { session.wrote(0, true); } catch (IOException ignored) { }
            throw failure;
        } finally {
            pool.release(buffer);
            session.writers.decrementAndGet();
        }
    }

    /** Result of a finished upload: where it landed and its inbox id (path under received/). */
    static final class Finished {
        final File file;
        final String inboxId;
        final String name;
        final long size;
        Finished(File file, String inboxId, String name, long size) {
            this.file = file; this.inboxId = inboxId; this.name = name; this.size = size;
        }
    }

    /** Moves a complete upload into received/, keeping its folders; a clash gets " (2)". */
    Finished finish(String id) throws IOException {
        Session session = require(id);
        synchronized (session) {
            if (session.writers.get() > 0) throw new Failure(409, "Parts of this upload are still being written.");
            if (!session.manifest.complete()) throw new Failure(409, "The upload is missing " + (session.size - session.manifest.completedBytes()) + " bytes.");
            session.wrote(0, true);
            session.close();
        }
        if (!received.isDirectory() && !received.mkdirs()) throw new Failure(500, "Could not create the receive folder.");
        File target;
        if (session.relPath != null) target = RelPath.unique(RelPath.resolve(received, session.relPath));
        else target = RelPath.unique(new File(received, "edge-" + session.id + "-" + session.name));
        if (!session.data.renameTo(target)) throw new Failure(500, "Could not save the uploaded file. Try a shorter filename.");
        if (session.lastModified > 0) //noinspection ResultOfMethodCallIgnored
            target.setLastModified(session.lastModified);
        //noinspection ResultOfMethodCallIgnored
        session.manifestFile.delete();
        open.remove(id);
        String inboxId = received.getCanonicalFile().toPath().relativize(target.getCanonicalFile().toPath()).toString()
                .replace(File.separatorChar, '/');
        String name = session.relPath != null ? inboxId : session.name;
        return new Finished(target, inboxId, name, target.length());
    }

    /** Drops an upload and its partial data. */
    boolean cancel(String id) {
        Session session = find(id);
        if (session == null) return false;
        session.close();
        open.remove(id);
        //noinspection ResultOfMethodCallIgnored
        session.data.delete();
        //noinspection ResultOfMethodCallIgnored
        session.manifestFile.delete();
        return true;
    }

    /** Saves the progress of every open session (on stop) and closes their files. */
    void closeAll() {
        for (Session session : open.values()) {
            try { session.wrote(0, true); } catch (IOException ignored) { }
            session.close();
        }
        open.clear();
    }

    /** Deletes sessions nobody resumed for {@link #STALE_MS}. */
    int prune(long nowMillis) {
        File[] files = uploads.listFiles();
        if (files == null) return 0;
        int removed = 0;
        for (File file : files) {
            String fileName = file.getName();
            int dot = fileName.indexOf('.');
            String id = dot > 0 ? fileName.substring(0, dot) : fileName;
            if (open.containsKey(id)) continue;
            if (nowMillis - file.lastModified() > STALE_MS && file.delete()) removed++;
        }
        return removed;
    }

    /** JSON-ready list of [start, end) pairs. */
    static org.json.JSONArray rangesJson(List<long[]> ranges) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (long[] range : ranges) array.put(new org.json.JSONArray().put(range[0]).put(range[1]));
        return array;
    }
}
