package org.koreader.backgroundonyxsynckoreader.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.Manifest;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int REQ_READ_EXTERNAL_STORAGE = 1001;

    private AlertDialog currentDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showAllFilesPermissionDialog();
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestReadPermission();
        } else {
            Log.i(TAG, "Permissions already granted");
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from Settings — check if the user granted All Files Access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && currentDialog != null
                && Environment.isExternalStorageManager()) {
            Log.i(TAG, "All files access granted");
            dismissDialog();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissDialog();
    }

    private void dismissDialog() {
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        currentDialog = null;
    }

    private void showAllFilesPermissionDialog() {
        currentDialog = new AlertDialog.Builder(this)
                .setTitle("Storage permission required")
                .setMessage(
                        "This app needs access to all files to read KOReader's statistics " +
                                "database and sync your reading history to your Onyx account.\n\n" +
                                "Please enable \"Allow access to manage all files\" on the next screen.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        Log.w(TAG, "Could not open app-specific settings, falling back", e);
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    Log.w(TAG, "User declined all-files permission");
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void requestReadPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            currentDialog = new AlertDialog.Builder(this)
                    .setTitle("Storage permission required")
                    .setMessage("This app needs access to external storage to read KOReader's statistics database.")
                    .setPositiveButton("Allow", (d, w) -> ActivityCompat.requestPermissions(
                            this,
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                            REQ_READ_EXTERNAL_STORAGE))
                    .setNegativeButton("Cancel", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_READ_EXTERNAL_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ_EXTERNAL_STORAGE) {
            Log.i(TAG, grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    ? "READ_EXTERNAL_STORAGE granted" : "READ_EXTERNAL_STORAGE denied");
            finish();
        }
    }
}