package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import dev.neosync.boox.R;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.SyncActions;
import dev.neosync.boox.kosync.SyncAll;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.kosync.SyncStateStore;
import dev.neosync.boox.service.SyncService;
import dev.neosync.boox.transfer.BookSyncStatus;
import dev.neosync.boox.transfer.PiccoClient;
import dev.neosync.boox.transfer.PiccoLibraryStore;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
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
    private Button retryButton;
    private boolean retrying;
    // Refreshes the status card whenever the sync service records an outcome.
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener
            statusListener = (prefs, key) -> runOnUiThread(() -> {
                if (SyncSettings.KEY_LAST_ATTEMPT_MS.equals(key)) {
                    retrying = false;
                }
                refreshStatus();
                renderPiccoLibrary();
            });
    private TextView statusView;
    private View statusDot;
    private TextView manualBookView;
    private TextView manualLocalView;
    private TextView manualRemoteView;
    private Button applyRemoteButton;
    private Button uploadLocalButton;
    private Button sendBookButton;
    private TextView transferStatusView;
    private PiccoLibraryStore piccoLibrary;
    private Button syncAllButton;
    private Button sendMissingButton;
    private TextView bulkStatusView;
    private View wifiHintView;
    // Keeps the hotspot hint current as the Boox switches networks.
    private final android.net.ConnectivityManager.NetworkCallback networkCallback =
            new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(android.net.Network network,
                                                  android.net.NetworkCapabilities caps) {
                    runOnUiThread(MainActivity.this::refreshNetworkHints);
                }

                @Override
                public void onLost(android.net.Network network) {
                    runOnUiThread(MainActivity.this::refreshNetworkHints);
                }
            };
    private boolean bulkRunning; // "Sync all positions" or "Send missing books"
    private TextView piccoUpdatedView;
    private LinearLayout piccoBookList;
    // Picco list refresh state: whether one is running, and whether the last
    // one failed (then the saved list is shown as possibly out of date).
    private boolean piccoRefreshing;
    private String piccoRefreshError;

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
        retryButton = findViewById(R.id.button_retry_sync);
        retryButton.setOnClickListener(v -> retrySync());
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
        piccoLibrary = new PiccoLibraryStore(this);
        piccoUpdatedView = findViewById(R.id.text_picco_updated);
        piccoBookList = findViewById(R.id.list_picco_books);
        findViewById(R.id.button_refresh_picco).setOnClickListener(v -> refreshPiccoLibrary());
        syncAllButton = findViewById(R.id.button_sync_all);
        sendMissingButton = findViewById(R.id.button_send_missing);
        bulkStatusView = findViewById(R.id.text_bulk_status);
        wifiHintView = findViewById(R.id.group_wifi_hint);
        findViewById(R.id.button_wifi_settings).setOnClickListener(v -> openWifiPicker());
        syncAllButton.setOnClickListener(v -> syncAllPositions());
        sendMissingButton.setOnClickListener(v -> sendMissingBooks());
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
    protected void onPause() {
        super.onPause();
        if (statusView != null) {
            settings.unregisterListener(statusListener);
            try {
                ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
                // Not registered (e.g. registration failed in onResume).
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) {
            settings.registerListener(statusListener);
            try {
                ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                        .registerDefaultNetworkCallback(networkCallback);
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "No network callback: " + e);
            }
            refreshStatus();
            renderPiccoLibrary();
            // The Picco address may have been changed on the settings screen.
            if (piccoAddressChecked != null
                    && !settings.getPiccoAddress().equals(piccoAddressChecked)) {
                refreshPiccoLibrary();
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
        retryButton.setVisibility(headline == R.string.status_headline_attention
                ? View.VISIBLE : View.GONE);
        retryButton.setEnabled(!retrying);
        retryButton.setText(retrying ? R.string.status_retrying : R.string.button_retry_sync);

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
            if (onWifiWithoutInternet()) {
                sb.append("\n").append(getString(R.string.hotspot_hint_short));
            }
        }
        if (!Permissions.hasAllFilesAccess()) {
            sb.append("\nBook file access is off, so positions are less accurate. ")
                    .append("Open Settings → How it works to allow it.");
        }
        statusView.setText(sb.toString());
    }

    /**
     * Asks the sync service to try again now. The status card updates through
     * statusListener once the attempt is recorded; the timeout only guards
     * against an attempt that never reports back (service killed meanwhile).
     */
    private void retrySync() {
        retrying = true;
        refreshStatus();
        SyncService.syncNow(this);
        retryButton.postDelayed(() -> {
            if (retrying) {
                retrying = false;
                refreshStatus();
            }
        }, 60_000);
    }

    // --- Manual sync ---------------------------------------------------------

    private KoSyncClient buildClient() {
        return new KoSyncClient(settings, "NeoSync", deviceId());
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
                runOnUiThread(() -> {
                    manualBookView.setText(R.string.manual_no_books);
                    refreshPiccoLibrary(); // The Picco list doesn't need a local book.
                });
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
     * Whether the Picco already has this book decides whether the send button
     * lights up. Answered by refreshing the Picco's book list, which keeps the
     * library section current with the same single walk over the Picco.
     */
    private void checkPiccoPresence(OnyxMetadataRepository.Book book) {
        refreshPiccoLibrary();
    }

    /**
     * Fetches the Picco's book list in the background and saves it. The saved
     * list stays on screen meanwhile and when the Picco can't be reached.
     * Also settles the send button for the current book. A newer refresh
     * (another address, a transfer) makes an older one's result irrelevant.
     */
    private void refreshPiccoLibrary() {
        final int generation = ++piccoCheckGeneration;
        final String address = settings.getPiccoAddress();
        piccoAddressChecked = address;
        if (address.isEmpty()) {
            piccoRefreshing = false;
            piccoRefreshError = null;
            // Clicking explains that the address is missing.
            sendBookButton.setEnabled(manualBook != null);
            transferStatusView.setText("");
            renderPiccoLibrary();
            return;
        }
        piccoRefreshing = true;
        piccoRefreshError = null;
        if (manualBook != null) {
            sendBookButton.setEnabled(false);
            transferStatusView.setText(R.string.transfer_checking);
        }
        renderPiccoLibrary();
        new Thread(() -> {
            List<String> books = null;
            String error = null;
            try {
                books = new PiccoClient(address).listBooks();
                piccoLibrary.save(address, books);
            } catch (IOException e) {
                android.util.Log.d("MainActivity", "Picco listing failed: " + e);
                error = hasLocalNetwork()
                        ? getString(R.string.transfer_unreachable, address)
                        : getString(R.string.transfer_no_network);
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Picco listing error", e);
                error = getString(R.string.transfer_unexpected, String.valueOf(e));
            }
            final List<String> listed = books;
            final String failure = error;
            runOnUiThread(() -> {
                if (generation != piccoCheckGeneration) {
                    return;
                }
                piccoRefreshing = false;
                piccoRefreshError = failure;
                OnyxMetadataRepository.Book book = manualBook;
                if (book != null) {
                    if (listed == null) {
                        // Unknown: offer the upload and let it report problems.
                        sendBookButton.setEnabled(true);
                        transferStatusView.setText("");
                    } else if (containsFile(listed, fileName(book.path))) {
                        sendBookButton.setEnabled(false);
                        transferStatusView.setText(R.string.transfer_already);
                    } else {
                        sendBookButton.setEnabled(true);
                        transferStatusView.setText(R.string.transfer_missing);
                    }
                }
                renderPiccoLibrary();
            });
        }).start();
    }

    /** True when any listed path ends in exactly this file name. */
    private static boolean containsFile(List<String> paths, String fileName) {
        for (String path : paths) {
            if (fileName(path).equals(fileName)) {
                return true;
            }
        }
        return false;
    }

    private static String fileName(String path) {
        return path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
    }

    /** One row of the Picco list, prepared off the UI thread. */
    private static class PiccoRow {
        String title;
        String status;
        long lastAccessMs;
    }

    /**
     * Redraws the Picco list from the saved copy, with each book's status on
     * this tablet. Reads the Onyx library on a background thread; never talks
     * to the Picco or the sync server.
     */
    private void renderPiccoLibrary() {
        if (piccoBookList == null) {
            return;
        }
        final String address = settings.getPiccoAddress();
        final boolean autoSync = settings.isAutoSyncEnabled();
        new Thread(() -> {
            List<String> books = piccoLibrary.getBooks(address);
            final boolean hasList = piccoLibrary.hasList(address);
            final long updatedMs = piccoLibrary.getUpdatedMs();
            Map<String, OnyxMetadataRepository.Book> localByName = new HashMap<>();
            if (!books.isEmpty()) {
                for (OnyxMetadataRepository.Book local : repository.queryBooksWithProgress()) {
                    if (local.path != null) {
                        localByName.put(fileName(local.path), local);
                    }
                }
            }
            final List<PiccoRow> rows = new ArrayList<>();
            for (String path : books) {
                OnyxMetadataRepository.Book local = localByName.get(fileName(path));
                PiccoRow row = new PiccoRow();
                row.title = displayTitle(path);
                row.status = statusLine(path, local, autoSync);
                row.lastAccessMs = local != null ? local.lastAccessMs : 0;
                rows.add(row);
            }
            // Most recently read here first; books not read here after, A–Z
            // (the listing order, kept by the stable sort).
            Collections.sort(rows, (x, y) -> Long.compare(y.lastAccessMs, x.lastAccessMs));
            runOnUiThread(() -> showPiccoRows(address, hasList, updatedMs, rows));
        }).start();
    }

    private void showPiccoRows(String address, boolean hasList, long updatedMs,
                               List<PiccoRow> rows) {
        String updated = hasList ? time(updatedMs) : null;
        if (address.isEmpty()) {
            piccoUpdatedView.setText(R.string.picco_list_no_address);
        } else if (piccoRefreshing) {
            piccoUpdatedView.setText(hasList
                    ? getString(R.string.picco_list_refreshing, updated)
                    : getString(R.string.picco_list_loading));
        } else if (piccoRefreshError != null) {
            piccoUpdatedView.setText(hasList
                    ? getString(R.string.picco_list_stale, updated) + "\n" + piccoRefreshError
                    : piccoRefreshError);
        } else if (hasList) {
            piccoUpdatedView.setText(getString(R.string.picco_list_updated, updated));
        } else {
            piccoUpdatedView.setText(R.string.picco_list_never);
        }

        piccoBookList.removeAllViews();
        if (hasList && rows.isEmpty()) {
            piccoBookList.addView(rowText(getString(R.string.picco_books_empty),
                    R.style.NeoSync_Small, 12));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            PiccoRow row = rows.get(i);
            if (i > 0) {
                View divider = new View(this);
                divider.setBackgroundResource(R.color.ink_soft);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.topMargin = dp(10);
                divider.setLayoutParams(lp);
                piccoBookList.addView(divider);
            }
            TextView title = rowText(row.title, R.style.NeoSync_Body, 10);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            piccoBookList.addView(title);
            piccoBookList.addView(rowText(row.status, R.style.NeoSync_Small, 2));
        }
    }

    private TextView rowText(String text, int style, int marginTopDp) {
        TextView view = new TextView(this);
        view.setTextAppearance(style);
        view.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(marginTopDp);
        view.setLayoutParams(lp);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** "Fiction/Dune.epub" -> "Dune". */
    private static String displayTitle(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The grey line under a book: its folder on the Picco, then its status here. */
    private String statusLine(String piccoPath, OnyxMetadataRepository.Book local,
                              boolean autoSync) {
        String status;
        if (local == null) {
            status = getString(R.string.book_status_not_here);
        } else {
            SyncStateStore.BookState state = stateStore.get(local.path);
            String pct = String.format(Locale.US, "%.0f%%",
                    SyncActions.localPercentage(local) * 100);
            switch (BookSyncStatus.of(true, local.progress, local.currentPage(),
                    state.lastPushedProgress, state.lastWrittenProgress)) {
                case SYNCED:
                    status = state.lastSyncMs > 0
                            ? getString(R.string.book_status_synced, pct, time(state.lastSyncMs))
                            : getString(R.string.book_status_synced_no_time, pct);
                    break;
                case CHANGED_HERE:
                    status = getString(autoSync ? R.string.book_status_changed
                            : R.string.book_status_changed_paused, pct);
                    break;
                case NEVER_SYNCED:
                    status = getString(R.string.book_status_never, pct);
                    break;
                default:
                    status = getString(R.string.book_status_not_started);
            }
        }
        int slash = piccoPath.lastIndexOf('/');
        return slash > 0 ? piccoPath.substring(0, slash) + " · " + status : status;
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
        // A list refresh still in flight must not overwrite this transfer's
        // result. Its listing is still saved; only its screen update is dropped.
        piccoCheckGeneration++;
        if (piccoRefreshing) {
            piccoRefreshing = false;
            renderPiccoLibrary();
        }
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
            piccoLibrary.addUploaded(address, file.getName());
            transferStatusView.post(() -> {
                transferStatusView.setText(message);
                renderPiccoLibrary();
            });
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

    // --- Bulk actions ---------------------------------------------------------

    private void setBulkRunning(boolean running) {
        bulkRunning = running;
        syncAllButton.setEnabled(!running);
        sendMissingButton.setEnabled(!running);
    }

    private void showBulkStatus(String text) {
        bulkStatusView.setVisibility(View.VISIBLE);
        bulkStatusView.setText(text);
    }

    /**
     * Brings the position of every book that is both on this Boox and on the
     * Picco up to date, newest position winning. Without a saved Picco list,
     * every book opened on this Boox is synced.
     */
    private void syncAllPositions() {
        if (bulkRunning) {
            return;
        }
        setBulkRunning(true);
        showBulkStatus(getString(R.string.manual_checking));
        final String address = settings.getPiccoAddress();
        new Thread(() -> {
            List<OnyxMetadataRepository.Book> books = repository.queryBooksWithProgress();
            if (piccoLibrary.hasList(address)) {
                List<String> onPicco = piccoLibrary.getBooks(address);
                List<OnyxMetadataRepository.Book> both = new ArrayList<>();
                for (OnyxMetadataRepository.Book book : books) {
                    if (containsFile(onPicco, fileName(book.path))) {
                        both.add(book);
                    }
                }
                books = both;
            }
            if (books.isEmpty()) {
                runOnUiThread(() -> {
                    setBulkRunning(false);
                    showBulkStatus(getString(R.string.sync_all_none));
                });
                return;
            }
            new SyncAll(this, settings, repository, stateStore, buildClient(), deviceId())
                    .run(books, new SyncAll.Listener() {
                        @Override
                        public void onBook(int index, int count, OnyxMetadataRepository.Book book) {
                            String text = getString(R.string.sync_all_progress,
                                    index + 1, count, displayName(book));
                            runOnUiThread(() -> showBulkStatus(text));
                        }

                        @Override
                        public void onFinished(SyncAll.Summary summary) {
                            runOnUiThread(() -> {
                                setBulkRunning(false);
                                String text;
                                if (summary.stoppedReason != null) {
                                    int done = summary.fetched + summary.sent
                                            + summary.unchanged + summary.failed;
                                    String reason = summary.stoppedError
                                            == KoSyncClient.Error.NETWORK_ERROR
                                            ? getString(hasInternet()
                                                    ? R.string.sync_all_server_unreachable
                                                    : R.string.sync_all_no_internet)
                                            : summary.stoppedReason;
                                    text = done == 0 ? reason : getString(
                                            R.string.sync_all_stopped, done, summary.count, reason);
                                } else {
                                    text = getString(R.string.sync_all_done, summary.fetched,
                                            summary.sent, summary.unchanged);
                                    if (summary.failed > 0) {
                                        text += " " + getString(R.string.sync_all_failed_part,
                                                summary.failed);
                                    }
                                }
                                showBulkStatus(text);
                                refreshStatus();
                                renderPiccoLibrary();
                                refreshLocalBook();
                            });
                        }
                    });
        }).start();
    }

    /**
     * Copies every EPUB on this Boox that the Picco doesn't have yet, after
     * asking. Compares against a fresh listing so nothing is sent twice.
     */
    private void sendMissingBooks() {
        if (bulkRunning) {
            return;
        }
        final String address = settings.getPiccoAddress();
        if (address.isEmpty()) {
            showBulkStatus(getString(R.string.transfer_no_address));
            return;
        }
        setBulkRunning(true);
        showBulkStatus(getString(R.string.send_missing_checking));
        new Thread(() -> {
            PiccoClient client = new PiccoClient(address);
            List<String> onPicco;
            try {
                onPicco = client.listBooks();
                piccoLibrary.save(address, onPicco);
            } catch (Exception e) {
                final String message = !(e instanceof IOException)
                        ? getString(R.string.transfer_unexpected, String.valueOf(e))
                        : hasLocalNetwork()
                        ? getString(R.string.transfer_unreachable, address)
                        : getString(R.string.transfer_no_network);
                runOnUiThread(() -> {
                    setBulkRunning(false);
                    showBulkStatus(message);
                });
                return;
            }
            final List<File> missing = new ArrayList<>();
            long bytes = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (OnyxMetadataRepository.Book book : repository.queryAllBooks()) {
                String name = fileName(book.path);
                File file = new File(book.path);
                if (!name.toLowerCase(Locale.ROOT).endsWith(".epub") || !seen.add(name)
                        || containsFile(onPicco, name) || !file.canRead()) {
                    continue;
                }
                missing.add(file);
                bytes += file.length();
            }
            final String size = android.text.format.Formatter.formatShortFileSize(this, bytes);
            runOnUiThread(() -> {
                renderPiccoLibrary();
                if (missing.isEmpty()) {
                    setBulkRunning(false);
                    showBulkStatus(getString(R.string.send_missing_none));
                    return;
                }
                bulkStatusView.setVisibility(View.GONE);
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.send_missing_confirm_title)
                        .setMessage(getString(R.string.send_missing_confirm_body,
                                missing.size(), size))
                        .setPositiveButton(R.string.send_missing_confirm_ok,
                                (d, w) -> uploadAll(client, address, missing))
                        .setNegativeButton(R.string.send_missing_cancel,
                                (d, w) -> setBulkRunning(false))
                        .setOnCancelListener(d -> setBulkRunning(false))
                        .show();
            });
        }).start();
    }

    private void uploadAll(PiccoClient client, String address, List<File> files) {
        new Thread(() -> {
            int sent = 0;
            String failure = null;
            for (int i = 0; i < files.size(); i++) {
                File file = files.get(i);
                String text = getString(R.string.send_missing_progress, i + 1, files.size(),
                        displayTitle(file.getName()));
                runOnUiThread(() -> showBulkStatus(text));
                try {
                    PiccoClient.UploadResult result = client.upload(file);
                    if (result.status != PiccoClient.Status.SERVER_ERROR) {
                        sent++;
                        piccoLibrary.addUploaded(address, file.getName());
                    }
                } catch (IOException e) {
                    // The Picco is gone (File Transfer closed, Wi-Fi dropped):
                    // the remaining books would all fail the same way.
                    android.util.Log.w("MainActivity", "Bulk upload stopped: " + e);
                    failure = getString(R.string.send_missing_lost, i, files.size());
                    break;
                }
            }
            final String summary = failure != null ? failure
                    : getString(R.string.send_missing_done, sent, files.size());
            runOnUiThread(() -> {
                setBulkRunning(false);
                showBulkStatus(summary);
                renderPiccoLibrary();
                if (manualBook != null) {
                    refreshPiccoLibrary();
                }
            });
        }).start();
    }

    private String deviceId() {
        String androidId = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID);
        return androidId != null ? androidId : "boox-companion";
    }

    /**
     * Apps can't switch Wi-Fi networks themselves (Android 10+), so this opens
     * the system's Wi-Fi panel over the app; the user picks the network and
     * comes straight back. Falls back to the full Wi-Fi settings screen on
     * firmware without the panel.
     */
    private void openWifiPicker() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                return;
            } catch (android.content.ActivityNotFoundException ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (android.content.ActivityNotFoundException e) {
            android.util.Log.w("MainActivity", "No Wi-Fi settings screen: " + e);
        }
    }

    /** Shows or hides the hotspot hints for the current network. */
    private void refreshNetworkHints() {
        if (wifiHintView == null || isFinishing()) {
            return;
        }
        wifiHintView.setVisibility(onWifiWithoutInternet() ? View.VISIBLE : View.GONE);
        refreshStatus();
    }

    /**
     * True on a Wi-Fi network that has no internet, which in practice means
     * the Picco's own hotspot. Mobile data or no network at all don't count.
     */
    private boolean onWifiWithoutInternet() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        android.net.Network network = cm.getActiveNetwork();
        android.net.NetworkCapabilities caps =
                network != null ? cm.getNetworkCapabilities(network) : null;
        return caps != null
                && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                && !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * True when Android has confirmed the current network reaches the
     * internet. False on the Picco's own hotspot, which is local only.
     */
    private boolean hasInternet() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true; // Cannot tell — fall back to the generic message.
        }
        android.net.Network network = cm.getActiveNetwork();
        android.net.NetworkCapabilities caps =
                network != null ? cm.getNetworkCapabilities(network) : null;
        return caps != null && caps.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
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
