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

/** Grants the selected camera access to one temporary image, never to plan backups. */
public final class PlanCaptureProvider extends ContentProvider {
    public static final Uri URI=Uri.parse("content://at.andi.magnetrute.capture/capture.jpg");
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return URI.equals(uri)?"image/jpeg":null; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if (!URI.equals(uri) || getContext()==null) throw new FileNotFoundException("Unknown image");
        int flags;
        if ("r".equals(mode)) flags=ParcelFileDescriptor.MODE_READ_ONLY;
        else if ("w".equals(mode) || "wt".equals(mode)) flags=ParcelFileDescriptor.MODE_WRITE_ONLY
            |ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE;
        else if ("rw".equals(mode)) flags=ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_CREATE;
        else if ("rwt".equals(mode)) flags=ParcelFileDescriptor.MODE_READ_WRITE
            |ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE;
        else throw new FileNotFoundException("Unsupported mode");
        return ParcelFileDescriptor.open(new File(getContext().getCacheDir(),"capture.jpg"),flags);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        if (!URI.equals(uri) || getContext()==null) return null;
        MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
        c.addRow(new Object[]{"capture.jpg",new File(getContext().getCacheDir(),"capture.jpg").length()});
        return c;
    }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
}
