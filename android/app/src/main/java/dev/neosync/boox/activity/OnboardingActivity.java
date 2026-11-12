package dev.neosync.boox.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import dev.neosync.boox.R;
import dev.neosync.boox.kosync.SyncSettings;

/**
 * First-run guide: what the app does, the CrossPoint firmware the Picco
 * needs, the Picco-side settings, the same-file rule, and permissions.
 * Steps are swapped in place (no animations — this runs on e-ink).
 */
public class OnboardingActivity extends Activity {

    /** Set when opened from the main screen's "How it works" button. */
    static final String EXTRA_REVIEW = "review";

    private static final int[] STEPS = {
            R.id.step_1, R.id.step_2, R.id.step_3, R.id.step_4, R.id.step_5};

    private int current = 0;
    private boolean review;
    private Button backButton;
    private Button nextButton;
    private Button filesButton;
    private Button notifButton;
    private TextView counterView;
    private ScrollView scrollView;
    /** API 33+: steps back through the guide; registered only past step 1. */
    private Object backCallback;
    private boolean backCallbackRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        SystemBars.fit(this);
        review = getIntent().getBooleanExtra(EXTRA_REVIEW, false);

        backButton = findViewById(R.id.button_back);
        nextButton = findViewById(R.id.button_next);
        counterView = findViewById(R.id.text_step_counter);
        scrollView = findViewById(R.id.scroll);
        filesButton = findViewById(R.id.button_perm_files);
        notifButton = findViewById(R.id.button_perm_notif);

        backButton.setOnClickListener(v -> show(current - 1));
        nextButton.setOnClickListener(v -> {
            if (current < STEPS.length - 1) {
                show(current + 1);
            } else {
                finishOnboarding();
            }
        });
        findViewById(R.id.button_open_crosspoint).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(getString(R.string.crosspoint_url))));
            } catch (Exception ignored) {
                // No browser installed; the address is printed in the step text.
            }
        });
        filesButton.setOnClickListener(v -> Permissions.openAllFilesAccessSettings(this));
        notifButton.setOnClickListener(v -> Permissions.requestNotifications(this));

        if (savedInstanceState != null) {
            current = savedInstanceState.getInt("step", 0);
        }
        show(current);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshPermissions();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("step", current);
    }

    /** Android 12 and older; on 13+ the OnBackInvokedCallback handles back. */
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        if (current > 0) {
            show(current - 1);
        } else {
            super.onBackPressed();
        }
    }

    private void show(int step) {
        current = Math.max(0, Math.min(STEPS.length - 1, step));
        for (int i = 0; i < STEPS.length; i++) {
            findViewById(STEPS[i]).setVisibility(i == current ? View.VISIBLE : View.GONE);
        }
        counterView.setText(getString(R.string.onb_step_counter, current + 1, STEPS.length));
        backButton.setVisibility(current == 0 ? View.INVISIBLE : View.VISIBLE);
        boolean last = current == STEPS.length - 1;
        nextButton.setText(!last ? R.string.onb_next
                : review ? R.string.onb_done : R.string.onb_finish);
        scrollView.scrollTo(0, 0);
        updateBackCallback();
    }

    /**
     * On step 1 back leaves the guide (system default, with the predictive
     * animation); on later steps it goes to the previous step.
     */
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        boolean want = current > 0;
        if (want == backCallbackRegistered) {
            return;
        }
        if (backCallback == null) {
            backCallback = (OnBackInvokedCallback) () -> show(current - 1);
        }
        if (want) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backCallback);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (OnBackInvokedCallback) backCallback);
        }
        backCallbackRegistered = want;
    }

    private void refreshPermissions() {
        mark(filesButton, Permissions.hasAllFilesAccess());
        mark(notifButton, Permissions.hasNotifications(this));
    }

    private void mark(Button button, boolean granted) {
        button.setEnabled(!granted);
        button.setText(granted ? R.string.onb5_granted : R.string.onb5_grant);
    }

    private void finishOnboarding() {
        SyncSettings settings = new SyncSettings(this);
        settings.setOnboarded();
        if (!review) {
            startActivity(new Intent(this, settings.hasCredentials()
                    ? MainActivity.class : SignInActivity.class));
        }
        finish();
    }
}
