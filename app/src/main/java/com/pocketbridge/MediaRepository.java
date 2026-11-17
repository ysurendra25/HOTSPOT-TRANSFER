package com.pocketbridge;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class MediaRepository {
    static final class Page {
        final List<Item> items;
        final int nextOffset;
        final boolean hasMore;

        Page(List<Item> items, int nextOffset, boolean hasMore) {
            this.items = items;
            this.nextOffset = nextOffset;
            this.hasMore = hasMore;
        }
    }

    static final class Item {
        final String key;
        final String name;
        final String mime;
        final String kind;
        final long size;
        final long modified;
        final Uri uri;

        Item(String key, String name, String mime, String kind, long size, long modified, Uri uri) {
            this.key = key; this.name = name;
            this.mime = MediaTypeClassifier.mimeType(name, mime);
            this.kind = MediaTypeClassifier.kind(this.mime, name, kind);
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
                // have to pull multi-megabyte originals over the hotspot. Video
                // thumbnails work on every supported Android version because the
                // server falls back to grabbing a frame from the file itself.
                if ("image".equals(kind) || "video".equals(kind)) {
                    result.put("thumb", "/api/thumb/" + Uri.encode(key));
                }
                // A larger JPEG for the viewer: loads fast over the hotspot and also
                // shows formats a browser cannot display directly (HEIC, AVIF, ...).
                if ("image".equals(kind)) {
                    result.put("preview", "/api/preview/" + Uri.encode(key));
                }
            } catch (Exception ignored) { }
            return result;
        }
    }

    private final Context context;
    MediaRepository(Context context) { this.context = context.getApplicationContext(); }

    Page listPage(String kind, int offset, int requestedLimit) {
        int safeOffset = Math.max(0, Math.min(20_000, offset));
        int limit = Math.max(1, Math.min(5_000, requestedLimit));
        int queryLimit = Math.min(5_000, safeOffset + limit + 1);
        // Accept both "image" and "images" (and friends) so the phone UI, the web
        // page and older callers all land on the same branch.
        String filter = normalizeKind(kind);
        List<Item> output = new ArrayList<>();
        if ("all".equals(filter) || "images".equals(filter)) collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", "image", queryLimit, output);
        if ("all".equals(filter) || "videos".equals(filter)) collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", "video", queryLimit, output);
        if ("all".equals(filter) || "audio".equals(filter)) collect(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", "audio", queryLimit, output);
        if ("all".equals(filter) || "files".equals(filter) || "images".equals(filter)
                || "videos".equals(filter) || "audio".equals(filter)) collectDocuments(queryLimit, output);
        if ("images".equals(filter)) output.removeIf(item -> !"image".equals(item.kind));
        else if ("videos".equals(filter)) output.removeIf(item -> !"video".equals(item.kind));
        else if ("audio".equals(filter)) output.removeIf(item -> !"audio".equals(item.kind));
        else if ("files".equals(filter)) output.removeIf(item -> !"file".equals(item.kind));
        Collections.sort(output, (a, b) -> Long.compare(b.modified, a.modified));
        int from = Math.min(safeOffset, output.size());
        int to = Math.min(from + limit, output.size());
        return new Page(new ArrayList<>(output.subList(from, to)), to, output.size() > to);
    }

    /** Maps singular and plural kind names onto the plural form listPage uses. */
    static String normalizeKind(String kind) {
        if (kind == null) return "all";
        String value = kind.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "all".equals(value)) return "all";
        if ("image".equals(value) || "images".equals(value) || "photo".equals(value) || "photos".equals(value)) return "images";
        if ("video".equals(value) || "videos".equals(value)) return "videos";
        if ("audio".equals(value) || "audios".equals(value) || "music".equals(value)) return "audio";
        if ("file".equals(value) || "files".equals(value) || "document".equals(value) || "documents".equals(value)) return "files";
        return "all";
    }

    /** True when the app may read this media kind. Used to explain an empty library. */
    boolean granted(String kind) {
        if ("images".equals(kind)) return grantedImageOrVideo(Manifest.permission.READ_MEDIA_IMAGES);
        if ("videos".equals(kind)) return grantedImageOrVideo(Manifest.permission.READ_MEDIA_VIDEO);
        if ("audio".equals(kind)) return grantedSimple(Manifest.permission.READ_MEDIA_AUDIO);
        if ("files".equals(kind)) return grantedDocuments();
        return granted("images") || granted("videos") || granted("audio") || granted("files");
    }

    JSONObject accessJson() {
        JSONObject result = new JSONObject();
        try {
            result.put("images", granted("images"));
            result.put("videos", granted("videos"));
            result.put("audio", granted("audio"));
            result.put("files", granted("files"));
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

    private boolean grantedDocuments() {
        // Android 11+ has no broad "documents" permission for ordinary apps, so the
        // Files tab can only show what the media store already indexes for us.
        return check(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private boolean check(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private long resolveSize(Uri uri, long size) {
        if (size > 0) return size;
        try (android.content.res.AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor == null) return size;
            long actual = descriptor.getLength();
            if (actual > 0) return actual;
            android.os.ParcelFileDescriptor file = descriptor.getParcelFileDescriptor();
            if (file != null && file.getStatSize() > 0) return file.getStatSize();
        } catch (Exception ignored) { }
        return size;
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
        else if ("file".equals(type)) { base = MediaStore.Files.getContentUri("external"); kind = "file"; }
        else return null;
        Uri uri = ContentUris.withAppendedId(base, id);
        String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String name = value(cursor, 0, "File");
            return new Item(key, name, value(cursor, 1, "application/octet-stream"), kind,
                    resolveSize(uri, number(cursor, 2)), number(cursor, 3), uri);
        } catch (SecurityException error) { return null; }
    }

    JSONArray listJson(Page page) {
        JSONArray array = new JSONArray();
        for (Item item : page.items) array.put(item.toJson());
        return array;
    }

    private void collect(Uri collection, String prefix, String kind, int maxRows, List<Item> output) {
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
        try (Cursor cursor = queryPage(collection, projection, null, null, maxRows)) {
            if (cursor == null) return;
            int limit = maxRows;
            while (cursor.moveToNext() && limit-- > 0) {
                long id = number(cursor, 0);
                Uri itemUri = ContentUris.withAppendedId(collection, id);
                String mime = value(cursor, 2, "application/octet-stream");
                output.add(new Item(prefix + ":" + id, value(cursor, 1, "File"), mime, kind,
                        resolveSize(itemUri, number(cursor, 3)), number(cursor, 4), itemUri));
            }
        } catch (SecurityException ignored) { }
    }

    /** Non-media files the media store knows about (documents, archives, and so on). */
    private void collectDocuments(int maxRows, List<Item> output) {
        Uri collection = MediaStore.Files.getContentUri("external");
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + " IS NULL OR "
                + MediaStore.Files.FileColumns.MEDIA_TYPE + " = 0";
        try (Cursor cursor = queryPage(collection, projection, selection, null, maxRows)) {
            if (cursor == null) return;
            int limit = maxRows;
            while (cursor.moveToNext() && limit-- > 0) {
                long id = number(cursor, 0);
                String name = value(cursor, 1, "File");
                String mime = value(cursor, 2, "application/octet-stream");
                // Skip anything that already appears in the photo, video or audio tabs.
                String providerMime = mime.trim().toLowerCase(Locale.ROOT);
                if (providerMime.startsWith("image/") || providerMime.startsWith("video/") || providerMime.startsWith("audio/")) continue;
                Uri itemUri = ContentUris.withAppendedId(collection, id);
                output.add(new Item("file:" + id, name, mime, "file",
                        resolveSize(itemUri, number(cursor, 3)), number(cursor, 4), itemUri));
            }
        } catch (SecurityException ignored) { }
    }

    private Cursor queryPage(Uri collection, String[] projection, String selection,
                             String[] selectionArgs, int limit) {
        Bundle args = new Bundle();
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
        args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns._ID});
        args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        if (selection != null) args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
        if (selectionArgs != null) args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        try {
            return context.getContentResolver().query(collection, projection, args, null);
        } catch (IllegalArgumentException unsupportedArgs) {
            // Some vendor media providers ignore query bundles; retain the older
            // query path and stop after `limit` rows in collect().
            return context.getContentResolver().query(collection, projection, selection, selectionArgs,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
        }
    }

    private static String value(Cursor cursor, int index, String fallback) {
        String value = cursor.getString(index);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static long number(Cursor cursor, int index) {
        try { return cursor.getLong(index); } catch (Exception ignored) { return 0; }
    }
}
