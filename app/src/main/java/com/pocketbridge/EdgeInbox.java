package com.pocketbridge;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

final class EdgeInbox {
    private EdgeInbox() {}
    /**
     * An inbox id is a file name in received/, or a folder path below it
     * ("Trip/day 1/a.jpg") for files that arrived with their folders.
     */
    static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > 4096) return false;
        String[] segments = id.split("/", -1);
        if (segments.length > RelPath.MAX_DEPTH) return false;
        for (String segment : segments) if (!validSegment(segment)) return false;
        return true;
    }

    private static boolean validSegment(String segment) {
        return !segment.isEmpty() && !segment.equals(".") && !segment.equals("..")
                && segment.indexOf((char)92) < 0 && segment.chars().noneMatch(c -> c < 32 || c == 127);
    }

    /** The received file an id names, only when it really is inside {@code inbox}; otherwise null. */
    static File resolve(File inbox, String id) {
        if (!validId(id)) return null;
        try {
            File root = inbox.getCanonicalFile();
            File file = new File(root, id).getCanonicalFile();
            return file.getPath().startsWith(root.getPath() + File.separator) && file.isFile() ? file : null;
        } catch (java.io.IOException invalid) {
            return null;
        }
    }

    /** True for transfer bookkeeping files that never show in the inbox. */
    static boolean hidden(String fileName) {
        return fileName.startsWith(".") || fileName.endsWith(".part") || fileName.endsWith(".pbresume") || fileName.endsWith(".tmp");
    }

    /** Display name for a received file id: the folder path stays, a top-level transfer prefix goes. */
    static String name(String id) {
        return id.replaceFirst("^(edge-[0-9a-f]{32}-|[0-9]+-)", "");
    }

    static List<JSONObject> list(Context context) {
        File dir = new File(context.getFilesDir(), "received");
        Map<String, String> names = new LinkedHashMap<>();
        JSONArray history = ChatStore.read(context);
        for (int i = history.length()-1; i >= 0; i--) {
            JSONObject msg = history.optJSONObject(i);
            if (msg == null || !"laptop".equals(msg.optString("from"))) continue;
            JSONObject item = msg.optJSONObject("attachment");
            if (item != null) names.put(item.optString("id"), item.optString("name"));
        }
        File[] entries = dir.listFiles((folder, id) -> id.startsWith("edge-"));
        if (entries != null) {
            Arrays.sort(entries, Comparator.comparingLong(File::lastModified).reversed());
            for (File f : entries) names.putIfAbsent(f.getName(), name(f.getName()));
        }
        List<JSONObject> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            String id = entry.getKey(), name = entry.getValue();
            if (!validId(id)) continue;
            File f = new File(dir, id);
            if (!f.isFile()) continue;
            try {
                rows.add(new JSONObject().put("id", id).put("attachmentId", id).put("name", name)
                        .put("path", "Received on this phone").put("size", f.length()).put("modified", f.lastModified())
                        .put("mime", MediaTypeClassifier.mimeType(name, null)).put("url", "/api/inbox/" + Uri.encode(id)));
            } catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
        }
        return rows;
    }
}
