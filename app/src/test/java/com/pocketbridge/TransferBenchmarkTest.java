package com.pocketbridge;

import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

/**
 * Old vs new transfer paths on the JVM, over loopback (NOT Wi-Fi: no radio, no
 * air-time sharing, near-zero latency; it shows CPU, disk and protocol cost).
 * Gated: runs only with -Dpocketbridge.bench=true (Gradle: -Ppocketbridge.bench=true).
 *
 * 1 KB x 5000 and 100 MB go through the real phone server (MediaHttpServer):
 *   old upload = one POST /api/upload per file; new = PBB1 batch / ChunkedUploader.
 *   old download = one plain GET; new = RangedDownloader (parallel ranges + If-Range).
 * 4 GB uses a sparse source and a discarding loopback receiver (the sandbox /tmp is
 * smaller than 4 GB), so it measures the client and the protocol without disk writes.
 * Results are printed as "BENCH name bytes ms MB/s" lines.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TransferBenchmarkTest {
    private static final String CODE = "654321";
    private static final String TOKEN = "fedcba9876543210fedcba9876543210";
    private static final String BASE = "http://127.0.0.1:" + MediaHttpServer.PORT;
    private static final long MIB = 1024L * 1024;

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MediaHttpServer server;
    private Context context;

    @Before public void setUp() throws Exception {
        Assume.assumeTrue("set -Dpocketbridge.bench=true to run the benchmarks", Boolean.getBoolean("pocketbridge.bench"));
        context = RuntimeEnvironment.getApplication();
        deleteTree(new File(context.getFilesDir(), "received"));
        deleteTree(new File(context.getFilesDir(), "uploads"));
        server = new MediaHttpServer(context, CODE, TOKEN);
        server.start();
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/health").openConnection();
                if (c.getResponseCode() == 200) return;
            } catch (Exception notYet) { Thread.sleep(50); }
        }
        throw new IllegalStateException("server did not start");
    }

    @After public void tearDown() {
        if (server != null) server.stop();
        if (context != null) {
            deleteTree(new File(context.getFilesDir(), "received"));
            deleteTree(new File(context.getFilesDir(), "uploads"));
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void report(String name, long bytes, long nanos) {
        double ms = nanos / 1e6;
        System.out.println(String.format(Locale.ROOT, "BENCH %-28s %12d bytes %10.1f ms %9.1f MB/s cpus=%d",
                name, bytes, ms, bytes / 1e6 / (nanos / 1e9), Runtime.getRuntime().availableProcessors()));
    }

    private static int legacyPost(String base, String name, InputStream body, long length) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/upload?name=" + java.net.URLEncoder.encode(name, "UTF-8") + "&code=" + CODE).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(length);
        c.setReadTimeout(120000);
        byte[] buffer = new byte[1024 * 1024];
        try (OutputStream out = c.getOutputStream()) {
            for (int read; (read = body.read(buffer)) != -1; ) out.write(buffer, 0, read);
        }
        int status = c.getResponseCode();
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) { if (in != null) while (in.read(buffer) != -1) { /* drain */ } }
        return status;
    }

    private static long plainGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setReadTimeout(120000);
        long n = 0;
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream in = c.getInputStream()) { for (int read; (read = in.read(buffer)) != -1; ) n += read; }
        return n;
    }

    @Test public void small1KBx5000() throws Exception {
        File dir = temporary.newFolder("small");
        List<File> files = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        byte[] kb = new byte[1024];
        new Random(1).nextBytes(kb);
        for (int i = 0; i < 5000; i++) {
            File f = new File(dir, "f" + i + ".bin");
            Files.write(f.toPath(), kb);
            files.add(f);
            paths.add(String.format(Locale.ROOT, "set/%02d/f%04d.bin", i / 500, i));
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < files.size(); i++) {
            try (InputStream in = Files.newInputStream(files.get(i).toPath())) {
                assertEquals(201, legacyPost(BASE, "f" + i + ".bin", in, 1024));
            }
        }
        report("1KBx5000 upload old", 5000L * 1024, System.nanoTime() - t0);
        deleteTree(new File(context.getFilesDir(), "received"));
        long t1 = System.nanoTime();
        JSONObject reply = new ChunkedUploader(BASE, CODE, null).uploadBatch(files, paths);
        report("1KBx5000 upload new (PBB1)", 5000L * 1024, System.nanoTime() - t1);
        assertEquals(5000, reply.getInt("count"));
    }

    @Test public void file100MB() throws Exception {
        long size = 100 * MIB;
        File source = temporary.newFile("big.bin");
        byte[] block = new byte[(int) MIB];
        new Random(2).nextBytes(block);
        try (OutputStream out = Files.newOutputStream(source.toPath())) { for (int i = 0; i < 100; i++) out.write(block); }
        long t0 = System.nanoTime();
        try (InputStream in = Files.newInputStream(source.toPath())) { assertEquals(201, legacyPost(BASE, "old.bin", in, size)); }
        report("100MB upload old", size, System.nanoTime() - t0);
        long t1 = System.nanoTime();
        ChunkedUploader.Sent sent = new ChunkedUploader(BASE, CODE, null).upload(source, "", null);
        report("100MB upload new", size, System.nanoTime() - t1);
        String url = BASE + "/api/inbox/" + java.net.URLEncoder.encode(sent.id, "UTF-8") + "?code=" + CODE;
        long t2 = System.nanoTime();
        assertEquals(size, plainGet(url));
        report("100MB download old", size, System.nanoTime() - t2);
        File part = new File(temporary.getRoot(), "down.part");
        long t3 = System.nanoTime();
        RangedDownloader.Result result = new RangedDownloader(url, part, size, null).download();
        report("100MB download new", size, System.nanoTime() - t3);
        assertEquals(size, result.file.length());
    }

    @Test public void file4GBSparseDiscarding() throws Exception {
        long size = 4L << 30;
        File sparse = temporary.newFile("sparse.bin");
        try (RandomAccessFile f = new RandomAccessFile(sparse, "rw")) { f.setLength(size); }
        try (DiscardServer sink = new DiscardServer(size)) {
            String base = "http://127.0.0.1:" + sink.port();
            long t0 = System.nanoTime();
            try (InputStream in = Files.newInputStream(sparse.toPath())) { assertEquals(201, legacyPost(base, "old.bin", in, size)); }
            report("4GB upload old", size, System.nanoTime() - t0);
            long t1 = System.nanoTime();
            ChunkedUploader.Sent sent = new ChunkedUploader(base, CODE, null).upload(sparse, "", null);
            report("4GB upload new", size, System.nanoTime() - t1);
            assertEquals(size, sent.bytesSent);
            long t2 = System.nanoTime();
            assertEquals(size, plainGet(base + "/zero"));
            report("4GB download old", size, System.nanoTime() - t2);
            long t3 = System.nanoTime();
            assertEquals(size, parallelRangedDiscard(base + "/zero", size, 4, 16 * MIB));
            report("4GB download new", size, System.nanoTime() - t3);
        }
    }

    /** RangedDownloader's request pattern (Range + If-Range, 4 workers) into a discarding sink. */
    private static long parallelRangedDiscard(String url, long size, int workers, long chunk) throws Exception {
        List<long[]> parts = TransferPlan.split(0, size, chunk);
        AtomicLong next = new AtomicLong(), total = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        Exception[] failure = {null};
        for (int w = 0; w < workers; w++) {
            Thread t = new Thread(() -> {
                byte[] buffer = new byte[1024 * 1024];
                for (int i; (i = (int) next.getAndIncrement()) < parts.size(); ) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestProperty("Range", TransferPlan.rangeHeader(parts.get(i)[0], parts.get(i)[1]));
                        c.setRequestProperty("If-Range", DiscardServer.ETAG);
                        if (c.getResponseCode() != 206) throw new IOException("status " + c.getResponseCode());
                        try (InputStream in = c.getInputStream()) { for (int r; (r = in.read(buffer)) != -1; ) total.addAndGet(r); }
                    } catch (Exception e) { synchronized (failure) { failure[0] = e; } return; }
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        if (failure[0] != null) throw failure[0];
        return total.get();
    }

    /**
     * Loopback receiver that discards: /api/upload/start, PUT, finish and legacy
     * POST read and drop the body; GET /zero serves zeros with ranges. Keep-alive.
     */
    static final class DiscardServer implements AutoCloseable {
        static final String ETAG = "\"zero-1\"";
        private final ServerSocket socket;
        private final long size;

        DiscardServer(long size) throws IOException {
            this.size = size;
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread accept = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket client = socket.accept();
                        Thread t = new Thread(() -> serve(client));
                        t.setDaemon(true);
                        t.start();
                    } catch (IOException closed) { return; }
                }
            });
            accept.setDaemon(true);
            accept.start();
        }

        int port() { return socket.getLocalPort(); }

        private static String line(InputStream in) throws IOException {
            StringBuilder b = new StringBuilder();
            for (int c; (c = in.read()) != -1; ) {
                if (c == '\n') return b.toString().trim();
                b.append((char) c);
            }
            return b.length() == 0 ? null : b.toString();
        }

        private void serve(Socket client) {
            try (Socket s = client) {
                InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16);
                OutputStream out = new BufferedOutputStream(s.getOutputStream(), 1 << 16);
                byte[] buffer = new byte[1024 * 1024];
                while (true) {
                    String request = line(in);
                    if (request == null || request.isEmpty()) return;
                    long length = 0;
                    String range = null;
                    for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                        String lower = h.toLowerCase(Locale.ROOT);
                        if (lower.startsWith("content-length:")) length = Long.parseLong(h.substring(15).trim());
                        if (lower.startsWith("range:")) range = h.substring(6).trim();
                    }
                    for (long left = length; left > 0; ) {
                        int r = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                        if (r < 0) return;
                        left -= r;
                    }
                    String[] parts = request.split(" ");
                    String path = parts[1];
                    if (parts[0].equals("GET") && path.startsWith("/zero")) {
                        long start = 0, end = size - 1;
                        boolean partial = range != null;
                        if (partial) {
                            String spec = range.substring(6);
                            int dash = spec.indexOf('-');
                            start = Long.parseLong(spec.substring(0, dash));
                            if (dash < spec.length() - 1) end = Long.parseLong(spec.substring(dash + 1));
                        }
                        long n = end - start + 1;
                        out.write(((partial ? "HTTP/1.1 206 Partial\r\nContent-Range: bytes " + start + "-" + end + "/" + size + "\r\n" : "HTTP/1.1 200 OK\r\n")
                                + "Content-Length: " + n + "\r\nETag: " + ETAG + "\r\nAccept-Ranges: bytes\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        java.util.Arrays.fill(buffer, (byte) 0);
                        for (long left = n; left > 0; ) {
                            int w = (int) Math.min(buffer.length, left);
                            out.write(buffer, 0, w);
                            left -= w;
                        }
                        out.flush();
                        continue;
                    }
                    String body;
                    int status = 200;
                    if (path.startsWith("/api/upload/start")) {
                        body = "{\"id\":\"" + "0123456789abcdef0123456789abcdef" + "\",\"missing\":[[0," + size + "]],\"chunk\":" + (16 * MIB) + ",\"connections\":4}";
                    } else if (path.contains("/finish")) {
                        status = 201;
                        body = "{\"id\":\"x\",\"name\":\"x\",\"bytes\":" + size + "}";
                    } else if (path.startsWith("/api/upload?")) {
                        status = 201;
                        body = "{\"ok\":true}";
                    } else {
                        body = "{\"received\":0}";
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(bytes);
                    out.flush();
                }
            } catch (IOException ended) { /* client closed */ }
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
