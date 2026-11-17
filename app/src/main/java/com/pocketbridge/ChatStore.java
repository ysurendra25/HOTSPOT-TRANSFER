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

    /** Adds one message. {@code from} is "phone" or "laptop". */
    static void append(Context context, String text, String from) {
        if (text == null) return;
        String clean = text.trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 2000) clean = clean.substring(0, 2000);
        synchronized (LOCK) {
            JSONArray messages = read(context);
            try {
                messages.put(new JSONObject()
                        .put("text", clean)
                        .put("time", System.currentTimeMillis())
                        .put("from", from == null ? "laptop" : from));
                JSONArray trimmed = new JSONArray();
                for (int i = Math.max(0, messages.length() - MAX_MESSAGES); i < messages.length(); i++) trimmed.put(messages.get(i));
                writeFile(context, new JSONObject().put("messages", trimmed).toString());
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
