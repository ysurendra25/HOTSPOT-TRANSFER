package com.pocketbridge;

import android.content.Context;
import android.os.StatFs;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Creates an on-phone H.264/AAC MP4 only when a browser cannot play the original. */
final class VideoCompatibility {
    private static final long MIN_FREE_BYTES = 96L * 1024 * 1024;
    private static final long CACHE_LIMIT_BYTES = 512L * 1024 * 1024;
    private static final long CACHE_MAX_AGE_MS = TimeUnit.DAYS.toMillis(7);
    private static final long CONVERSION_TIMEOUT_MINUTES = 30;
    private final Context context;

    VideoCompatibility(Context context) { this.context = context.getApplicationContext(); }

    File makeBrowserCopy(MediaRepository.Item item) throws Exception {
        if (item == null || !"video".equals(item.kind)) throw new IOException("This item is not a video.");
        File directory = new File(context.getCacheDir(), "browser-video");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create temporary video storage.");
        String id = cacheId(item);
        File output = new File(directory, id + ".mp4");
        synchronized (output.getAbsolutePath().intern()) {
            if (output.isFile() && output.length() > 0) return output;
            pruneCache(directory, null);
            File source = new File(directory, id + ".source");
            File partial = new File(directory, id + ".partial.mp4");
            source.delete();
            partial.delete();
            ensureFreeSpace(directory, item.size);
            try {
                copySource(item, source);
                transcode(source, partial);
                if (!partial.isFile() || partial.length() < 1024) {
                    throw new IOException("The phone could not convert this video into a browser-compatible format.");
                }
                if (!partial.renameTo(output)) throw new IOException("Could not save the converted video in temporary storage.");
                pruneCache(directory, output);
                return output;
            } finally {
                source.delete();
                partial.delete();
            }
        }
    }

    private void pruneCache(File directory, File keep) {
        File[] cached = directory.listFiles((dir, name) -> name.endsWith(".mp4"));
        if (cached == null) return;
        Arrays.sort(cached, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        for (File file : cached) total += file.length();
        long cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS;
        for (File file : cached) {
            if (keep != null && file.equals(keep)) continue;
            if (file.lastModified() < cutoff || total > CACHE_LIMIT_BYTES) {
                long length = file.length();
                if (file.delete()) total -= length;
            }
        }
    }

    private void ensureFreeSpace(File directory, long sourceSize) throws IOException {
        long available = new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        long needed = Math.max(MIN_FREE_BYTES, sourceSize > 0 && sourceSize < Long.MAX_VALUE / 2 ? sourceSize * 2 : MIN_FREE_BYTES);
        if (available < needed) throw new IOException("The phone needs more free storage to make a browser-compatible video copy.");
    }

    private void copySource(MediaRepository.Item item, File target) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(item.uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("The original video is no longer available on the phone.");
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private void transcode(File source, File output) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ReturnCode> returnCode = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        String[] args = {
                "-nostdin", "-y", "-i", source.getAbsolutePath(),
                "-map", "0:v:0", "-map", "0:a:0?", "-sn",
                "-c:v", "h264_mediacodec", "-b:v", "10M",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k",
                "-movflags", "+faststart", "-f", "mp4", output.getAbsolutePath()
        };
        FFmpegSession session = FFmpegKit.executeWithArgumentsAsync(args, completed -> {
            returnCode.set(completed.getReturnCode());
            String details = completed.getFailStackTrace();
            if (details != null && !details.trim().isEmpty()) failure.set(details);
            done.countDown();
        });
        if (!done.await(CONVERSION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            FFmpegKit.cancel(session.getSessionId());
            throw new IOException("Video conversion took too long. Download the original file instead.");
        }
        if (!ReturnCode.isSuccess(returnCode.get())) {
            String detail = failure.get();
            throw new IOException(detail == null || detail.isEmpty()
                    ? "This phone could not convert the video. Download the original and play it in a media player."
                    : "This phone could not convert the video: " + detail);
        }
    }

    private String cacheId(MediaRepository.Item item) throws Exception {
        String source = item.key + ":" + item.size + ":" + item.modified;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }
}
