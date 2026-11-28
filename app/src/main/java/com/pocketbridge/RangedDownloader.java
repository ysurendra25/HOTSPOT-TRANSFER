package com.pocketbridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads one file over several HTTP connections at once, and resumes.
 *
 * 1. Probe: GET with "Range: bytes=0-0". A 206 gives the size (Content-Range)
 *    and the validator (ETag). A 200 means the server does not do ranges: that
 *    same response is then read as a single stream.
 * 2. The .part file is preallocated to the full size; a {@link ResumeManifest}
 *    beside it records the ranges that are safely on disk (forced, then saved,
 *    every 64 MiB or 2 s). A manifest for the same size and ETag resumes; any
 *    other starts over.
 * 3. Workers (2..6, adapted to measured throughput by {@link TransferPlan.Concurrency})
 *    each take the next chunk (1..16 MiB, adapted per worker by how long its last
 *    chunk took) and GET it with Range and If-Range, writing with positional
 *    FileChannel writes. A 200 to a ranged If-Range request means the source
 *    changed: everything restarts from zero against the new file.
 */
final class RangedDownloader {
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int READ_TIMEOUT_MS = 40000;
    static final int RETRIES_PER_CHUNK = 3;
    static final int MAX_RESTARTS = 2;
    static final long FLUSH_BYTES = 64L * 1024 * 1024;
    static final long FLUSH_NANOS = 2_000_000_000L;

    /** Progress callback: bytes on disk so far, and the total (-1 unknown). */
    interface Progress { void update(long done, long total); }

    /** The source changed while it was downloaded (If-Range failed). */
    static final class SourceChanged extends IOException {
        SourceChanged() { super("The file changed on the sending side. Starting it again."); }
    }

    /** Outcome of a download, for callers and tests. */
    static final class Result {
        final File file;
        final long size;
        final boolean ranged;
        final boolean resumed;
        final int restarts;
        final long bytesFetched;
        Result(File file, long size, boolean ranged, boolean resumed, int restarts, long bytesFetched) {
            this.file = file; this.size = size; this.ranged = ranged; this.resumed = resumed;
            this.restarts = restarts; this.bytesFetched = bytesFetched;
        }
    }

    private final String url;
    private final File part;
    private final File manifestFile;
    private final long expectedSize;
    private final Progress progress;
    private int maxConnections = TransferPlan.MAX_CONNECTIONS;
    private final AtomicLong fetched = new AtomicLong();

    /**
     * @param part         stable partial file (the same name on every attempt, so it resumes)
     * @param expectedSize the size the listing promised, or -1; a different real size is refused
     */
    RangedDownloader(String url, File part, long expectedSize, Progress progress) {
        this.url = url;
        this.part = part;
        this.manifestFile = new File(part.getParentFile(), part.getName() + ".pbresume");
        this.expectedSize = expectedSize;
        this.progress = progress;
    }

    RangedDownloader maxConnections(int max) {
        this.maxConnections = Math.max(1, Math.min(TransferPlan.MAX_CONNECTIONS, max));
        return this;
    }

    File manifestFile() { return manifestFile; }

    /** Thrown when the size differs from the listing: the caller should reload its list. */
    static final class SizeChanged extends IOException {
        SizeChanged() { super("The file changed after it was listed. Reload laptop files before downloading."); }
    }

    Result download() throws IOException {
        int restarts = 0;
        while (true) {
            try {
                Result result = attempt(restarts);
                //noinspection ResultOfMethodCallIgnored
                manifestFile.delete();
                return result;
            } catch (SourceChanged changed) {
                discard();
                if (++restarts > MAX_RESTARTS) throw changed;
            }
        }
    }

    /** Deletes the partial file and its manifest. */
    void discard() {
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        //noinspection ResultOfMethodCallIgnored
        manifestFile.delete();
    }

    private HttpURLConnection open(String range, String ifRange) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) connection.setRequestProperty("Range", range);
        if (ifRange != null && !ifRange.isEmpty()) connection.setRequestProperty("If-Range", ifRange);
        return connection;
    }

    private static IOException httpFailure(HttpURLConnection connection, int status) {
        String detail = "";
        try (InputStream error = connection.getErrorStream()) {
            if (error != null) detail = HelperClient.readText(error, 4096).trim();
        } catch (IOException ignored) { }
        return new IOException(status == 404
                ? "The file moved or was deleted. Reload the list and try again."
                : "The other side could not send this file (HTTP " + status + "). " + detail);
    }

    private Result attempt(int restarts) throws IOException {
        HttpURLConnection probe = open("bytes=0-0", null);
        boolean probeOpen = true;
        try {
            int status = probe.getResponseCode();
            if (status == 416) {
                // Only an empty file cannot satisfy byte 0.
                long total = TransferPlan.totalFromContentRange(probe.getHeaderField("Content-Range"));
                if (total == 0 || total == -1) return emptyFile(restarts);
                throw httpFailure(probe, status);
            }
            if (status == HttpURLConnection.HTTP_OK) {
                long length = probe.getContentLengthLong();
                if (expectedSize >= 0 && length >= 0 && length != expectedSize) throw new SizeChanged();
                probeOpen = false;
                return singleStream(probe, length >= 0 ? length : expectedSize, restarts);
            }
            if (status != HttpURLConnection.HTTP_PARTIAL) throw httpFailure(probe, status);
            long total = TransferPlan.totalFromContentRange(probe.getHeaderField("Content-Range"));
            if (total < 0) throw new IOException("The other side did not say how big the file is.");
            if (expectedSize >= 0 && total != expectedSize) throw new SizeChanged();
            String etag = probe.getHeaderField("ETag");
            // If-Range needs a strong validator; without one, ranges still work but a
            // change can only be caught by size, and a resume is never trusted.
            String validator = etag != null && !etag.startsWith("W/") ? etag : "";
            try (InputStream drain = probe.getInputStream()) { while (drain.read() >= 0) { /* one byte */ } }
            return ranged(total, validator, restarts);
        } finally {
            if (probeOpen) probe.disconnect();
        }
    }

    private Result emptyFile(int restarts) throws IOException {
        if (expectedSize > 0) throw new SizeChanged();
        File parent = part.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the download folder.");
        try (RandomAccessFile file = new RandomAccessFile(part, "rw")) { file.setLength(0); }
        if (progress != null) progress.update(0, 0);
        return new Result(part, 0, false, false, restarts, 0);
    }

    /** No range support: one stream from byte 0 (nothing to resume). */
    private Result singleStream(HttpURLConnection connection, long total, int restarts) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        manifestFile.delete();
        File parent = part.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the download folder.");
        long copied = 0;
        ByteBuffer buffer = BufferPool.HEAP.acquire(30_000);
        try (InputStream input = connection.getInputStream();
             RandomAccessFile file = new RandomAccessFile(part, "rw")) {
            if (total > 0) file.setLength(total);
            FileChannel channel = file.getChannel();
            byte[] bytes = buffer.array();
            int read;
            while ((read = input.read(bytes)) != -1) {
                if (total >= 0 && copied + read > total) throw new IOException("The other side sent more bytes than the file size.");
                ByteBuffer slice = ByteBuffer.wrap(bytes, 0, read);
                long at = copied;
                while (slice.hasRemaining()) at += channel.write(slice, at);
                copied += read;
                fetched.addAndGet(read);
                if (progress != null) progress.update(copied, total);
            }
            if (total >= 0 && copied != total) throw new IOException("Download ended early (" + copied + " of " + total + " bytes). Try again.");
            file.setLength(copied);
            channel.force(false);
        } catch (IOException failure) {
            discard();
            throw failure;
        } finally {
            BufferPool.HEAP.release(buffer);
            connection.disconnect();
        }
        return new Result(part, copied, false, false, restarts, fetched.get());
    }

    // ------------------------------------------------------------------ ranged

    private final class Job {
        final long size;
        final String validator;
        final ResumeManifest manifest;
        final FileChannel channel;
        final Deque<long[]> gaps = new ArrayDeque<>();
        final AtomicReference<IOException> failure = new AtomicReference<>();
        final AtomicLong windowBytes = new AtomicLong();
        long unflushed;
        long lastFlush = System.nanoTime();
        int active;
        int target;
        int inFlight;

        Job(long size, String validator, ResumeManifest manifest, FileChannel channel) {
            this.size = size; this.validator = validator; this.manifest = manifest; this.channel = channel;
            gaps.addAll(manifest.missing());
        }

        /** Next range of at most {@code chunk} bytes; null when nothing is left to hand out. */
        synchronized long[] next(long chunk) {
            long[] gap = gaps.pollFirst();
            if (gap == null) return null;
            if (gap[1] - gap[0] > chunk) {
                gaps.addFirst(new long[]{gap[0] + chunk, gap[1]});
                gap = new long[]{gap[0], gap[0] + chunk};
            }
            inFlight++;
            return gap;
        }

        synchronized void giveBack(long start, long end) {
            if (end > start) gaps.addFirst(new long[]{start, end});
        }

        synchronized void chunkEnded() { inFlight--; notifyAll(); }

        synchronized boolean finished() { return gaps.isEmpty() && inFlight == 0; }

        /** Records written bytes; forces the data then saves the manifest every 64 MiB or 2 s. */
        synchronized void wrote(long start, long end, boolean force) throws IOException {
            if (end > start) {
                manifest.add(start, end);
                unflushed += end - start;
            }
            long now = System.nanoTime();
            if (!force && unflushed < FLUSH_BYTES && now - lastFlush < FLUSH_NANOS) return;
            String snapshot = manifest.encode();
            channel.force(false);
            ResumeManifest.writeText(manifestFile, snapshot);
            unflushed = 0;
            lastFlush = now;
        }

        void fail(IOException error) {
            failure.compareAndSet(null, error);
            synchronized (this) { notifyAll(); }
        }
    }

    private Result ranged(long total, String validator, int restarts) throws IOException {
        File parent = part.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the download folder.");
        ResumeManifest manifest = ResumeManifest.read(manifestFile);
        boolean resumed = manifest != null && manifest.matches(total, validator) && part.isFile() && part.length() == total;
        if (!resumed) {
            discard();
            manifest = new ResumeManifest(total, validator, url.replaceAll("[?].*$", ""));
        }
        try (RandomAccessFile file = new RandomAccessFile(part, "rw")) {
            if (!resumed) {
                file.setLength(total); // preallocate (sparse where the file system allows)
                if (!validator.isEmpty()) manifest.write(manifestFile);
            }
            Job job = new Job(total, validator, manifest, file.getChannel());
            if (progress != null) progress.update(manifest.completedBytes(), total);
            run(job);
            IOException failed = job.failure.get();
            if (failed != null) {
                try { job.wrote(0, 0, true); } catch (IOException ignored) { }
                if (validator.isEmpty() && !(failed instanceof SourceChanged)) discard();
                throw failed;
            }
            if (!manifest.complete()) throw new IOException("Download ended early. Try again to resume it.");
            file.getChannel().force(false);
        }
        return new Result(part, total, true, resumed, restarts, fetched.get());
    }

    private void run(Job job) throws IOException {
        int initial = Math.min(maxConnections, Math.max(1, TransferPlan.initialConnections(job.size - job.manifest.completedBytes())));
        // A small remainder stays on one connection; a big one moves between 2 and 6.
        int ceiling = initial == 1 ? 1 : maxConnections;
        TransferPlan.Concurrency concurrency = new TransferPlan.Concurrency(initial,
                Math.min(TransferPlan.MIN_CONNECTIONS, ceiling), ceiling);
        long chunk = TransferPlan.initialChunk(job.size, initial);
        List<Thread> threads = new ArrayList<>();
        synchronized (job) { job.target = concurrency.current(); }
        for (int i = 0; i < concurrency.current(); i++) threads.add(startWorker(job, chunk));
        long windowStart = System.nanoTime();
        while (true) {
            synchronized (job) {
                if (job.failure.get() != null || job.finished()) break;
                try { job.wait(250); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    job.fail(new java.io.InterruptedIOException("Download cancelled."));
                    break;
                }
            }
            long now = System.nanoTime();
            if (now - windowStart >= 1_000_000_000L) {
                double rate = job.windowBytes.getAndSet(0) / ((now - windowStart) / 1e9);
                windowStart = now;
                int wanted = concurrency.observe(rate);
                synchronized (job) {
                    job.target = wanted;
                    while (job.active < job.target && !job.gaps.isEmpty()) {
                        threads.add(startWorker(job, chunk));
                    }
                }
            }
        }
        for (Thread thread : threads) {
            try { thread.join(READ_TIMEOUT_MS + 5000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
    }

    private Thread startWorker(Job job, long firstChunk) {
        synchronized (job) { job.active++; }
        Thread thread = new Thread(() -> worker(job, firstChunk), "PocketBridge-range");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void worker(Job job, long firstChunk) {
        long chunk = firstChunk;
        boolean retired = false;
        try {
            while (job.failure.get() == null) {
                synchronized (job) {
                    // Fewer connections wanted: this worker leaves, decided under the lock
                    // so two workers never both leave for one step down.
                    if (job.active > job.target) { job.active--; retired = true; job.notifyAll(); return; }
                }
                long[] range = job.next(chunk);
                if (range == null) return;
                long started = System.nanoTime();
                try {
                    fetchWithRetries(job, range[0], range[1]);
                } catch (IOException error) {
                    job.fail(error);
                    return;
                } finally {
                    job.chunkEnded();
                }
                chunk = TransferPlan.adaptChunk(chunk, range[1] - range[0], System.nanoTime() - started);
            }
        } finally {
            if (!retired) synchronized (job) { job.active--; job.notifyAll(); }
        }
    }

    private void fetchWithRetries(Job job, long start, long end) throws IOException {
        long[] cursor = {start};
        IOException last = null;
        for (int attempt = 0; attempt <= RETRIES_PER_CHUNK && job.failure.get() == null; attempt++) {
            try {
                fetch(job, cursor, end);
                if (cursor[0] >= end) return;
            } catch (SourceChanged changed) {
                throw changed;
            } catch (IOException error) {
                last = error;
            }
            if (attempt < RETRIES_PER_CHUNK) {
                try { Thread.sleep(300L * (attempt + 1)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        job.giveBack(cursor[0], end);
        throw last != null ? last : new IOException("Download stopped.");
    }

    /** Fetches [cursor, end), moving cursor past every byte written, even when it then fails. */
    private void fetch(Job job, long[] cursor, long end) throws IOException {
        long start = cursor[0];
        HttpURLConnection connection = open(TransferPlan.rangeHeader(start, end), job.validator);
        long at = start;
        ByteBuffer buffer = BufferPool.HEAP.acquire(30_000);
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) throw new SourceChanged();
            if (status != HttpURLConnection.HTTP_PARTIAL) throw httpFailure(connection, status);
            String contentRange = connection.getHeaderField("Content-Range");
            long size = TransferPlan.totalFromContentRange(contentRange);
            if (TransferPlan.startFromContentRange(contentRange) != start || (size >= 0 && size != job.size)) {
                throw new SourceChanged();
            }
            String etag = connection.getHeaderField("ETag");
            if (!job.validator.isEmpty() && etag != null && !etag.equals(job.validator)) throw new SourceChanged();
            byte[] bytes = buffer.array();
            try (InputStream input = connection.getInputStream()) {
                while (at < end && job.failure.get() == null) {
                    int want = (int) Math.min(bytes.length, end - at);
                    int filled = 0;
                    while (filled < want) {
                        int read = input.read(bytes, filled, want - filled);
                        if (read < 0) break;
                        filled += read;
                    }
                    if (filled > 0) {
                        ByteBuffer slice = ByteBuffer.wrap(bytes, 0, filled);
                        long position = at;
                        while (slice.hasRemaining()) position += job.channel.write(slice, position);
                        job.wrote(at, at + filled, false);
                        at += filled;
                        cursor[0] = at;
                        fetched.addAndGet(filled);
                        job.windowBytes.addAndGet(filled);
                        if (progress != null) progress.update(job.manifest.completedBytes(), job.size);
                    }
                    if (filled < want) throw new IOException("The connection dropped at byte " + at + ".");
                }
            }
        } finally {
            BufferPool.HEAP.release(buffer);
            connection.disconnect();
        }
    }
}
