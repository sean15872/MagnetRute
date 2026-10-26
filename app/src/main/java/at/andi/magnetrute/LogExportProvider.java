package at.andi.magnetrute;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Nur die eigens erzeugte ZIP im privaten Cache wird lesend freigegeben. */
public class LogExportProvider extends ContentProvider {
    public static final String AUTHORITY = "at.andi.magnetrute.export";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/MagnetRute_Log.zip");

    private File allowedFile(Uri uri) throws FileNotFoundException {
        if (getContext() == null || !URI.equals(uri)) throw new FileNotFoundException("Unknown URI");
        return new File(getContext().getCacheDir(), "MagnetRute_Log.zip");
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return URI.equals(uri) ? "application/zip" : null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File f = allowedFile(uri);
            MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{f.getName(), f.length()});
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        File file = allowedFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String sel, String[] args) { return 0; }
}
