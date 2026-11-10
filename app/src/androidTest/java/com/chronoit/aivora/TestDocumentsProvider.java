package com.chronoit.aivora;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Standalone test-APK component: Android/JDK only, since target-APK Kotlin runtime is not visible here. */
public final class TestDocumentsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.chronoit.aivora.test.documents";
    private File directory() {
        File result = new File(getContext().getFilesDir(), "document-fixtures");
        result.mkdirs();
        return result;
    }
    private void reset() {
        for (String id : new String[]{"report", "notes"}) {
            try (FileOutputStream output = new FileOutputStream(new File(directory(), id))) {
                output.write(("AIVORA " + id + " fixture").getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) { throw new IllegalStateException(error); }
        }
    }
    @Override public boolean onCreate() { reset(); return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("grant".equals(method)) getContext().grantUriPermission("com.chronoit.aivora",
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root"), Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if ("remove-report".equals(method)) new File(directory(), "report").delete();
        if ("reset".equals(method)) reset();
        return new Bundle();
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection != null ? projection : new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        MatrixCursor cursor = new MatrixCursor(columns);
        String id = DocumentsContract.getDocumentId(uri);
        String[] ids = "children".equals(uri.getLastPathSegment()) ?
            ("root".equals(id) ? new String[]{"report", "folder", "root"} : new String[]{"notes"}) : new String[]{id};
        for (String item : ids) {
            boolean file = "report".equals(item) || "notes".equals(item);
            File content = new File(directory(), item);
            if (file && !content.exists()) continue;
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                String column = columns[i];
                if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) row[i] = item;
                else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) row[i] = "report".equals(item) ? "FinalReport.pdf" : "notes".equals(item) ? "SessionNotes.txt" : item;
                else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) row[i] = "report".equals(item) ? "application/pdf" : "notes".equals(item) ? "text/plain" : DocumentsContract.Document.MIME_TYPE_DIR;
                else if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) row[i] = file ? content.length() : 0L;
                else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) row[i] = file ? content.lastModified() : 0L;
            }
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = DocumentsContract.getDocumentId(uri);
        if (!"r".equals(mode) || !("report".equals(id) || "notes".equals(id))) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(directory(), id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
