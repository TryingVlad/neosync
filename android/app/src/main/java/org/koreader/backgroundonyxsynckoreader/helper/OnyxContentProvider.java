package org.koreader.backgroundonyxsynckoreader.helper;

import android.app.Application;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.List;

public class OnyxContentProvider {

    private static final String TAG = "OnyxContentProvider";
    private static final Uri METADATA_URI = Uri.parse(
            "content://com.onyx.content.database.ContentProvider/Metadata"
    );

    private static OnyxContentProvider instance;
    private final ContentResolver resolver;

    private OnyxContentProvider() {
        this.resolver = getAppContext().getContentResolver();
    }

    public static OnyxContentProvider getInstance() {
        if (instance == null) {
            instance = new OnyxContentProvider();
        }
        return instance;
    }

    private static Context getAppContext() {
        try {
            return (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("Could not get application context", e);
        }
    }

    public boolean syncProgress(String path, String progress, Long timestamp, int readingStatus) {
        Log.d(TAG, "syncProgress: " + path + " -> " + progress + " status=" + readingStatus);
        try {
            if (path == null) return false;
            int rows = blindUpdate(path, progress, timestamp, readingStatus);
            if (rows > 0) {
                Log.i(TAG, "Updated " + rows + " row(s)");
                return true;
            }
            Log.w(TAG, "No rows updated - book not in Onyx database: " + path);
            return false;
        } catch (Throwable e) {
            Log.e(TAG, "syncProgress error: " + e.getMessage(), e);
            return false;
        }
    }
    public int batchSync(List<BookData> books) {
        int updated = 0;
        try (ContentProviderClient client = resolver.acquireUnstableContentProviderClient(METADATA_URI)) {
            if (client == null) {
                Log.e(TAG, "batchSync: could not acquire ContentProviderClient");
                return 0;
            }
            for (BookData book : books) {
                int rows = client.update(
                        METADATA_URI,
                        book.toContentValues(),
                        "nativeAbsolutePath = ?",
                        new String[]{book.path}
                );
                if (rows > 0) updated++;
            }
        } catch (Exception e) {
            Log.e(TAG, "batchSync error", e);
        }
        return updated;
    }
    private int blindUpdate(String path, String progress, Long timestamp, int readingStatus) {
        try (ContentProviderClient client = resolver.acquireUnstableContentProviderClient(METADATA_URI)) {
            if (client == null) {
                Log.w(TAG, "blindUpdate: could not acquire ContentProviderClient");
                return -1;
            }

            ContentValues values = new ContentValues();
            values.put("progress", progress);
            values.put("readingStatus", readingStatus);
            values.put("lastAccess", timestamp);

            int rows = client.update(METADATA_URI, values, "nativeAbsolutePath = ?", new String[]{path});
            Log.d(TAG, "blindUpdate: " + rows + " row(s)");
            return rows;
        } catch (Exception e) {
            Log.w(TAG, "blindUpdate failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Debug method
     */
    public void logAllBooks() {
        ContentProviderClient client = null;
        Cursor cursor = null;
        try {
            client = resolver.acquireUnstableContentProviderClient(METADATA_URI);
            if (client == null) {
                Log.w(TAG, "logAllBooks: could not acquire ContentProviderClient");
                return;
            }
            cursor = client.query(
                    METADATA_URI,
                    new String[]{"id", "name", "nativeAbsolutePath"},
                    null, null, null
            );
            if (cursor == null) {
                Log.w(TAG, "logAllBooks: cursor is null");
                return;
            }
            Log.i(TAG, "logAllBooks: " + cursor.getCount() + " row(s) found");
            while (cursor.moveToNext()) {
                String id   = cursor.getString(0);
                String name = cursor.getString(1);
                String p    = cursor.getString(2);
                Log.i(TAG, "  [" + id + "] " + name + " -> " + p);
            }
        } catch (Exception e) {
            Log.w(TAG, "logAllBooks error: " + e.getMessage());
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Exception ignored) {}
            if (client != null) client.close();
        }
    }

    public static class BookData {
        public String path;
        public String progress;
        public long timestamp;
        public int readingStatus;

        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put("readingStatus", readingStatus);
            if (readingStatus != 0) {
                values.put("progress", progress);
                values.put("lastAccess", timestamp);
            } else {
                values.putNull("progress");
                values.putNull("lastAccess");
            }
            return values;
        }
    }
}