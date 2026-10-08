package it.paolo.telegrampdfshare;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class PdfShareProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("Context non disponibile");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2) {
            throw new FileNotFoundException("Percorso non valido");
        }
        String messageFolder = segments.get(0).replaceAll("[^0-9-]", "");
        String fileName = segments.get(1).replaceAll("[\\\\/]", "_");
        File base = new File(getContext().getCacheDir(), "shared_pdf");
        File target = new File(new File(base, messageFolder), fileName);
        try {
            String basePath = base.getCanonicalPath() + File.separator;
            String targetPath = target.getCanonicalPath();
            if (!targetPath.startsWith(basePath)) {
                throw new FileNotFoundException("Percorso non consentito");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Percorso non valido");
        }
        if (!target.exists() || !target.isFile()) {
            throw new FileNotFoundException("File non trovato");
        }
        return target;
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.equals("r")) {
            throw new FileNotFoundException("Solo lettura");
        }
        return ParcelFileDescriptor.open(resolveFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveFile(uri);
            String[] columns = projection != null ? projection :
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                    row.add(file.getName());
                } else if (OpenableColumns.SIZE.equals(column)) {
                    row.add(file.length());
                } else {
                    row.add(null);
                }
            }
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Solo lettura");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
