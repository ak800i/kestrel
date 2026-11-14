package dev.kestrel.app.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Stores one file, and like some document providers it shortens that file only when the mode asks for it with "t".
 * It is written in Java because it runs in the test APK's own process, and the test APK leaves out the Kotlin standard
 * library, which the app APK holds.
 */
public class ExportFileProvider extends ContentProvider {
    private File file() {
        return new File(getContext().getFilesDir(), "export.json");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int flags;
        if (mode.contains("w")) {
            flags = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE;
            if (mode.contains("t")) flags |= ParcelFileDescriptor.MODE_TRUNCATE;
        } else {
            flags = ParcelFileDescriptor.MODE_READ_ONLY;
        }
        return ParcelFileDescriptor.open(file(), flags);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return file().delete() ? 1 : 0;
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
