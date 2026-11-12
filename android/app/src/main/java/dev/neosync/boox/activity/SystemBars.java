package dev.neosync.boox.activity;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

/**
 * Keeps screens clear of the status bar, navigation bar and keyboard once
 * Android draws apps edge to edge (forced from Android 15 when targeting
 * API 35+).
 *
 * The layouts' roots use android:fitsSystemWindows, which under edge to edge
 * replaces the root's own padding with the system-bar sizes; on a Boox those
 * are zero at the sides, so the screen margins disappeared. Here the bar and
 * keyboard sizes are added on top of the padding from the layout instead.
 * Older Android versions keep the plain fitsSystemWindows behaviour.
 */
final class SystemBars {

    private SystemBars() {
    }

    /** Call right after setContentView. */
    static void fit(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) {
            return;
        }
        View root = content.getChildAt(0);
        root.setFitsSystemWindows(false);
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            // The keyboard replaces adjustResize, which edge to edge no longer does.
            int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            v.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    bottom + Math.max(bars.bottom, ime));
            return insets;
        });
        root.requestApplyInsets();
    }
}
