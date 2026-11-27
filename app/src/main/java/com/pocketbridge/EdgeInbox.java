package com.pocketbridge;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.*;

final class EdgeInbox {
    private EdgeInbox() {}
    static boolean validId(String id) {
        return !id.isEmpty() && !id.equals(".") && !id.equals("..") && id.indexOf('/') < 0
                && id.indexOf((char)92) < 0 && id.chars().noneMatch(c -> c < 32 || c == 127);
    }

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
