package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import dev.neosync.boox.R;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.update.ProblemReport;
import dev.neosync.boox.update.UpdateChecker;

/**
 * Settings screen: account, Picco connection, book matching, and the guide.
 * Everything that is not a day-to-day action lives here instead of the home
 * screen.
 */
public class SettingsActivity extends Activity {

    private SyncSettings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SyncSettings(this);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());

        findViewById(R.id.button_account).setOnClickListener(v ->
                startActivity(new Intent(this, SignInActivity.class)));

        EditText piccoAddressField = findViewById(R.id.field_picco_address);
        piccoAddressField.setText(settings.getPiccoAddress());
        piccoAddressField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                settings.setPiccoAddress(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        RadioGroup matchMethodGroup = findViewById(R.id.group_match_method);
        matchMethodGroup.check(SyncSettings.MATCH_BINARY.equals(settings.getMatchMethod())
                ? R.id.radio_match_binary : R.id.radio_match_filename);
        matchMethodGroup.setOnCheckedChangeListener((group, id) -> settings.setMatchMethod(
                id == R.id.radio_match_binary
                        ? SyncSettings.MATCH_BINARY : SyncSettings.MATCH_FILENAME));

        findViewById(R.id.button_support).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(getString(R.string.support_url))));
            } catch (android.content.ActivityNotFoundException e) {
                // No browser on this device: the address is in the text above.
                android.widget.Toast.makeText(this, R.string.support_url,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });

        findViewById(R.id.button_report).setOnClickListener(v -> sendReport());

        findViewById(R.id.button_how_it_works).setOnClickListener(v ->
                startActivity(new Intent(this, OnboardingActivity.class)
                        .putExtra(OnboardingActivity.EXTRA_REVIEW, true)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        showVersion(new UpdateChecker(this).knownUpdate());
        // Settings is where people look for updates: ask GitHub now.
        new Thread(() -> {
            UpdateChecker.Update update = new UpdateChecker(this).check(true);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    showVersion(update);
                }
            });
        }).start();
        // Refreshed here, not in onCreate: the account may have just changed.
        ((TextView) findViewById(R.id.text_settings_account))
                .setText(getString(R.string.main_signed_in_as, settings.getUsername()));
    }

    private void showVersion(UpdateChecker.Update update) {
        String current = new UpdateChecker(this).currentVersion();
        TextView versionView = findViewById(R.id.text_version);
        android.widget.Button download = findViewById(R.id.button_download_update);
        if (update != null) {
            versionView.setText(getString(R.string.version_update, current, update.version));
            download.setText(getString(R.string.button_download_update, update.version));
            download.setVisibility(android.view.View.VISIBLE);
            download.setOnClickListener(v -> openDownload(this, update));
        } else {
            versionView.setText(getString(R.string.version_current, current));
            download.setVisibility(android.view.View.GONE);
        }
    }

    /** Opens the APK download in the browser; shared with the home banner. */
    static void openDownload(Activity activity, UpdateChecker.Update update) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(update.downloadUrl)));
            android.widget.Toast.makeText(activity, R.string.update_install_hint,
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(activity,
                    activity.getString(R.string.update_no_browser, update.downloadUrl),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Builds the scrubbed problem report and copies it to the clipboard, for
     * pasting into a Reddit comment or message, or a GitHub issue. Nothing
     * is sent anywhere by the app.
     */
    private void sendReport() {
        TextView status = findViewById(R.id.text_report_status);
        android.widget.Button button = findViewById(R.id.button_report);
        button.setEnabled(false);
        status.setText(R.string.report_building);
        String version = new UpdateChecker(this).currentVersion();
        new Thread(() -> {
            String report = ProblemReport.build(this, version);
            runOnUiThread(() -> {
                button.setEnabled(true);
                if (isFinishing()) {
                    return;
                }
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("NeoSync report", report));
                }
                status.setText(clipboard != null
                        ? R.string.report_copied : R.string.report_copy_failed);
            });
        }).start();
    }
}
