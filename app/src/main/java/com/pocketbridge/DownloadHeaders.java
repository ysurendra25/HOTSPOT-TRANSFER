package com.pocketbridge;

import java.nio.charset.StandardCharsets;

/**
 * Builds Content-Disposition values for files the phone serves.
 *
 * Playback asks for {@code inline}; a Download button asks for {@code ?download=1}
 * and gets {@code attachment}, which makes Edge save the file into its Downloads
 * list instead of opening it in a tab. Both forms carry an ASCII {@code filename}
 * for old clients and an RFC 5987 {@code filename*} with the exact UTF-8 name.
 */
final class DownloadHeaders {
    static final String FALLBACK_NAME = "download";

    private DownloadHeaders() { }

    /** True for the query values a Download link sends: 1, true or yes. */
    static boolean wantsDownload(String queryValue) {
        if (queryValue == null) return false;
        String value = queryValue.trim();
        return value.equals("1") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
    }

    /**
     * A quoted-string-safe ASCII stand-in: every character outside printable ASCII,
     * and the quote, backslash and path separators, becomes '_'.
     */
    static String asciiFallback(String name) {
        if (name == null) return FALLBACK_NAME;
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp >= 0x7f || cp == '"' || cp == '\\' || cp == '/' || cp == ';') out.append('_');
            else out.append((char) cp);
        }
        String value = out.toString().trim();
        return value.isEmpty() || value.equals(".") || value.equals("..") ? FALLBACK_NAME : value;
    }

    /** RFC 5987 ext-value body: UTF-8 bytes, percent-encoding everything but attr-char. */
    static String rfc5987(String name) {
        String value = name == null || name.trim().isEmpty() ? FALLBACK_NAME : name;
        // CR, LF and other controls never belong in a file name.
        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            clean.append(c < 0x20 || c == 0x7f ? '_' : c);
        }
        byte[] bytes = clean.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xff;
            if (isAttrChar(c)) out.append((char) c);
            else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit(c >> 4, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static boolean isAttrChar(int c) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= '0' && c <= '9') return true;
        switch (c) {
            case '!': case '#': case '$': case '&': case '+': case '-': case '.':
            case '^': case '_': case '`': case '|': case '~':
                return true;
            default:
                return false;
        }
    }

    /** The header value, such as {@code attachment; filename="a.mp4"; filename*=UTF-8''a.mp4}. */
    static String contentDisposition(String name, boolean attachment) {
        return (attachment ? "attachment" : "inline") + "; filename=\"" + asciiFallback(name)
                + "\"; filename*=UTF-8''" + rfc5987(name);
    }

    /** The full header line, CRLF included, ready to add to a response. */
    static String headerLine(String name, boolean attachment) {
        return "Content-Disposition: " + contentDisposition(name, attachment) + "\r\n";
    }

    /**
     * A strong validator for one version of a file. Edge and Chrome only resume an
     * interrupted download when the first response carried one (with Accept-Ranges).
     */
    static String etag(String id, long size, long modified) {
        String key = id == null ? "" : id;
        long hash = 1125899906842597L;
        for (int i = 0; i < key.length(); i++) hash = 31 * hash + key.charAt(i);
        return "\"" + Long.toHexString(hash) + "-" + Long.toHexString(size) + "-" + Long.toHexString(modified) + "\"";
    }

    /** Whether a Range request may be answered with 206: no If-Range, or one naming this version. */
    static boolean ifRangeMatches(String ifRange, String etag) {
        if (ifRange == null || ifRange.trim().isEmpty()) return true;
        return ifRange.trim().equals(etag);
    }
}
