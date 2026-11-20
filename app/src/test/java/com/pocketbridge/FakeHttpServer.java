package com.pocketbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A tiny HTTP/1.1 server for tests (the JDK's HttpServer is not on the Android test classpath). */
final class FakeHttpServer implements AutoCloseable {
    static final class Request {
        final String method;
        final String path;
        final String rawQuery;
        final String body;
        Request(String method, String path, String rawQuery, String body) {
            this.method = method; this.path = path; this.rawQuery = rawQuery; this.body = body;
        }
    }

    static final class Reply {
        final int status;
        final String body;
        final byte[] bytes;
        Reply(int status, String body) { this.status = status; this.body = body; this.bytes = body.getBytes(StandardCharsets.UTF_8); }
        Reply(int status, byte[] bytes) { this.status = status; this.body = ""; this.bytes = bytes; }
    }

    interface Handler { Reply handle(Request request); }

    private final ServerSocket socket;
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final Thread thread;

    FakeHttpServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::serve, "fake-helper");
        thread.setDaemon(true);
        thread.start();
    }

    int port() { return socket.getLocalPort(); }

    void route(String path, Handler handler) { routes.put(path, handler); }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(5000);
                InputStream in = client.getInputStream();
                String requestLine = line(in);
                if (requestLine == null) continue;
                int length = 0;
                String header;
                while ((header = line(in)) != null && !header.isEmpty()) {
                    if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                        length = Integer.parseInt(header.substring(15).trim());
                    }
                }
                byte[] body = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(body, read, length - read);
                    if (n < 0) break;
                    read += n;
                }
                String[] parts = requestLine.split(" ");
                String target = parts[1];
                int q = target.indexOf('?');
                String path = q < 0 ? target : target.substring(0, q);
                String query = q < 0 ? "" : target.substring(q + 1);
                Handler handler = routes.get(path);
                Reply reply = handler == null ? new Reply(404, "not found")
                        : handler.handle(new Request(parts[0], path, query, new String(body, StandardCharsets.UTF_8)));
                OutputStream out = client.getOutputStream();
                out.write(("HTTP/1.1 " + reply.status + " X\r\nContent-Type: application/json\r\nContent-Length: "
                        + reply.bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(reply.bytes);
                out.flush();
            } catch (IOException closedOrBroken) {
                if (socket.isClosed()) return;
            }
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') out.write(c);
        }
        if (c == -1 && out.size() == 0) return null;
        return out.toString("UTF-8");
    }

    @Override public void close() throws IOException { socket.close(); }
}
