package com.pocketbridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The share chat. The laptop writes through the phone web server and the phone
 * reads and writes the same file, so both sides see one conversation and
 * nothing ever leaves the local network.
 */
final class ChatStore {
    static final int MAX_MESSAGES = 100;
    private static final String FILE_NAME = "share-messages.json";
    private static final Object LOCK = new Object();

    private ChatStore() { }

    /** Returns the stored messages, oldest first. */
    static JSONArray read(Context context) {
        synchronized (LOCK) {
            String raw = readFile(context);
            JSONArray result = new JSONArray();
            if (raw == null || raw.isEmpty()) return result;
            try {
                JSONArray stored = new JSONObject(raw).optJSONArray("messages");
                if (stored == null) return result;
                // Keep only the tail so a long session cannot grow without limit.
                for (int i = Math.max(0, stored.length() - MAX_MESSAGES); i < stored.length(); i++) {
                    JSONObject item = stored.optJSONObject(i);
                    if (item != null) result.put(item);
                }
            } catch (Exception ignored) { }
            return result;
        }
    }

    /** Trimmed message text, or null when there is nothing worth storing. */
    static String sanitise(String text) {
        if (text == null) return null;
        String clean = text.trim();
        if (clean.isEmpty()) return null;
        return clean.length() > 2000 ? clean.substring(0, 2000) : clean;
    }

    /** "phone" or "laptop"; anything else is treated as a laptop message. */
    static String normalizeSender(String from) {
        return "phone".equals(from) ? "phone" : "laptop";
    }

    /** Keeps only the newest {@code max} messages, oldest first. */
    static JSONArray trimmed(JSONArray messages, int max) {
        JSONArray kept = new JSONArray();
        if (messages == null) return kept;
        for (int i = Math.max(0, messages.length() - max); i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item != null) kept.put(item);
        }
        return kept;
    }

    /** Adds one message. {@code from} is "phone" or "laptop". */
    static void append(Context context, String text, String from) {
        String clean = sanitise(text);
        if (clean == null) return;
        synchronized (LOCK) {
            JSONArray messages = read(context);
            try {
                messages.put(new JSONObject()
                        .put("text", clean)
                        .put("time", System.currentTimeMillis())
                        .put("from", normalizeSender(from)));
                writeFile(context, new JSONObject().put("messages", trimmed(messages, MAX_MESSAGES)).toString());
            } catch (Exception ignored) { }
        }
    }

    /** Adds a file transfer card to the shared conversation. */
    static void appendFile(Context context, String name, String id, long size, String from) {
        String extension = "";
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) extension = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String mime = extension.isEmpty() ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        synchronized (LOCK) {
            JSONArray messages = read(context);
            try {
                messages.put(new JSONObject().put("text", "Shared a file")
                        .put("time", System.currentTimeMillis())
                        .put("from", normalizeSender(from))
                        .put("attachment", new JSONObject().put("name", name).put("id", id).put("size", size).put("mime", mime)));
                writeFile(context, new JSONObject().put("messages", trimmed(messages, MAX_MESSAGES)).toString());
            } catch (Exception ignored) { }
        }
    }

    static void clear(Context context) {
        synchronized (LOCK) { writeFile(context, "{\"messages\":[]}"); }
    }

    private static String readFile(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return "";
        try (InputStream stream = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "";
        }
    }

    private static void writeFile(Context context, String json) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
}
