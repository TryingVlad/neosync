package org.koreader.backgroundonyxsynckoreader.helper;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class SyncService extends JobIntentService {
    private static final String TAG = "SyncService";
    private static final int JOB_ID = 1000; // Must match the one in SyncReceiver

    public static final String ACTION_SINGLE = "org.koreader.onyx.SYNC_PROGRESS";
    public static final String ACTION_BULK = "org.koreader.onyx.BULK_SYNC";
    public static final String EXTRA_BOOKS_JSON = "bookData"; // Match what Lua sends

    // Static method to enqueue work
    public static void enqueueWork(Context context, Intent work) {
        Log.i(TAG, "Enqueuing work with action: " + work.getAction());
        enqueueWork(context, SyncService.class, JOB_ID, work);
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        Log.i(TAG, "=== onHandleWork called ===");
        Log.i(TAG, "Intent: " + intent);

        if (intent == null) {
            Log.w(TAG, "Intent is null in onHandleWork");
            return;
        }

        String action = intent.getAction();
        Log.i(TAG, "Handling action: " + action);

        // Log all extras
        if (intent.getExtras() != null) {
            Log.i(TAG, "Intent extras:");
            for (String key : intent.getExtras().keySet()) {
                Object value = intent.getExtras().get(key);
                Log.i(TAG, "  " + key + " = " + value);
            }
        }

        if (ACTION_SINGLE.equals(action)) {
            handleSingleSync(intent);
        } else if (ACTION_BULK.equals(action)) {
            handleBulkSync(intent);
        } else {
            Log.w(TAG, "Unknown action: " + action);
        }
    }

    private void handleSingleSync(Intent intent) {
        Log.i(TAG, "=== handleSingleSync ===");

        String path = intent.getStringExtra("path");
        String progress = intent.getStringExtra("progress");
        Long timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis());
        int status = intent.getIntExtra("readingStatus", 1);
        if (status == 0) {
            timestamp = null;
            progress = null;
        }
        Log.i(TAG, "Single sync details:");
        Log.i(TAG, "  Path: " + path);
        Log.i(TAG, "  Progress: " + progress);
        Log.i(TAG, "  Timestamp: " + timestamp);
        Log.i(TAG, "  Status: " + status);

        if (TextUtils.isEmpty(path)) {
            Log.w(TAG, "Single sync with empty path");
            return;
        }

        try {
            boolean ok = OnyxContentProvider.getInstance().syncProgress(path, progress, timestamp, status);
            Log.i(TAG, "Single sync " + path + " -> " + (ok ? "OK" : "FAIL"));
        } catch (Exception e) {
            Log.e(TAG, "Error in single sync", e);
        }
    }

    private void handleBulkSync(Intent intent) {
        Log.i(TAG, "=== handleBulkSync ===");

        String jsonStr = intent.getStringExtra(EXTRA_BOOKS_JSON);
        Log.i(TAG, "Bulk JSON data: " + jsonStr);

        if (TextUtils.isEmpty(jsonStr)) {
            Log.w(TAG, "Bulk sync with no JSON data");
            return;
        }

        List<OnyxContentProvider.BookData> books = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(jsonStr);
            Log.i(TAG, "Parsing " + array.length() + " books from JSON");

            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                OnyxContentProvider.BookData book = new OnyxContentProvider.BookData();
                book.path = obj.optString("path");
                book.progress = obj.optString("progress");
                book.timestamp = obj.optLong("timestamp", System.currentTimeMillis());
                book.readingStatus = obj.optInt("readingStatus", 1);

                if (!TextUtils.isEmpty(book.path)) {
                    books.add(book);
                    Log.i(TAG, "Added book: " + book.path + " (" + book.progress + ")");
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Bulk sync JSON parse error", e);
            return;
        }

        try {
            int updated = OnyxContentProvider.getInstance().batchSync(books);
            Log.i(TAG, "Bulk sync updated " + updated + " of " + books.size() + " books");
        } catch (Exception e) {
            Log.e(TAG, "Error in bulk sync", e);
        }
    }
}