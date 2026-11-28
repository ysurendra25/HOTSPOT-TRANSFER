package com.pocketbridge;

import android.content.Context;
import android.os.storage.StorageManager;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Downloads a selected laptop file into the phone's private received inbox: parallel ranges, resumable. */
final class LaptopFileTransfer {
    private LaptopFileTransfer() { }

    static File download(Context context, String url, File inbox, String requestedName, long listedSize) throws IOException {
        return download(context, url, inbox, requestedName, listedSize, null);
    }

    static File download(Context context, String url, File inbox, String requestedName, long listedSize,
                         DownloadPublisher.Progress progress) throws IOException {
        if (!inbox.exists() && !inbox.mkdirs()) throw new IOException("Could not create the phone's receive folder.");
        String name = safeName(requestedName);
        // A stable, hidden partial name: trying the same file again resumes it
        // (RangedDownloader keeps a manifest beside it) instead of starting over.
        File partial = partialFile(inbox, url, listedSize, name);
        RangedDownloader downloader = new RangedDownloader(url, partial, listedSize,
                progress == null ? null : progress::update);
        if (listedSize > 0 && !partial.isFile()) prepareStorage(context, inbox, listedSize);
        RangedDownloader.Result result;
        try {
            result = downloader.download();
        } catch (RangedDownloader.SizeChanged changed) {
            downloader.discard();
            throw new IOException(changed.getMessage(), changed);
        } catch (IOException failure) {
            // Keep the partial file and its manifest for the next attempt, unless the
            // server cannot resume (no ranges), where RangedDownloader already removed it.
            throw failure;
        }
        File target = new File(inbox, System.currentTimeMillis() + "-" + name);
        if (!result.file.renameTo(target)) {
            downloader.discard();
            throw new IOException("The file downloaded but could not be saved in the phone inbox.");
        }
        //noinspection ResultOfMethodCallIgnored
        downloader.manifestFile().delete();
        return target;
    }

    /** ".pb-<hash>-<name>.part" from the URL without its query (tokens change), the size and the name. */
    static File partialFile(File inbox, String url, long listedSize, String name) {
        String stable = url.replaceAll("[?#].*$", "") + "\n" + listedSize + "\n" + name;
        String key = UploadSessions.sessionId(stable).substring(0, 16);
        String shortName = name.length() > 80 ? name.substring(name.length() - 80) : name;
        return new File(inbox, ".pb-" + key + "-" + shortName + ".part");
    }

    /** Ask Android to free or reserve cached storage before a large transfer. */
    private static void prepareStorage(Context context, File inbox, long expected) throws IOException {
        if (expected <= 0 || context == null) return;
        StorageManager storage = context.getSystemService(StorageManager.class);
        if (storage == null) return;
        try {
            UUID volume = storage.getUuidForPath(inbox);
            if (storage.getAllocatableBytes(volume) < expected) {
                throw new IOException("There is not enough free storage on the phone for this file.");
            }
            storage.allocateBytes(volume, expected);
        } catch (IOException insufficient) {
            throw new IOException("There is not enough free storage on the phone for this file.", insufficient);
        } catch (Exception unsupportedVolume) {
            // Continue with the stream; a real write failure is caught below and
            // the partial file is deleted before the user sees a failure.
        }
    }

    static String safeName(String raw) {
        String value = raw == null ? "shared-file" : raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return value.isEmpty() || value.equals(".") || value.equals("..") ? "shared-file" : value;
    }
}
