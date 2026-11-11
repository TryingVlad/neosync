package dev.neosync.boox.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

import dev.neosync.boox.R;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.service.SyncService;
import dev.neosync.boox.transfer.CoverFixer;
import dev.neosync.boox.transfer.PiccoClient;
import dev.neosync.boox.transfer.PiccoLibraryStore;
import dev.neosync.boox.update.ProblemReport;
import dev.neosync.boox.update.UpdateChecker;

/**
 * Settings (spec 5.4, mockup 4): one row per setting with its current value,
 * grouped Sync, Permissions (5.9), Picco, Help, Advanced and About. The
 * settings that can break sync if changed by mistake (book matching, server)
 * open their own pages. Every value is re-read in onResume, since most rows
 * lead somewhere that can change it.
 */
public class SettingsActivity extends Activity {

    private SyncSettings settings;
    /** The notification prompt was refused here once: Allow opens the system page next. */
    private boolean notificationsRefused;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SyncSettings(this);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());

        // Sync
        row(R.id.row_auto_sync, R.string.autosync_label).setOnClickListener(v -> toggleAutoSync());
        sw(R.id.row_auto_sync).setVisibility(View.VISIBLE);
        value(R.id.row_auto_sync, getString(R.string.autosync_hint));
        row(R.id.row_account, R.string.settings_account).setOnClickListener(v ->
                startActivity(new Intent(this, SignInActivity.class)));
        more(R.id.row_account);

        // Permissions
        row(R.id.row_file_access, R.string.settings_file_access);
        button(R.id.row_file_access).setOnClickListener(v ->
                Permissions.openAllFilesAccessSettings(this));
        row(R.id.row_notifications, R.string.settings_notifications);
        button(R.id.row_notifications).setOnClickListener(v -> allowNotifications());

        // Picco
        row(R.id.row_picco, R.string.settings_picco).setOnClickListener(v ->
                startActivity(new Intent(this, ConnectionsActivity.class)));
        more(R.id.row_picco);
        row(R.id.row_folder, R.string.settings_folder).setOnClickListener(v -> changeFolder());
        end(R.id.row_folder, getString(R.string.settings_change));

        // Help
        row(R.id.row_setup_guide, R.string.settings_setup_guide).setOnClickListener(v ->
                startActivity(new Intent(this, OnboardingActivity.class)
                        .putExtra(OnboardingActivity.EXTRA_REVIEW, true)));
        value(R.id.row_setup_guide, getString(R.string.settings_setup_guide_sub));
        more(R.id.row_setup_guide);
        row(R.id.row_report, R.string.settings_report).setOnClickListener(v -> showReport());
        value(R.id.row_report, getString(R.string.settings_report_sub));
        more(R.id.row_report);

        // Advanced
        row(R.id.row_matching, R.string.settings_matching).setOnClickListener(v ->
                startActivity(new Intent(this, MatchingActivity.class)));
        more(R.id.row_matching);
        row(R.id.row_server, R.string.settings_server).setOnClickListener(v ->
                startActivity(new Intent(this, ServerActivity.class)));
        more(R.id.row_server);
        // Read-only information: no action, so the row isn't tappable.
        row(R.id.row_cover_backups, R.string.settings_cover_backups).setClickable(false);

        // About
        row(R.id.row_version, R.string.app_name).setClickable(false);
        row(R.id.row_support, R.string.button_support).setOnClickListener(v -> openSupport());
        value(R.id.row_support, getString(R.string.support_blurb));
        more(R.id.row_support);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
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
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == Permissions.REQ_POST_NOTIFICATIONS) {
            notificationsRefused = !Permissions.hasNotifications(this);
            render();
        }
    }

    /** Every row's current value (cheap: preferences and one small folder listing). */
    private void render() {
        showSwitch(settings.isAutoSyncEnabled());

        String user = settings.getUsername();
        value(R.id.row_account, settings.hasCredentials() && !user.isEmpty()
                ? getString(R.string.main_signed_in_as, user)
                : getString(R.string.settings_not_signed_in));

        permission(R.id.row_file_access, Permissions.hasAllFilesAccess());
        permission(R.id.row_notifications, Permissions.hasNotifications(this));

        String address = settings.getPiccoAddress();
        PiccoLibraryStore piccoList = new PiccoLibraryStore(this);
        value(R.id.row_picco, address.isEmpty()
                ? getString(R.string.settings_picco_not_set_up)
                : piccoList.hasList(address)
                ? getString(R.string.settings_picco_found, address, clock(piccoList.getUpdatedMs()))
                : getString(R.string.settings_picco_found_no_list, address));

        String folder = settings.getPiccoFolder();
        value(R.id.row_folder, folder != null
                ? MainActivity.piccoFolderLabel(this, folder)
                : getString(R.string.settings_folder_unset));

        value(R.id.row_matching, getString(
                SyncSettings.MATCH_BINARY.equals(settings.getMatchMethod())
                        ? R.string.match_binary : R.string.match_filename));
        value(R.id.row_server, ServerActivity.serverLabel(settings));

        File backups = CoverFixer.backupDir();
        String shown = backups.getParentFile().getName() + "/" + backups.getName();
        File[] files = backups.listFiles();
        int count = files != null ? files.length : 0;
        // Without file access the folder can't be read: show only where it is.
        value(R.id.row_cover_backups, files == null && !Permissions.hasAllFilesAccess() ? shown
                : getResources().getQuantityString(
                        R.plurals.settings_cover_backups_value, count, shown, count));
    }

    private void permission(int rowId, boolean allowed) {
        value(rowId, getString(allowed ? R.string.settings_allowed : R.string.settings_not_allowed));
        Button allow = button(rowId);
        allow.setText(R.string.button_allow);
        allow.setVisibility(allowed ? View.GONE : View.VISIBLE);
    }

    private void toggleAutoSync() {
        boolean on = !settings.isAutoSyncEnabled();
        settings.setAutoSyncEnabled(on);
        if (on) {
            SyncService.start(this);
        } else {
            SyncService.stop(this);
        }
        showSwitch(on);
    }

    /** Moves the switch without its sliding animation (e-ink). */
    private void showSwitch(boolean on) {
        Switch view = sw(R.id.row_auto_sync);
        if (view.isChecked() != on) {
            view.setChecked(on);
            view.jumpDrawablesToCurrentState();
        }
    }

    /**
     * The system prompt the first time; once refused (Android then stops
     * showing it), the app's notification page.
     */
    private void allowNotifications() {
        if (notificationsRefused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                return;
            } catch (ActivityNotFoundException ignored) {
                // fall back to the prompt
            }
        }
        Permissions.requestNotifications(this);
    }

    /**
     * Lists the Picco's folders and asks again. When the Picco can't be
     * reached, forgets the choice instead, so the next send asks (as the old
     * "Choose again" did).
     */
    private void changeFolder() {
        String address = settings.getPiccoAddress();
        View row = findViewById(R.id.row_folder);
        row.setEnabled(false);
        new Thread(() -> {
            List<String> folders = null;
            if (!address.isEmpty()) {
                try {
                    folders = new PiccoClient(address).listFolders();
                } catch (Exception ignored) {
                    // unreachable: handled below
                }
            }
            final List<String> found = folders;
            runOnUiThread(() -> {
                row.setEnabled(true);
                if (isFinishing()) {
                    return;
                }
                if (found == null) {
                    settings.setPiccoFolder(null);
                    Toast.makeText(this, R.string.settings_folder_offline, Toast.LENGTH_LONG).show();
                    render();
                    return;
                }
                PiccoFolderChooser.ask(this, found, f -> render(), f -> { });
            });
        }).start();
    }

    private void showVersion(UpdateChecker.Update update) {
        String current = new UpdateChecker(this).currentVersion();
        title(R.id.row_version, getString(R.string.settings_version, current));
        Button download = findViewById(R.id.button_download_update);
        if (update != null) {
            value(R.id.row_version, getString(R.string.settings_version_update, update.version));
            download.setText(getString(R.string.button_download_update, update.version));
            download.setVisibility(View.VISIBLE);
            download.setOnClickListener(v -> openDownload(this, update));
        } else {
            value(R.id.row_version, getString(R.string.settings_version_current));
            download.setVisibility(View.GONE);
        }
    }

    /** Opens the APK download in the browser; shared with the home banner. */
    static void openDownload(Activity activity, UpdateChecker.Update update) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)));
            Toast.makeText(activity, R.string.update_install_hint, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, activity.getString(R.string.update_no_browser,
                    update.downloadUrl), Toast.LENGTH_LONG).show();
        }
    }

    private void openSupport() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.support_url))));
        } catch (ActivityNotFoundException e) {
            // No browser on this device: the address is in the row's text.
            Toast.makeText(this, R.string.support_url, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Report a problem: today's explanation and the copy action, in a small
     * dialog that stays open to say whether the copy worked. Nothing is sent
     * anywhere by the app.
     */
    private void showReport() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_report)
                .setMessage(R.string.report_hint)
                .setPositiveButton(R.string.button_report, null)
                .setNegativeButton(R.string.settings_close, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button copy = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            copy.setOnClickListener(v -> copyReport(dialog, copy));
        });
        dialog.show();
    }

    private void copyReport(AlertDialog dialog, Button copy) {
        copy.setEnabled(false);
        dialog.setMessage(getString(R.string.report_building));
        String version = new UpdateChecker(this).currentVersion();
        new Thread(() -> {
            String report = ProblemReport.build(this, version);
            runOnUiThread(() -> {
                copy.setEnabled(true);
                if (isFinishing() || !dialog.isShowing()) {
                    return;
                }
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("NeoSync report", report));
                }
                dialog.setMessage(getString(clipboard != null
                        ? R.string.report_copied : R.string.report_copy_failed));
            });
        }).start();
    }

    // --- Rows (item_setting) ------------------------------------------------------

    /** Sets a row's title and returns its tappable part. */
    private View row(int rowId, int titleRes) {
        title(rowId, getString(titleRes));
        return findViewById(rowId).findViewById(R.id.setting_row);
    }

    private void title(int rowId, String text) {
        setText(findViewById(rowId).findViewById(R.id.setting_title), text);
    }

    private void value(int rowId, String text) {
        TextView view = findViewById(rowId).findViewById(R.id.setting_value);
        setText(view, text);
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(View.VISIBLE);
        }
    }

    private void more(int rowId) {
        end(rowId, getString(R.string.settings_more));
    }

    private void end(int rowId, String text) {
        TextView view = findViewById(rowId).findViewById(R.id.setting_end);
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }

    private Switch sw(int rowId) {
        return findViewById(rowId).findViewById(R.id.setting_switch);
    }

    private Button button(int rowId) {
        return findViewById(rowId).findViewById(R.id.setting_button);
    }

    /** Sets text only when it differs, so an unchanged view isn't redrawn (e-ink). */
    private static void setText(TextView view, CharSequence text) {
        if (!android.text.TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    /** "13:37" today, else a short date and time. */
    private String clock(long ms) {
        if (DateUtils.isToday(ms)) {
            return DateFormat.getTimeFormat(this).format(new java.util.Date(ms));
        }
        return DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME);
    }
}
