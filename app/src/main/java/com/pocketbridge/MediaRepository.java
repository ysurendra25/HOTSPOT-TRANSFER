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
                if ("image".equals(kind) || "video".equals(kind) || "audio".equals(kind)) {
                    // Audio asks for album art; the server falls back to a blank when
                    // the file carries none, and the page then shows its own icon.
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

    private final java.util.Map<String, Item> picked = new java.util.LinkedHashMap<>();

    void addDocument(Uri uri) throws java.io.IOException {
        String[] columns = {android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE};
        try (Cursor cursor = context.getContentResolver().query(uri, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new java.io.IOException("Cannot read selected document");
            String key = "picked:" + java.util.UUID.nameUUIDFromBytes(uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Item item = new Item(key, value(cursor, 0, "File"), context.getContentResolver().getType(uri), "file",
                    resolveSize(uri, number(cursor, 1)), System.currentTimeMillis() / 1000, uri);
            synchronized (picked) { picked.put(key, item); }
        }
    }

    Page listPage(String kind, int offset, int requestedLimit) {
        return listPage(kind, offset, requestedLimit, "", LibrarySort.DEFAULT);
    }

    Page listPage(String kind, int offset, int requestedLimit, String query) {
        return listPage(kind, offset, requestedLimit, query, LibrarySort.DEFAULT);
    }

    /** Fields of a library item for {@link LibrarySort}. */
    static final LibrarySort.Fields<Item> ITEM_FIELDS = new LibrarySort.Fields<Item>() {
        @Override public String name(Item row) { return row.name; }
        @Override public long modified(Item row) { return row.modified; }
        @Override public long size(Item row) { return row.size; }
        @Override public String key(Item row) { return row.key; }
    };

    /** A sorted listing kept while the browser pages through it. */
    private static final class Snapshot {
        final List<Item> items;
        final long builtAt;
        Snapshot(List<Item> items, long builtAt) { this.items = items; this.builtAt = builtAt; }
    }

    static final long SNAPSHOT_LIFE_MS = 120_000L;
    private static final int MAX_SNAPSHOTS = 6;
    private final java.util.LinkedHashMap<String, Snapshot> snapshots = new java.util.LinkedHashMap<String, Snapshot>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Snapshot> eldest) { return size() > MAX_SNAPSHOTS; }
    };

    /**
     * One page of the library in the requested order. Natural name order cannot be
     * expressed in SQL, so the matching rows (metadata only) are read once, sorted
     * in memory and kept as a snapshot: the first page (offset 0) takes a fresh
     * snapshot and later pages read the same one, so paging stays stable even while
     * new photos arrive.
     */
    Page listPage(String kind, int offset, int requestedLimit, String query, LibrarySort sort) {
        String filter = normalizeKind(kind);
        String term = query == null ? "" : query.trim();
        LibrarySort order = sort == null ? LibrarySort.DEFAULT : sort;
        String cacheKey = filter + "|" + term.toLowerCase(Locale.ROOT) + "|" + order.id;
        long now = android.os.SystemClock.elapsedRealtime();
        int start = Math.max(0, offset);
        int limit = Math.max(1, Math.min(5000, requestedLimit));
        Snapshot snapshot;
        synchronized (snapshots) {
            snapshot = snapshots.get(cacheKey);
            if (start == 0 || snapshot == null || now - snapshot.builtAt > SNAPSHOT_LIFE_MS) snapshot = null;
        }
        if (snapshot == null) {
            snapshot = new Snapshot(collect(filter, term, order), now);
            synchronized (snapshots) { snapshots.put(cacheKey, snapshot); }
        }
        return slice(snapshot.items, start, limit);
    }

    static Page slice(List<Item> sorted, int offset, int limit) {
        int first = Math.min(Math.max(0, offset), sorted.size());
        int last = Math.min(sorted.size(), first + Math.max(1, limit));
        return new Page(new ArrayList<>(sorted.subList(first, last)), last, last < sorted.size());
    }

    static List<Item> sorted(List<Item> items, LibrarySort sort) {
        List<Item> copy = new ArrayList<>(items);
        copy.sort((sort == null ? LibrarySort.DEFAULT : sort).comparator(ITEM_FIELDS));
        return copy;
    }

    private List<Item> collect(String filter, String term, LibrarySort order) {
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MEDIA_TYPE};
        Uri collection = MediaStore.Files.getContentUri("external");
        String type = MediaStore.Files.FileColumns.MEDIA_TYPE;
        // Unclassified files can still be movies or music. Classify those by MIME and extension.
        String types = "images".equals(filter) ? "0,6,1" : "videos".equals(filter) ? "0,6,3"
                : "audio".equals(filter) ? "0,6,2" : isFileCategory(filter) ? "0,6" : "0,1,2,3,6";
        String selection = "(" + type + " IS NULL OR " + type + " IN (" + types + "))";
        String[] args = null;
        if (!term.isEmpty()) {
            selection += " AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            // LIKE is a prefilter. The literal comparison in readPage also handles filenames containing % or _.
            args = new String[]{"%" + term + "%"};
        }
        String needle = term.toLowerCase(Locale.ROOT);
        List<Item> all = new ArrayList<>();
        synchronized (picked) {
            for (Item item : picked.values()) {
                if (matches(filter, item) && item.name.toLowerCase(Locale.ROOT).contains(needle)) all.add(item);
            }
        }
        String sqlOrder = MediaStore.MediaColumns.DATE_MODIFIED + " DESC, " + MediaStore.MediaColumns._ID + " DESC";
        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, args, sqlOrder)) {
            all.addAll(readPage(cursor, filter, 0, Integer.MAX_VALUE, term).items);
        } catch (SecurityException denied) {
            // System-picker grants remain readable when the user denies broad media access.
        }
        return sorted(all, order);
    }

    static boolean isFileCategory(String filter) {
        return "files".equals(filter) || "documents".equals(filter) || "apps".equals(filter) || "other".equals(filter);
    }

    /** True when an item belongs to the (normalized) Library filter. */
    static boolean matches(String filter, Item item) {
        return matches(filter, item.kind, item.mime, item.name);
    }

    static boolean matches(String filter, String kind, String mime, String name) {
        if ("all".equals(filter)) return true;
        String category = MediaTypeClassifier.category(mime, name, kind);
        if ("files".equals(filter)) return !"images".equals(category) && !"videos".equals(category) && !"audio".equals(category);
        return category.equals(filter);
    }

    static Page readPage(Cursor cursor, String filter, int offset, int limit, String term) {
        List<Item> items = new ArrayList<>();
        int matched = 0;
        String needle = term.toLowerCase(Locale.ROOT);
        while (cursor != null && cursor.moveToNext()) {
            String name = value(cursor, 1, "");
            if (name.isEmpty() || !name.toLowerCase(Locale.ROOT).contains(needle)) continue;
            int type = cursor.getInt(5);
            String prefix;
            Uri base;
            switch (type) {
                case MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE:
                    prefix = "image"; base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; break;
                case MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO:
                    prefix = "audio"; base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; break;
                case MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO:
                    prefix = "video"; base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; break;
                default:
                    prefix = "file"; base = MediaStore.Files.getContentUri("external"); break;
            }
            String mime = value(cursor, 2, "");
            String classified = MediaTypeClassifier.kind(MediaTypeClassifier.mimeType(name, mime), name, prefix);
            if (!matches(filter, classified, mime, name)) continue;
            if (matched++ < offset) continue;
            if (items.size() == limit) return new Page(items, matched - 1, true);
            long id = number(cursor, 0);
            items.add(new Item(prefix + ":" + id, name, mime, classified, number(cursor, 3),
                    number(cursor, 4), ContentUris.withAppendedId(base, id)));
        }
        return new Page(items, matched, false);
    }

    /** Maps singular and plural kind names onto the plural form listPage uses. */
    static String normalizeKind(String kind) {
        if (kind == null) return "all";
        String value = kind.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "all".equals(value)) return "all";
        if ("image".equals(value) || "images".equals(value) || "photo".equals(value) || "photos".equals(value)) return "images";
        if ("video".equals(value) || "videos".equals(value)) return "videos";
        if ("audio".equals(value) || "audios".equals(value) || "music".equals(value)) return "audio";
        if ("file".equals(value) || "files".equals(value)) return "files";
        if ("document".equals(value) || "documents".equals(value) || "docs".equals(value)) return "documents";
        if ("app".equals(value) || "apps".equals(value) || "apk".equals(value)) return "apps";
        if ("other".equals(value) || "others".equals(value)) return "other";
        return "all";
    }

    /** True when the app may read this media kind. Used to explain an empty library. */
    boolean granted(String kind) {
        if ("images".equals(kind)) return grantedImageOrVideo(Manifest.permission.READ_MEDIA_IMAGES);
        if ("videos".equals(kind)) return grantedImageOrVideo(Manifest.permission.READ_MEDIA_VIDEO);
        if ("audio".equals(kind)) return grantedSimple(Manifest.permission.READ_MEDIA_AUDIO);
        if (isFileCategory(kind)) return grantedDocuments();
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
        if (key.startsWith("picked:")) { synchronized (picked) { return picked.get(key); } }
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

    private static String value(Cursor cursor, int index, String fallback) {
        String value = cursor.getString(index);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static long number(Cursor cursor, int index) {
        try { return cursor.getLong(index); } catch (Exception ignored) { return 0; }
    }
}
