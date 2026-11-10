package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import dev.neosync.boox.R;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.SyncActions;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.kosync.SyncStateStore;
import dev.neosync.boox.service.SyncService;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Home screen: sync status, a manual "force a direction" panel for the most
 * recently read book, and the few settings. First launch is routed through
 * the onboarding guide and the sign-in screen.
 */
public class MainActivity extends Activity {

    private SyncSettings settings;
    private OnyxMetadataRepository repository;
    private SyncStateStore stateStore;
    private RadioGroup matchMethodGroup;
    private TextView accountView;
    private TextView statusView;
    private TextView manualBookView;
    private TextView manualLocalView;
    private TextView manualRemoteView;
    private Button applyRemoteButton;
    private Button uploadLocalButton;

    // Latest book + remote progress from the last "Compare positions" run.
    private OnyxMetadataRepository.Book manualBook;
    private KoSyncClient.Progress manualRemote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SyncSettings(this);

        // Existing installs that already have an account skip the guide.
        if (!settings.isOnboarded() && !settings.hasCredentials()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        if (!settings.hasCredentials()) {
            startActivity(new Intent(this, SignInActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        accountView = findViewById(R.id.text_account);
        statusView = findViewById(R.id.text_status);

        Switch autoSyncSwitch = findViewById(R.id.switch_auto_sync);
        autoSyncSwitch.setChecked(settings.isAutoSyncEnabled());
        autoSyncSwitch.setOnCheckedChangeListener((button, checked) -> {
            settings.setAutoSyncEnabled(checked);
            if (checked) {
                SyncService.start(this);
            } else {
                SyncService.stop(this);
            }
            refreshStatus();
        });

        matchMethodGroup = findViewById(R.id.group_match_method);
        matchMethodGroup.check(SyncSettings.MATCH_BINARY.equals(settings.getMatchMethod())
                ? R.id.radio_match_binary : R.id.radio_match_filename);
        matchMethodGroup.setOnCheckedChangeListener((group, id) -> settings.setMatchMethod(
                id == R.id.radio_match_binary
                        ? SyncSettings.MATCH_BINARY : SyncSettings.MATCH_FILENAME));

        repository = new OnyxMetadataRepository(this);
        stateStore = new SyncStateStore(this);
        manualBookView = findViewById(R.id.text_manual_book);
        manualLocalView = findViewById(R.id.text_manual_local);
        manualRemoteView = findViewById(R.id.text_manual_remote);
        applyRemoteButton = findViewById(R.id.button_apply_remote);
        uploadLocalButton = findViewById(R.id.button_upload_local);
        findViewById(R.id.button_manual_check).setOnClickListener(v -> manualCheck());
        applyRemoteButton.setOnClickListener(v -> manualApplyRemote());
        uploadLocalButton.setOnClickListener(v -> manualUploadLocal());

        findViewById(R.id.button_account).setOnClickListener(v ->
                startActivity(new Intent(this, SignInActivity.class)));
        findViewById(R.id.button_how_it_works).setOnClickListener(v ->
                startActivity(new Intent(this, OnboardingActivity.class)
                        .putExtra(OnboardingActivity.EXTRA_REVIEW, true)));

        if (!Permissions.hasNotifications(this)) {
            Permissions.requestNotifications(this);
        }
        SyncService.start(this);
        manualCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) {
            refreshStatus();
        }
    }

    private void refreshStatus() {
        accountView.setText(getString(R.string.main_signed_in_as, settings.getUsername()));
        StringBuilder sb = new StringBuilder();
        if (!settings.isAutoSyncEnabled()) {
            sb.append(getString(R.string.status_auto_off)).append("\n\n");
        }
        long lastSync = settings.getLastSyncMs();
        if (lastSync > 0) {
            sb.append("Last sync: ")
                    .append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(new Date(lastSync)));
            if (!settings.getLastBook().isEmpty()) {
                sb.append("\n").append(settings.getLastBook());
            }
        } else {
            sb.append(getString(R.string.status_never_synced));
        }
        if (!settings.getLastError().isEmpty()) {
            sb.append("\n\nLast problem: ").append(settings.getLastError());
        }
        if (!Permissions.hasAllFilesAccess()) {
            sb.append("\n\nBook file access is off, so positions are less accurate. ")
                    .append("Open “How it works” to allow it.");
        }
        statusView.setText(sb.toString());
    }

    // --- Manual sync ---------------------------------------------------------

    private KoSyncClient buildClient() {
        String androidId = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID);
        return new KoSyncClient(settings, "NeoSync",
                androidId != null ? androidId : "boox-companion");
    }

    private static String time(long ms) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(ms));
    }

    /**
     * Fetches the most recently read book and its server-side progress, and
     * arms the two direction buttons. Both deliberately skip the automatic
     * newest-timestamp rule — here the user decides.
     */
    private void manualCheck() {
        manualBookView.setText(R.string.manual_checking);
        manualLocalView.setText("");
        manualRemoteView.setText("");
        applyRemoteButton.setEnabled(false);
        uploadLocalButton.setEnabled(false);
        new Thread(() -> {
            List<OnyxMetadataRepository.Book> books = repository.queryBooksWithProgress();
            if (books.isEmpty()) {
                runOnUiThread(() -> manualBookView.setText(R.string.manual_no_books));
                return;
            }
            OnyxMetadataRepository.Book book = books.get(0);
            String hash = SyncActions.documentHash(settings, book.path);
            double localPct = SyncActions.localPercentage(book);
            KoSyncClient.Result result = hash != null ? buildClient().getProgress(hash) : null;
            runOnUiThread(() -> {
                manualBook = book;
                manualRemote = (result != null && result.error == KoSyncClient.Error.OK)
                        ? result.progress : null;
                manualBookView.setText(book.name != null ? book.name : book.path);
                String local = String.format(Locale.US, "Here: %.1f%% (page %d of %d)",
                        localPct * 100, book.currentPage(), book.totalPages());
                if (book.lastAccessMs > 0) {
                    local += " · " + time(book.lastAccessMs);
                }
                manualLocalView.setText(local);
                if (manualRemote != null) {
                    manualRemoteView.setText(String.format(Locale.US, "Online: %.1f%% · from %s · %s",
                            manualRemote.percentage * 100,
                            manualRemote.device.isEmpty() ? "another device" : manualRemote.device,
                            time(manualRemote.timestampSec * 1000L)));
                } else if (result != null && result.error == KoSyncClient.Error.NOT_FOUND) {
                    manualRemoteView.setText(R.string.manual_no_remote);
                } else {
                    manualRemoteView.setText("Online: " + (result != null
                            ? KoSyncClient.errorString(result) : "cannot read the book file"));
                }
                applyRemoteButton.setEnabled(manualRemote != null && book.totalPages() > 0);
                uploadLocalButton.setEnabled(hash != null
                        && book.currentPage() > 0 && book.totalPages() > 0);
            });
        }).start();
    }

    private void manualApplyRemote() {
        final OnyxMetadataRepository.Book book = manualBook;
        final KoSyncClient.Progress remote = manualRemote;
        if (book == null || remote == null) {
            return;
        }
        applyRemoteButton.setEnabled(false);
        uploadLocalButton.setEnabled(false);
        new Thread(() -> {
            String written = SyncActions.applyRemote(this, repository, stateStore, book, remote);
            runOnUiThread(() -> {
                if (written != null) {
                    settings.recordSyncSuccess(book.name != null ? book.name : book.path);
                }
                refreshStatus();
                manualRemoteView.setText(written != null
                        ? getString(R.string.manual_apply_done)
                        : "Could not fetch: book not found in the Boox library.");
            });
        }).start();
    }

    private void manualUploadLocal() {
        final OnyxMetadataRepository.Book book = manualBook;
        if (book == null) {
            return;
        }
        applyRemoteButton.setEnabled(false);
        uploadLocalButton.setEnabled(false);
        new Thread(() -> {
            String hash = SyncActions.documentHash(settings, book.path);
            KoSyncClient.Result result = hash != null
                    ? SyncActions.uploadLocal(buildClient(), stateStore, book, hash) : null;
            boolean ok = result != null && result.error == KoSyncClient.Error.OK;
            runOnUiThread(() -> {
                if (ok) {
                    settings.recordSyncSuccess(book.name != null ? book.name : book.path);
                }
                refreshStatus();
                manualRemoteView.setText(ok
                        ? getString(R.string.manual_upload_done)
                        : "Could not send: " + (result != null
                                ? KoSyncClient.errorString(result) : "cannot read the book file"));
            });
        }).start();
    }
}
