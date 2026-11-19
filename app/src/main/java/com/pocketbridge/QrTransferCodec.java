package com.pocketbridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small optical-transfer framing format shared with tools/win-control/web/qr.html. */
final class QrTransferCodec {
    static final int MAX_BYTES = 128 * 1024;
    private static final int CHUNK_BYTES = 1000;
    private static final int MAX_FRAMES = 132;

    static final class Frame {
        final boolean text;
        final String id;
        final int index;
        final int total;
        final String name;
        final String mime;
        final byte[] bytes;

        Frame(boolean text, String id, int index, int total, String name, String mime, byte[] bytes) {
            this.text = text; this.id = id; this.index = index; this.total = total;
            this.name = name; this.mime = mime; this.bytes = bytes;
        }
    }

    private QrTransferCodec() { }

    static List<String> textFrames(String id, String text) {
        return buildFrames(id, true, "", "", text.getBytes(StandardCharsets.UTF_8));
    }

    static List<String> fileFrames(String id, String name, String mime, byte[] data) {
        return buildFrames(id, false, name, mime, data);
    }

    private static List<String> buildFrames(String id, boolean text, String name, String mime, byte[] data) {
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("QR transfers are limited to 128 KiB. Use Wi-Fi or Hotspot for larger files.");
        int total = Math.max(1, (data.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        if (total > MAX_FRAMES) throw new IllegalArgumentException("This item needs too many QR frames.");
        String encodedName = encode(name.getBytes(StandardCharsets.UTF_8));
        String encodedMime = encode(mime.getBytes(StandardCharsets.UTF_8));
        List<String> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK_BYTES;
            int end = Math.min(data.length, start + CHUNK_BYTES);
            byte[] part = java.util.Arrays.copyOfRange(data, start, end);
            result.add(text
                    ? "PBQ1|T|" + id + "|" + i + "|" + total + "|" + encode(part)
                    : "PBQ1|F|" + id + "|" + i + "|" + total + "|" + encodedName + "|" + encodedMime + "|" + encode(part));
        }
        return result;
    }

    static Frame parse(String value) {
        if (value == null || !value.startsWith("PBQ1|")) return null;
        String[] part = value.split("\\|", -1);
        try {
            if (part.length == 6 && "T".equals(part[1])) {
                int index = Integer.parseInt(part[3]), total = Integer.parseInt(part[4]);
                if (!validSequence(index, total)) return null;
                byte[] bytes = decode(part[5]);
                if (bytes.length > CHUNK_BYTES) return null;
                return new Frame(true, part[2], index, total, "", "text/plain", bytes);
            }
            if (part.length == 8 && "F".equals(part[1])) {
                int index = Integer.parseInt(part[3]), total = Integer.parseInt(part[4]);
                if (!validSequence(index, total)) return null;
                byte[] bytes = decode(part[7]);
                if (bytes.length > CHUNK_BYTES) return null;
                return new Frame(false, part[2], index, total,
                        new String(decode(part[5]), StandardCharsets.UTF_8),
                        new String(decode(part[6]), StandardCharsets.UTF_8), bytes);
            }
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    private static boolean validSequence(int index, int total) {
        return index >= 0 && total > 0 && total <= MAX_FRAMES && index < total;
    }

    private static String encode(byte[] value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return java.util.Base64.getUrlDecoder().decode(value);
    }
}
