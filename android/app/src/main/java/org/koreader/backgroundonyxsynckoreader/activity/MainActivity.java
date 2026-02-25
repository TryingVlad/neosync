package org.koreader.backgroundonyxsynckoreader.activity;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.e(TAG, "MainActivity created - app process started");
        
        // You can close the activity immediately since this is just a background service app
        finish();
    }
}