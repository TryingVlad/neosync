package org.koreader.backgroundonyxsynckoreader.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

public class SyncReceiver extends BroadcastReceiver {
    private static final String TAG = "SyncReceiver";


    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "=== BROADCAST RECEIVED ===");

        if (intent == null) {
            Log.w(TAG, "Intent is null");
            return;
        }

        String action = intent.getAction();
        Log.i(TAG, "Action: " + action);

        if (TextUtils.isEmpty(action)) {
            Log.w(TAG, "Action is empty");
            return;
        }

        // Log extras for debugging
        if (intent.getExtras() != null) {
            for (String key : intent.getExtras().keySet()) {
                Object value = intent.getExtras().get(key);
                Log.i(TAG, "Extra: " + key + " = " + value);
            }
        }

        try {
            SyncService.onHandleWork(context, intent);
            Log.i(TAG, "Finish update");
        } catch (Exception e) {
            Log.e(TAG, "Error enqueuing work to SyncService", e);
        }
    }
}