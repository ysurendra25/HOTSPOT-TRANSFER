package com.pocketbridge;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class MediaRepository {
    static final class Item {
        final String key;
        final String name;
        final String mime;
        final String kind;
        final long size;
        final long modified;
        final Uri uri;

        Item(String key, String name, String mime, String kind, long size, long modified, Uri uri) {
            this.key = key; this.name = name; this.mime = mime; this.kind = kind;
            this.size = size; this.modified = modified; this.uri = uri;
        }

        JSONObject toJson() {
            JSONObject result = new JSONObject();
            try {
                result.put("id", key);
                result.put("name", name);
                result.put("mime", mime);
                result.put("kind", kind);
                result.put("size", size);
                result.put("modified", modified);
                result.put("url", "/api/media/" + Uri.encode(key));
                // Images and videos get a small preview so the browser grid does not
                // have to pull multi-megabyte originals over the hotspot.
                if ("image".equals(kind) || "video".equals(kind)) {
                    result.put("thumb", "/api/thumb/" + Uri.encode(key));
                }
            } catch (Exception ignored) { }
            return result;
        }
    }

    private final Context context;
    MediaRepository(Context context) { this.context = context.getApplicationContext(); }

    List<Item> list(String kind) {
        List<Item> output = new ArrayList<>();
        if ("all".equals(kind) || "images".equals(kind)) collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", "images", output);
        if ("all".equals(kind) || "videos".equals(kind)) collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", "videos", output);
        if ("all".equals(kind) || "audio".equals(kind)) collect(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", "audio", output);
        Collections.sort(output, (a, b) -> Long.compare(b.modified, a.modified));
        return output;
    }

    /** True when the app may read this media kind. Used to explain an empty library. */
    boolean granted(String kind) {
        if ("images".equals(kind)) return grantedImageOrVideo(Manifest.permission.READ_MEDIA_IMAGES);
        if ("videos".equals(kind)) return grantedImageOrVideo(Manifest.permission.READ_MEDIA_VIDEO);
        if ("audio".equals(kind)) return grantedSimple(Manifest.permission.READ_MEDIA_AUDIO);
        return granted("images") || granted("videos") || granted("audio");
    }

    JSONObject accessJson() {
        JSONObject result = new JSONObject();
        try {
            result.put("images", granted("images"));
            result.put("videos", granted("videos"));
            result.put("audio", granted("audio"));
        } catch (Exception ignored) { }
        return result;
    }

    private boolean grantedImageOrVideo(String permission) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (check(permission)) return true;
            // Android 14+ lets the user share only selected items.
            return Build.VERSION.SDK_INT >= 34 && check(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        }
        return check(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private boolean grantedSimple(String permission) {
        if (Build.VERSION.SDK_INT >= 33) return check(permission);
        return check(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private boolean check(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    Item find(String key) {
        if (key == null) return null;
        int split = key.indexOf(':');
        if (split < 1) return null;
        String type = key.substring(0, split);
        long id;
        try { id = Long.parseLong(key.substring(split + 1)); } catch (NumberFormatException error) { return null; }
        Uri base;
        String kind;
        if ("image".equals(type)) { base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; kind = "image"; }
        else if ("video".equals(type)) { base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; kind = "video"; }
        else if ("audio".equals(type)) { base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; kind = "audio"; }
        else return null;
        Uri uri = ContentUris.withAppendedId(base, id);
        String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return new Item(key, value(cursor, 0, "File"), value(cursor, 1, "application/octet-stream"), kind,
                    number(cursor, 2), number(cursor, 3), uri);
        } catch (SecurityException error) { return null; }
    }

    JSONArray listJson(String kind) {
        JSONArray array = new JSONArray();
        for (Item item : list(kind)) array.put(item.toJson());
        return array;
    }

    private void collect(Uri collection, String prefix, String kind, List<Item> output) {
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
        try (Cursor cursor = context.getContentResolver().query(collection, projection, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return;
            int limit = 5000;
            while (cursor.moveToNext() && limit-- > 0) {
                long id = number(cursor, 0);
                Uri itemUri = ContentUris.withAppendedId(collection, id);
                String mime = value(cursor, 2, "application/octet-stream");
                output.add(new Item(prefix + ":" + id, value(cursor, 1, "File"), mime, kind,
                        number(cursor, 3), number(cursor, 4), itemUri));
            }
        } catch (SecurityException ignored) { }
    }

    private static String value(Cursor cursor, int index, String fallback) {
        String value = cursor.getString(index);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static long number(Cursor cursor, int index) {
        try { return cursor.getLong(index); } catch (Exception ignored) { return 0; }
    }
}
