package com.pocketbridge;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded pool of equally sized ByteBuffers. Buffers are made on first use
 * up to {@code capacity}; after that a caller waits for one to come back, so
 * memory stays bounded however many transfers run (capacity x size), and the
 * hot path never allocates per chunk.
 *
 * Direct buffers are used where the data moves channel to channel (socket
 * channel to FileChannel on the phone's upload receiver), which avoids the
 * JDK's hidden copy into a temporary direct buffer. Heap buffers are used where
 * one side is an InputStream (HttpURLConnection), since a stream can only read
 * into a byte[].
 */
final class BufferPool {
    static final int DEFAULT_SIZE = 1024 * 1024;

    /** Phone-wide pool for the upload receiver: at most 12 MiB of direct memory. */
    static final BufferPool DIRECT = new BufferPool(DEFAULT_SIZE, 12, true);
    /** Phone-wide pool for stream readers (the download client): at most 12 MiB. */
    static final BufferPool HEAP = new BufferPool(DEFAULT_SIZE, 12, false);

    private final int size;
    private final int capacity;
    private final boolean direct;
    private final ArrayBlockingQueue<ByteBuffer> free;
    private final AtomicInteger created = new AtomicInteger();

    BufferPool(int size, int capacity, boolean direct) {
        if (size <= 0 || capacity <= 0) throw new IllegalArgumentException("size and capacity must be positive");
        this.size = size;
        this.capacity = capacity;
        this.direct = direct;
        this.free = new ArrayBlockingQueue<>(capacity);
    }

    int bufferSize() { return size; }
    int capacity() { return capacity; }
    /** Buffers made so far (never more than capacity). */
    int created() { return created.get(); }
    int idle() { return free.size(); }

    /** A cleared buffer; waits up to {@code timeoutMs} when every buffer is in use. */
    ByteBuffer acquire(long timeoutMs) throws java.io.IOException {
        ByteBuffer buffer = free.poll();
        if (buffer != null) { buffer.clear(); return buffer; }
        while (true) {
            int made = created.get();
            if (made >= capacity) break;
            if (created.compareAndSet(made, made + 1)) {
                return direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
            }
        }
        try {
            buffer = free.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Interrupted while waiting for a transfer buffer.");
        }
        if (buffer == null) throw new java.io.IOException("Too many transfers at once. Try again in a moment.");
        buffer.clear();
        return buffer;
    }

    void release(ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() != size || buffer.isDirect() != direct) return;
        buffer.clear();
        //noinspection ResultOfMethodCallIgnored
        free.offer(buffer);
    }
}
