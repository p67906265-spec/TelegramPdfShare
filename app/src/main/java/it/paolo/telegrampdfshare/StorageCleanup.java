package it.paolo.telegrampdfshare;

import android.content.Context;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.io.IOException;

final class StorageCleanup {

    private static final String PREFS = "telegram_pdf_share_prefs";
    private static final String KEY_STORAGE_CLEANUP_V14 = "storage_cleanup_v14_done";

    private StorageCleanup() {}

    /**
     * One-time migration for v0.14: removes TDLib's previously downloaded media files.
     * The TDLib database/authentication files are kept intact, so Telegram does not need
     * to be configured again. Only the media download directory is purged.
     */
    static void runV14Migration(Context context) {
        if (context == null) return;
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_STORAGE_CLEANUP_V14, false)) {
            return;
        }

        File tdFiles = new File(new File(context.getFilesDir(), "tdlib"), "files");
        deleteChildren(tdFiles);
        deleteTree(new File(context.getCacheDir(), "shared_pdf"));

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_STORAGE_CLEANUP_V14, true)
                .apply();
    }

    /**
     * Releases a Telegram download after the app has made its own short-lived sharing copy.
     * DeleteFile keeps TDLib's local state consistent; direct path cleanup is only a fallback.
     */
    static void releaseTdlibDownload(Context context, Client client, int fileId, String path) {
        if (client != null && fileId != 0) {
            try {
                client.send(new TdApi.DeleteFile(fileId), object -> {
                    if (object instanceof TdApi.Error) deleteTdlibDownloadedFile(context, path);
                });
                return;
            } catch (Exception ignored) {
            }
        }
        deleteTdlibDownloadedFile(context, path);
    }

    private static void deleteTdlibDownloadedFile(Context context, String path) {
        if (context == null || path == null || path.trim().isEmpty()) return;
        try {
            File root = new File(new File(context.getFilesDir(), "tdlib"), "files").getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            String rootPath = root.getPath() + File.separator;
            if (!file.getPath().startsWith(rootPath)) return;

            if (file.exists()) file.delete();
            removeEmptyParents(file.getParentFile(), root);
        } catch (IOException ignored) {
        }
    }

    private static void removeEmptyParents(File dir, File stopAt) throws IOException {
        File stop = stopAt.getCanonicalFile();
        File current = dir;
        while (current != null) {
            File canonical = current.getCanonicalFile();
            if (canonical.equals(stop)) break;
            File[] children = canonical.listFiles();
            if (children == null || children.length != 0) break;
            if (!canonical.delete()) break;
            current = canonical.getParentFile();
        }
    }

    private static void deleteChildren(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) deleteTree(child);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        try { file.delete(); } catch (Exception ignored) {}
    }
}
