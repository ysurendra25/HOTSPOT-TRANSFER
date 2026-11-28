package com.pocketbridge;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Many small files in one request body, so 5000 photos cost one connection and
 * one request instead of 5000. The framing is deliberately trivial, so the Edge
 * page builds it from a Blob of [header, file, header, file, ...] without
 * reading any file into memory, and the Go helper parses the same thing:
 * <pre>
 * "PBB1"
 * repeat: u16 pathLength | path (UTF-8, "folder/sub/name.ext") | u64 size | size bytes
 * u16 0                     end of batch
 * </pre>
 * All integers are big-endian.
 */
final class BatchStream {
    static final byte[] MAGIC = {'P', 'B', 'B', '1'};
    static final int MAX_PATH_BYTES = 4096;
    static final int MAX_ENTRIES = 100_000;

    private BatchStream() { }

    /** One received file. */
    static final class Entry {
        final String path;
        final File file;
        final long size;
        Entry(String path, File file, long size) { this.path = path; this.file = file; this.size = size; }
    }

    /** The header bytes that precede one file's data. */
    static byte[] header(String path, long size) {
        byte[] name = path.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > MAX_PATH_BYTES) throw new IllegalArgumentException("bad path length");
        ByteBuffer header = ByteBuffer.allocate(2 + name.length + 8);
        header.putShort((short) name.length).put(name).putLong(size);
        return header.array();
    }

    /** Writes a whole batch; used by the JVM client and the benchmark. */
    static long write(OutputStream output, List<String> paths, List<File> files) throws IOException {
        long written = MAGIC.length;
        output.write(MAGIC);
        byte[] buffer = new byte[256 * 1024];
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            long size = file.length();
            byte[] head = header(paths.get(i), size);
            output.write(head);
            written += head.length;
            try (InputStream input = new FileInputStream(file)) {
                long left = size;
                while (left > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (read < 0) throw new IOException(file.getName() + " got shorter while it was being sent.");
                    output.write(buffer, 0, read);
                    left -= read;
                }
            }
            written += size;
        }
        output.write(new byte[]{0, 0});
        output.flush();
        return written + 2;
    }

    /** Exact body length for {@link #write}. */
    static long length(List<String> paths, List<File> files) {
        long total = MAGIC.length + 2;
        for (int i = 0; i < files.size(); i++) {
            total += 2 + paths.get(i).getBytes(StandardCharsets.UTF_8).length + 8 + files.get(i).length();
        }
        return total;
    }

    /**
     * Reads a batch of at most {@code limit} bytes and writes each file under
     * {@code root}, keeping its relative folders. A name that already exists
     * gets " (2)". On any error the files written by this batch are removed.
     */
    static List<Entry> read(InputStream input, long limit, File root, BufferPool pool) throws IOException {
        DataInputStream data = new DataInputStream(new LimitedInput(input, limit));
        byte[] magic = new byte[4];
        data.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("Not a PocketBridge batch.");
        List<Entry> received = new ArrayList<>();
        ByteBuffer buffer = pool.acquire(30_000);
        try {
            // A direct buffer has no array; stream reads need one, so fall back to a small heap array.
            byte[] bytes = buffer.hasArray() ? buffer.array() : new byte[256 * 1024];
            while (true) {
                int nameLength = data.readUnsignedShort();
                if (nameLength == 0) break;
                if (nameLength > MAX_PATH_BYTES || received.size() >= MAX_ENTRIES) throw new IOException("Batch entry is too large.");
                byte[] name = new byte[nameLength];
                data.readFully(name);
                long size = data.readLong();
                if (size < 0) throw new IOException("Negative file size in batch.");
                String path = RelPath.sanitize(new String(name, StandardCharsets.UTF_8));
                if (path == null) throw new IOException("Invalid file path in batch.");
                File target = RelPath.unique(RelPath.resolve(root, path));
                received.add(new Entry(path, target, size));
                try (FileOutputStream output = new FileOutputStream(target)) {
                    long left = size;
                    while (left > 0) {
                        int read = data.read(bytes, 0, (int) Math.min(bytes.length, left));
                        if (read < 0) throw new EOFException("The batch ended early.");
                        output.write(bytes, 0, read);
                        left -= read;
                    }
                }
            }
            return received;
        } catch (IOException failure) {
            for (Entry entry : received) {
                //noinspection ResultOfMethodCallIgnored
                entry.file.delete();
            }
            throw failure;
        } finally {
            pool.release(buffer);
        }
    }

    /** Never reads past the request body, so a bad batch cannot eat the next request. */
    private static final class LimitedInput extends java.io.FilterInputStream {
        private long left;
        LimitedInput(InputStream in, long limit) { super(in); left = limit; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int value = super.read();
            if (value >= 0) left--;
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int read = super.read(b, off, (int) Math.min(len, left));
            if (read > 0) left -= read;
            return read;
        }
        @Override public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, left));
            left -= skipped;
            return skipped;
        }
        @Override public int available() throws IOException { return (int) Math.min(super.available(), left); }
    }
}
