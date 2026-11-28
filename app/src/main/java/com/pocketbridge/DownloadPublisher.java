package com.pocketbridge;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Puts a received file into the phone's system Downloads, so it shows in the Files
 * app and the Downloads list, and keeps the user informed with a progress
 * notification while it downloads and saves.
 *
 * Android 10+ writes through MediaStore Downloads (Download/PocketBridge) with no
 * storage permission. Android 8 and 9 save into this app's Downloads folder and
 * register the file with DownloadManager, which lists it in the Downloads app.
 *
 * The phone's own HTTP client fetches the file rather than DownloadManager: the
 * system downloader binds to the default network (often mobile data) and cannot
 * reach a laptop on this phone's hotspot.
 */
final class DownloadPublisher {
    static final String CHANNEL_ID = "pocketbridge_downloads";
    static final String SUBFOLDER = "PocketBridge";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(4100);

    interface Progress { void update(long done, long total); }

    private DownloadPublisher() { }

    /** Download/PocketBridge, the MediaStore relative path used on Android 10+. */
    static String relativePath() {
        return Environment.DIRECTORY_DOWNLOADS + "/" + SUBFOLDER;
    }

    /** Copies {@code source} into system Downloads and returns the new item's content URI. */
    static Uri publish(Context context, File source, String name, String mime, Progress progress) throws IOException {
        String displayName = LaptopFileTransfer.safeName(name);
        String type = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
        if (Build.VERSION.SDK_INT >= 29) return publishMediaStore(context, source, displayName, type, progress);
        return publishLegacy(context, source, displayName, type, progress);
    }

    private static Uri publishMediaStore(Context context, File source, String name, String mime, Progress progress) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("MediaStore Downloads needs Android 10.");
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath());
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) throw new IOException("Android did not create a Downloads entry for " + name + ".");
        try {
            boolean copied = false;
            // A file descriptor lets the copy run channel to channel (no Java buffer).
            try (android.os.ParcelFileDescriptor descriptor = resolver.openFileDescriptor(item, "w")) {
                if (descriptor != null) {
                    try (FileInputStream input = new FileInputStream(source);
                         FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
                        copyChannel(input.getChannel(), output.getChannel(), source.length(), progress);
                        output.getFD().sync();
                    }
                    copied = true;
                }
            } catch (java.io.FileNotFoundException | UnsupportedOperationException noDescriptor) {
                // Some providers only offer a stream; use it below.
            }
            if (!copied) {
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = resolver.openOutputStream(item, "w")) {
                    if (output == null) throw new IOException("Android did not open the Downloads entry.");
                    copy(input, output, source.length(), progress);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, done, null, null);
            return item;
        } catch (IOException | RuntimeException failure) {
            try { resolver.delete(item, null, null); } catch (Exception ignored) { }
            throw failure instanceof IOException ? (IOException) failure : new IOException(failure.getMessage(), failure);
        }
    }

    @SuppressWarnings("deprecation") // addCompletedDownload is the API 26-28 way to list a file in Downloads.
    private static Uri publishLegacy(Context context, File source, String name, String mime, Progress progress) throws IOException {
        File folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (folder == null) throw new IOException("Shared storage is not available right now.");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Could not create the Downloads folder.");
        File target = uniqueFile(folder, name);
        try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            copyChannel(input.getChannel(), output.getChannel(), source.length(), progress);
        }
        DownloadManager downloads = context.getSystemService(DownloadManager.class);
        if (downloads == null) return Uri.fromFile(target);
        long id = downloads.addCompletedDownload(name, "Saved by PocketBridge", true, mime,
                target.getAbsolutePath(), target.length(), false);
        Uri uri = downloads.getUriForDownloadedFile(id);
        return uri == null ? Uri.fromFile(target) : uri;
    }

    static File uniqueFile(File folder, String name) {
        File candidate = new File(folder, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int counter = 2; candidate.exists(); counter++) candidate = new File(folder, base + " (" + counter + ")" + extension);
        return candidate;
    }

    /** FileChannel copy in 8 MiB steps (the kernel moves the bytes), with progress after each step. */
    static long copyChannel(java.nio.channels.FileChannel from, java.nio.channels.FileChannel to, long total,
                            Progress progress) throws IOException {
        long done = 0;
        int stalls = 0;
        while (done < total) {
            long moved = from.transferTo(done, Math.min(8L * 1024 * 1024, total - done), to);
            if (moved <= 0) {
                if (from.size() <= done || ++stalls > 100) throw new IOException("The file got shorter while it was being saved.");
                continue;
            }
            stalls = 0;
            done += moved;
            if (progress != null) progress.update(done, total);
        }
        return done;
    }

    private static void copy(InputStream input, OutputStream output, long total, Progress progress) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        long done = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            done += read;
            if (progress != null) progress.update(done, total);
        }
        output.flush();
    }

    // ---------------------------------------------------------------- notifications

    /** One progress notification per file: downloading, saving, then done or failed. */
    static final class Notifier implements Progress {
        private final Context app;
        private final NotificationManager manager;
        private final int id = NEXT_ID.getAndIncrement();
        private final String name;
        private String stage = "Downloading";
        private long lastUpdate;

        Notifier(Context context, String name) {
            this.app = context.getApplicationContext();
            this.manager = app.getSystemService(NotificationManager.class);
            this.name = name;
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Progress of files saved from the laptop into Downloads.");
                manager.createNotificationChannel(channel);
            }
        }

        Notifier stage(String value) { stage = value; lastUpdate = 0; update(0, -1); return this; }

        @Override public void update(long done, long total) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastUpdate < 500 && (total <= 0 || done < total)) return;
            lastUpdate = now;
            int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
            post(builder()
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(stage + " " + name)
                    .setContentText(total > 0 ? percent + "%" : "")
                    .setProgress(100, percent, total <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true));
        }

        void done(Uri file, String mime) {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(file, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent open = PendingIntent.getActivity(app, id, view,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            post(builder()
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(name)
                    .setContentText("Saved to " + (Build.VERSION.SDK_INT >= 29 ? relativePath() : "Downloads"))
                    .setContentIntent(open)
                    .setAutoCancel(true));
        }

        void failed(String message) {
            post(builder()
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Could not save " + name)
                    .setContentText(message)
                    .setAutoCancel(true));
        }

        private Notification.Builder builder() {
            return new Notification.Builder(app, CHANNEL_ID);
        }

        private void post(Notification.Builder builder) {
            if (manager == null) return;
            // Without the notification permission (Android 13+) the toast still reports the result.
            try { manager.notify(id, builder.build()); } catch (SecurityException denied) { /* not allowed */ }
        }
    }
}
