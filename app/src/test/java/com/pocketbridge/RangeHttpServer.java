package com.pocketbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A test file server with byte ranges, a strong ETag and If-Range, like the
 * phone server and the Go helper. Knobs: swap the content (new ETag), turn
 * ranges off, cut every response off once a byte budget is spent.
 */
final class RangeHttpServer implements AutoCloseable {
    private final ServerSocket socket;
    private volatile byte[] content;
    private volatile String etag;
    private volatile boolean ranges = true;
    /** Body bytes still allowed before responses are cut off (Long.MAX_VALUE = no limit). */
    final AtomicLong budget = new AtomicLong(Long.MAX_VALUE);
    final AtomicLong bodyBytes = new AtomicLong();
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maxActive = new AtomicInteger();
    /** Swap to this content once this many ranged requests were answered (0 = never). */
    volatile int swapAfter;
    volatile byte[] swapTo;
    private final AtomicInteger ranged = new AtomicInteger();
    private int version;

    RangeHttpServer(byte[] content) throws IOException {
        setContent(content);
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(this::serve, "range-server");
        accept.setDaemon(true);
        accept.start();
    }

    synchronized void setContent(byte[] content) {
        this.content = content;
        this.etag = "\"v" + (++version) + "-" + content.length + "\"";
    }

    void ranges(boolean on) { ranges = on; }

    String url(String path) { return "http://127.0.0.1:" + socket.getLocalPort() + path; }

    private void serve() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Thread worker = new Thread(() -> handle(client), "range-client");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException closed) { return; }
        }
    }

    private void handle(Socket client) {
        int now = active.incrementAndGet();
        maxActive.accumulateAndGet(now, Math::max);
        try (Socket ignored = client) {
            client.setSoTimeout(10_000);
            InputStream in = client.getInputStream();
            String requestLine = line(in);
            if (requestLine == null) return;
            requests.incrementAndGet();
            Map<String, String> headers = new HashMap<>();
            String header;
            while ((header = line(in)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
            }
            byte[] body;
            String tag;
            synchronized (this) { body = content; tag = etag; }
            long total = body.length;
            String range = headers.get("range");
            String ifRange = headers.get("if-range");
            boolean partial = ranges && range != null && range.startsWith("bytes=") && (ifRange == null || ifRange.equals(tag));
            long start = 0, end = total - 1;
            StringBuilder head = new StringBuilder();
            if (partial) {
                String[] bounds = range.substring(6).split("-", 2);
                start = Long.parseLong(bounds[0]);
                end = bounds[1].isEmpty() ? total - 1 : Math.min(total - 1, Long.parseLong(bounds[1]));
                if (start >= total) {
                    head.append("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */").append(total)
                            .append("\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                    client.getOutputStream().write(head.toString().getBytes(StandardCharsets.US_ASCII));
                    return;
                }
                head.append("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(total).append("\r\n");
            } else {
                head.append("HTTP/1.1 200 OK\r\n");
            }
            long length = Math.max(0, end - start + 1);
            if (ranges) head.append("Accept-Ranges: bytes\r\nETag: ").append(tag).append("\r\n");
            head.append("Content-Length: ").append(length).append("\r\nConnection: close\r\n\r\n");
            OutputStream out = client.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            long at = start;
            while (at <= end) {
                int n = (int) Math.min(64 * 1024, end - at + 1);
                long left = budget.getAndAdd(-n);
                if (left <= 0) return; // cut off: the client sees a short body
                if (left < n) n = (int) left;
                out.write(body, (int) at, n);
                bodyBytes.addAndGet(n);
                at += n;
            }
            out.flush();
            if (partial && swapAfter > 0 && ranged.incrementAndGet() == swapAfter && swapTo != null) setContent(swapTo);
        } catch (IOException ignored) {
        } finally {
            active.decrementAndGet();
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') bytes.write(value);
        }
        return value == -1 && bytes.size() == 0 ? null : bytes.toString("UTF-8");
    }

    @Override public void close() throws IOException { socket.close(); }
}
