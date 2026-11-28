package com.pocketbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** A read-only URI grant for one completed file the phone owner chooses to open. */
public final class ReceivedFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        String id = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || id == null || !EdgeInbox.validId(id))
            throw new FileNotFoundException("Invalid file");
        File file = EdgeInbox.resolve(new File(getContext().getFilesDir(), "received"), id);
        if (file == null) throw new FileNotFoundException("File is unavailable");
        return file;
    }

    @Override public String getType(Uri uri) {
        try { return MediaTypeClassifier.mimeType(EdgeInbox.name(resolve(uri).getName()), null); }
        catch (FileNotFoundException gone) { return null; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        try {
            File file = resolve(uri);
            MatrixCursor.RowBuilder row = result.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(EdgeInbox.name(file.getName()));
                else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
                else row.add(null);
            }
        } catch (FileNotFoundException gone) { /* An empty cursor means unavailable. */ }
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
