package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import dev.neosync.boox.R;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.SyncActions;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.kosync.SyncStateStore;
import dev.neosync.boox.service.SyncService;
import dev.neosync.boox.transfer.PiccoClient;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home screen: the sync status at a glance and a manual "force a direction"
 * panel for the most recently read book. Configuration lives on the settings
 * screen behind the gear icon. First launch is routed through the onboarding
 * guide and the sign-in screen.
 */
public class MainActivity extends Activity {

    private SyncSettings settings;
    private OnyxMetadataRepository repository;
    private SyncStateStore stateStore;
    private TextView statusHeadlineView;
    private TextView statusView;
    private View statusDot;
    private TextView manualBookView;
    private TextView manualLocalView;
    private TextView manualRemoteView;
    private Button applyRemoteButton;
    private Button uploadLocalButton;
    private Button sendBookButton;
    private TextView transferStatusView;
    private Button listPiccoButton;
    private TextView piccoBooksView;

    // Latest book + remote progress from the last "Compare positions" run.
    private OnyxMetadataRepository.Book manualBook;
    private KoSyncClient.Progress manualRemote;
    // Picco presence check bookkeeping: which run is current, and which
    // address it used (to re-check after the address changes in Settings).
    private int piccoCheckGeneration;
    private String piccoAddressChecked;

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
        statusHeadlineView = findViewById(R.id.text_status_headline);
        statusDot = findViewById(R.id.status_dot);
        statusView = findViewById(R.id.text_status);
        findViewById(R.id.button_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

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

        repository = new OnyxMetadataRepository(this);
        stateStore = new SyncStateStore(this);
        manualBookView = findViewById(R.id.text_manual_book);
        manualLocalView = findViewById(R.id.text_manual_local);
        manualRemoteView = findViewById(R.id.text_manual_remote);
        applyRemoteButton = findViewById(R.id.button_apply_remote);
        uploadLocalButton = findViewById(R.id.button_upload_local);
        sendBookButton = findViewById(R.id.button_send_book);
        transferStatusView = findViewById(R.id.text_transfer_status);
        listPiccoButton = findViewById(R.id.button_list_picco);
        piccoBooksView = findViewById(R.id.text_picco_books);
        listPiccoButton.setOnClickListener(v -> listPiccoBooks());
        findViewById(R.id.button_manual_check).setOnClickListener(v -> manualCheck());
        applyRemoteButton.setOnClickListener(v -> manualApplyRemote());
        uploadLocalButton.setOnClickListener(v -> manualUploadLocal());
        sendBookButton.setOnClickListener(v -> sendBookToPicco());
        findViewById(R.id.button_refresh_book).setOnClickListener(v -> refreshLocalBook());

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
            // The Picco address may have been changed on the settings screen.
            if (manualBook != null && !settings.getPiccoAddress().equals(piccoAddressChecked)) {
                checkPiccoPresence(manualBook);
            }
        }
    }

    private void refreshStatus() {
        boolean autoOn = settings.isAutoSyncEnabled();
        String error = settings.getLastError();
        long lastSync = settings.getLastSyncMs();
        int headline;
        if (!autoOn) {
            headline = R.string.status_headline_paused;
        } else if (!error.isEmpty()) {
            headline = R.string.status_headline_attention;
        } else if (lastSync > 0) {
            headline = R.string.status_headline_synced;
        } else {
            headline = R.string.status_headline_never;
        }
        statusHeadlineView.setText(headline);
        statusDot.setBackgroundResource(headline == R.string.status_headline_synced
                ? R.drawable.status_dot_ok : R.drawable.status_dot_idle);

        StringBuilder sb = new StringBuilder();
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
        if (!error.isEmpty()) {
            sb.append("\nLast problem: ").append(error);
        }
        if (!Permissions.hasAllFilesAccess()) {
            sb.append("\nBook file access is off, so positions are less accurate. ")
                    .append("Open Settings → How it works to allow it.");
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
     * arms the direction buttons. Both deliberately skip the automatic
     * newest-timestamp rule — here the user decides.
     */
    private void manualCheck() {
        loadLatestBook(true);
    }

    /** Re-reads the local position only; the server is not contacted. */
    private void refreshLocalBook() {
        loadLatestBook(false);
    }

    private void loadLatestBook(boolean checkServer) {
        if (checkServer) {
            manualBookView.setText(R.string.manual_checking);
            manualLocalView.setText("");
            manualRemoteView.setText("");
            applyRemoteButton.setEnabled(false);
            uploadLocalButton.setEnabled(false);
        }
        new Thread(() -> {
            List<OnyxMetadataRepository.Book> books = repository.queryBooksWithProgress();
            if (books.isEmpty()) {
                runOnUiThread(() -> manualBookView.setText(R.string.manual_no_books));
                return;
            }
            OnyxMetadataRepository.Book book = books.get(0);
            double localPct = SyncActions.localPercentage(book);
            // Show the local position right away, then the server's, and let
            // the Picco check run on its own: a Picco that is switched off
            // must not hold up the rest of the screen.
            runOnUiThread(() -> {
                manualBook = book;
                manualBookView.setText(displayName(book));
                String local = String.format(Locale.US, "Here: %.1f%% (page %d of %d)",
                        localPct * 100, book.currentPage(), book.totalPages());
                if (book.lastAccessMs > 0) {
                    local += " · " + time(book.lastAccessMs);
                }
                manualLocalView.setText(local);
                checkPiccoPresence(book);
            });
            if (!checkServer) {
                return;
            }
            String hash = SyncActions.documentHash(settings, book.path);
            KoSyncClient.Result serverResult = hash != null ? buildClient().getProgress(hash) : null;
            runOnUiThread(() -> {
                if (manualBook != book) {
                    return; // A newer load replaced this book meanwhile.
                }
                manualRemote = (serverResult != null && serverResult.error == KoSyncClient.Error.OK)
                        ? serverResult.progress : null;
                if (manualRemote != null) {
                    manualRemoteView.setText(String.format(Locale.US, "Online: %.1f%% · from %s · %s",
                            manualRemote.percentage * 100,
                            manualRemote.device.isEmpty() ? "another device" : manualRemote.device,
                            time(manualRemote.timestampSec * 1000L)));
                } else if (serverResult != null && serverResult.error == KoSyncClient.Error.NOT_FOUND) {
                    manualRemoteView.setText(R.string.manual_no_remote);
                } else {
                    manualRemoteView.setText(getString(R.string.manual_remote_problem,
                            serverResult != null ? KoSyncClient.errorString(serverResult)
                                    : getString(R.string.manual_no_book_file)));
                }
                applyRemoteButton.setEnabled(manualRemote != null && book.totalPages() > 0);
                uploadLocalButton.setEnabled(hash != null
                        && book.currentPage() > 0 && book.totalPages() > 0);
            });
        }).start();
    }

    /**
     * Asks the Picco whether it already has this book file, so the send button
     * only lights up when there is something to send. Runs on the UI thread;
     * the network part goes to a background thread. A newer check (another
     * book, another address) makes the result of an older one irrelevant.
     */
    private void checkPiccoPresence(OnyxMetadataRepository.Book book) {
        final int generation = ++piccoCheckGeneration;
        final String address = settings.getPiccoAddress();
        piccoAddressChecked = address;
        if (address.isEmpty()) {
            // Clicking explains that the address is missing.
            sendBookButton.setEnabled(true);
            transferStatusView.setText("");
            return;
        }
        sendBookButton.setEnabled(false);
        transferStatusView.setText(R.string.transfer_checking);
        new Thread(() -> {
            Boolean onPicco = null; // null = unknown: Picco unreachable.
            try {
                onPicco = new PiccoClient(address).hasBook(new File(book.path).getName());
            } catch (Exception e) {
                android.util.Log.d("MainActivity", "Picco presence check failed: " + e);
            }
            final Boolean piccoHas = onPicco;
            runOnUiThread(() -> {
                if (generation != piccoCheckGeneration) {
                    return;
                }
                if (Boolean.TRUE.equals(piccoHas)) {
                    sendBookButton.setEnabled(false);
                    transferStatusView.setText(R.string.transfer_already);
                } else {
                    // Absent or unknown: offer the upload and let the transfer
                    // itself report any problem.
                    sendBookButton.setEnabled(true);
                    if (piccoHas == null) {
                        transferStatusView.setText("");
                    } else {
                        transferStatusView.setText(R.string.transfer_missing);
                    }
                }
            });
        }).start();
    }

    /** Book title for display: file name without path or extension. */
    private static String displayName(OnyxMetadataRepository.Book book) {
        String name = book.name != null ? book.name : book.path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name;
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

    /**
     * Sends the latest book's EPUB file to the Picco over local Wi-Fi, using
     * CrossPoint's file-transfer server. Skips the upload when a file with the
     * same name is already there. Fully independent of the sync server: works
     * while automatic sync is off or the server is rate-limiting.
     */
    private void sendBookToPicco() {
        final OnyxMetadataRepository.Book book = manualBook;
        if (book == null) {
            return;
        }
        final String address = settings.getPiccoAddress();
        if (address.isEmpty()) {
            transferStatusView.setText(R.string.transfer_no_address);
            return;
        }
        // A presence check still in flight must not overwrite this transfer's result.
        piccoCheckGeneration++;
        sendBookButton.setEnabled(false);
        transferStatusView.setText(R.string.transfer_checking);
        new Thread(() -> {
            final boolean delivered = transferBook(book, address);
            runOnUiThread(() -> {
                // Once the file is on the Picco there is nothing left to send.
                sendBookButton.setEnabled(!delivered);
            });
        }).start();
    }

    /** Runs on a background thread; returns true when the file is on the Picco. */
    private boolean transferBook(OnyxMetadataRepository.Book book, String address) {
        File file = new File(book.path);
        if (!file.canRead()) {
            transferStatusView.post(() -> transferStatusView.setText(R.string.transfer_no_file));
            return false;
        }
        PiccoClient client = new PiccoClient(address);
        try {
            if (client.hasBook(file.getName())) {
                transferStatusView.post(() -> transferStatusView.setText(R.string.transfer_already));
                return true;
            }
            runOnUiThread(() -> transferStatusView.setText(R.string.transfer_sending));
            PiccoClient.UploadResult result = client.upload(file);
            final int message;
            switch (result.status) {
                case SENT:
                    message = R.string.transfer_sent;
                    break;
                case ALREADY_THERE:
                    message = R.string.transfer_already;
                    break;
                default:
                    transferStatusView.post(() -> transferStatusView.setText(
                            getString(R.string.transfer_server_error, result.httpCode)));
                    return false;
            }
            transferStatusView.post(() -> transferStatusView.setText(message));
            return true;
        } catch (IOException e) {
            android.util.Log.w("MainActivity", "Picco transfer failed: " + e);
            // Two very different situations hide behind a failed connection:
            // the tablet is not on the Picco's network at all (hotspot off or
            // not joined), or it is online but the Picco does not answer
            // (wrong address or File Transfer not running).
            final String message = hasLocalNetwork()
                    ? getString(R.string.transfer_unreachable, address)
                    : getString(R.string.transfer_no_network);
            transferStatusView.post(() -> transferStatusView.setText(message));
            return false;
        } catch (Exception e) {
            // Not a connection problem: the Picco answered, something else broke.
            android.util.Log.e("MainActivity", "Picco transfer error", e);
            final String message = getString(R.string.transfer_unexpected, String.valueOf(e));
            transferStatusView.post(() -> transferStatusView.setText(message));
            return false;
        }
    }

    /**
     * Lists every book file currently on the Picco. Uses the same address and
     * the same network-aware error messages as the book-file transfer.
     */
    private void listPiccoBooks() {
        final String address = settings.getPiccoAddress();
        if (address.isEmpty()) {
            piccoBooksView.setText(R.string.transfer_no_address);
            return;
        }
        listPiccoButton.setEnabled(false);
        piccoBooksView.setText(R.string.picco_books_loading);
        new Thread(() -> {
            try {
                final List<String> books = new PiccoClient(address).listBooks();
                // Local positions by file name, so each Picco book can be
                // annotated with how far it was read on this tablet. Matching
                // by exact file name, same as the transfer's presence check.
                final Map<String, Double> localPctByName = new HashMap<>();
                for (OnyxMetadataRepository.Book local : repository.queryBooksWithProgress()) {
                    if (local.path == null) {
                        continue;
                    }
                    String name = local.path.substring(local.path.lastIndexOf('/') + 1);
                    localPctByName.put(name, SyncActions.localPercentage(local));
                }
                runOnUiThread(() -> {
                    listPiccoButton.setEnabled(true);
                    if (books.isEmpty()) {
                        piccoBooksView.setText(R.string.picco_books_empty);
                        return;
                    }
                    StringBuilder text = new StringBuilder(getResources().getQuantityString(
                            R.plurals.picco_books_count, books.size(), books.size()));
                    for (String book : books) {
                        String name = book.substring(book.lastIndexOf('/') + 1);
                        Double pct = localPctByName.get(name);
                        text.append('\n').append(book);
                        if (pct != null) {
                            text.append(String.format(Locale.US, " — here: %.1f%%", pct * 100));
                        } else {
                            text.append(" — no position here");
                        }
                    }
                    piccoBooksView.setText(text.toString());
                });
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "Picco library listing failed: " + e);
                // Only a failed request means "can't reach"; anything else is
                // a bug on our side and is reported as such.
                final String message = !(e instanceof IOException)
                        ? getString(R.string.transfer_unexpected, String.valueOf(e))
                        : hasLocalNetwork()
                        ? getString(R.string.transfer_unreachable, address)
                        : getString(R.string.transfer_no_network);
                runOnUiThread(() -> {
                    listPiccoButton.setEnabled(true);
                    piccoBooksView.setText(message);
                });
            }
        }).start();
    }

    /**
     * True when the tablet currently has a local-network link (Wi-Fi or
     * Ethernet). A Picco hotspot without internet access still counts, so the
     * check looks at the transport, not at internet reachability.
     */
    private boolean hasLocalNetwork() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true; // Cannot tell — fall back to the generic message.
        }
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
    }
}
