package com.pocketbridge;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * The Windows helper link the user pastes on the phone, such as
 * {@code http://192.168.43.42:7070/?t=abcd}. Every laptop feature (files,
 * screen, power) is built from this one parsed value so they all agree on the
 * host, port and access code, and so a pasted link with stray spaces, quotes
 * or no {@code http://} still works.
 *
 * Plain Java (no android.net.Uri) so it is covered by fast unit tests.
 */
final class HelperLink {
    static final int DEFAULT_PORT = 7070;

    final String host;
    final int port;
    final String token;

    private HelperLink(String host, int port, String token) {
        this.host = host;
        this.port = port;
        this.token = token;
    }

    /** Parses a pasted helper link; the message of the exception is shown to the user. */
    static HelperLink parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        // People copy the whole log line ("[pc-remote] LAN: http://…") or wrap
        // the link in quotes; keep only the link itself.
        int http = text.toLowerCase(Locale.ROOT).indexOf("http");
        if (http > 0) text = text.substring(http);
        while (text.length() > 1 && (text.startsWith("\"") || text.startsWith("'") || text.startsWith("<"))) text = text.substring(1);
        while (text.length() > 1 && (text.endsWith("\"") || text.endsWith("'") || text.endsWith(">"))) text = text.substring(0, text.length() - 1);
        int space = text.indexOf(' ');
        if (space > 0) text = text.substring(0, space);
        if (text.isEmpty()) throw new IllegalArgumentException("Paste the link the Windows helper prints, for example http://192.168.43.42:7070/?t=…");
        if (!text.contains("://")) text = "http://" + text;
        URI uri;
        try {
            uri = new URI(text);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("That helper link is not a valid web address. Copy it again from the Windows helper window.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme)) {
            throw new IllegalArgumentException("Use the local http:// link from the Windows helper, not " + scheme + "://.");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("The helper link has no laptop address in it.");
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;
        String token = queryValue(uri.getRawQuery(), "t");
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("The helper link is missing its access code (the ?t=… part). Copy the whole link from the Windows helper.");
        }
        return new HelperLink(host, port, token.trim());
    }

    /** Null when the link cannot be parsed, for callers that only need a yes/no. */
    static HelperLink tryParse(String raw) {
        try { return parse(raw); } catch (IllegalArgumentException invalid) { return null; }
    }

    static String queryValue(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!key.equals(name)) continue;
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try { return URLDecoder.decode(value, "UTF-8"); }
            catch (Exception ignored) { return value; }
        }
        return null;
    }

    boolean isLoopback() {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]") || h.equals("0.0.0.0");
    }

    HelperLink withHost(String newHost) { return new HelperLink(newHost, port, token); }

    String base() {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port;
    }

    static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (Exception impossible) { return value; }
    }

    /**
     * An API url. {@code path} is used as given (already escaped), extra query
     * pairs are encoded here, and the access code always comes last.
     */
    String url(String path, String... queryPairs) {
        StringBuilder out = new StringBuilder(base());
        String p = path == null || path.isEmpty() ? "/" : path;
        if (!p.startsWith("/")) p = "/" + p;
        int existingQuery = p.indexOf('?');
        out.append(p);
        char sep = existingQuery >= 0 ? '&' : '?';
        for (int i = 0; i + 1 < queryPairs.length; i += 2) {
            out.append(sep).append(encode(queryPairs[i])).append('=').append(encode(queryPairs[i + 1]));
            sep = '&';
        }
        out.append(sep).append("t=").append(encode(token));
        return out.toString();
    }

    /** The helper's own viewer page. */
    String pageUrl() { return url("/"); }

    /** The link in the form the helper prints, for saving back into the field. */
    String display() { return pageUrl(); }

    @Override public boolean equals(Object other) {
        if (!(other instanceof HelperLink)) return false;
        HelperLink that = (HelperLink) other;
        return host.equals(that.host) && port == that.port && token.equals(that.token);
    }

    @Override public int hashCode() { return (host + ":" + port + "/" + token).hashCode(); }
}
