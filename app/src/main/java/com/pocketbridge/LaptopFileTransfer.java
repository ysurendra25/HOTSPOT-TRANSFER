package com.pocketbridge;

import android.content.Context;
import android.os.storage.StorageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/** Streams a selected laptop file into the phone's private received inbox. */
final class LaptopFileTransfer {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final int BUFFER_SIZE = 1024 * 1024;

    private LaptopFileTransfer() { }

    static File download(Context context, String url, File inbox, String requestedName, long listedSize) throws IOException {
        return download(context, url, inbox, requestedName, listedSize, null);
    }

    static File download(Context context, String url, File inbox, String requestedName, long listedSize,
                         DownloadPublisher.Progress progress) throws IOException {
        if (!inbox.exists() && !inbox.mkdirs()) throw new IOException("Could not create the phone's receive folder.");
        String name = safeName(requestedName);
        File target = new File(inbox, System.currentTimeMillis() + "-" + name);
        File partial = new File(inbox, target.getName() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                String detail = HelperClient.readText(connection.getErrorStream() == null
                        ? new java.io.ByteArrayInputStream(new byte[0]) : connection.getErrorStream(), 4096);
                throw new IOException(status == 404
                        ? "The laptop file moved or was deleted. Reload laptop files and try again."
                        : "The laptop could not send this file (HTTP " + status + "). " + detail.trim());
            }
            long contentLength = connection.getContentLengthLong();
            if (listedSize >= 0 && contentLength >= 0 && listedSize != contentLength) {
                throw new IOException("The file changed after it was listed. Reload laptop files before downloading.");
            }
            long expected = contentLength >= 0 ? contentLength : listedSize;
            prepareStorage(context, inbox, expected);
            long copied = 0;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    copied += read;
                    if (progress != null) progress.update(copied, expected);
                    if (expected >= 0 && copied > expected) throw new IOException("The laptop sent more bytes than the file listing reported.");
                }
                output.getFD().sync();
            }
            if (expected >= 0 && copied != expected) {
                throw new IOException("Download ended early (" + copied + " of " + expected + " bytes). Try again.");
            }
            if (!partial.renameTo(target)) throw new IOException("The file downloaded but could not be saved in the phone inbox.");
            return target;
        } catch (IOException failure) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            throw failure;
        } finally {
            if (connection != null) connection.disconnect();
        }
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
