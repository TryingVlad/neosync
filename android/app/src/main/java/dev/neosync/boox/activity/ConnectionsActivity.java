package dev.neosync.boox.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.Html;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

import dev.neosync.boox.R;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.jobs.SyncAllJob;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.kosync.SyncStateStore;
import dev.neosync.boox.library.LibraryModel;
import dev.neosync.boox.library.PiccoFinder;
import dev.neosync.boox.library.SyncProblem;
import dev.neosync.boox.library.TitleCleaner;
import dev.neosync.boox.service.SyncService;
import dev.neosync.boox.transfer.PiccoClient;
import dev.neosync.boox.transfer.PiccoLibraryStore;

/**
 * Connections (spec 5.3, mockup 3), opened from either home chip: a diagram
 * of the two links, this Boox's file access (5.9), the sync server's state
 * with its fix, and the Picco's state with the steps to connect it and
 * "Look for the Picco" (PiccoFinder). Black and white, at most one black
 * button, no animation.
 */
public class ConnectionsActivity extends Activity {

    private static final String TAG = "Connections";

    /** How long the saved address gets to answer before the Picco counts as unreachable. */
    private static final int CHECK_TIMEOUT_MS = 3000;

    private SyncSettings settings;

    private View wireWifi;
    private TextView wireWifiText;
    private TextView fileAccessText;
    private Button fileAccessButton;
    private TextView serverText;
    private Button serverFix;
    private TextView syncAllNote;
    private TextView piccoText;
    private TextView hotspotText;
    private View piccoSteps;
    private Button lookButton;
    private TextView addressLink;

    /** Which Picco check or search is current; older ones' results are dropped. */
    private int generation;
    private boolean checking;
    private boolean looking;
    private boolean lookFailed;
    private boolean retrying;
    /** The automatic search runs at most once per visit (onResume). */
    private boolean autoLooked;

    private final Runnable stopRetrying = () -> {
        retrying = false;
        render();
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            (prefs, key) -> runOnUiThread(() -> {
                if (SyncSettings.KEY_LAST_ATTEMPT_MS.equals(key)) {
                    retrying = false;
                }
                render();
            });

    // The hotspot text follows the Boox as it switches networks.
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    runOnUiThread(ConnectionsActivity.this::render);
                }

                @Override
                public void onLost(Network network) {
                    runOnUiThread(ConnectionsActivity.this::render);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setWindowAnimations(0);
        setContentView(R.layout.activity_connections);
        settings = new SyncSettings(this);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        wireWifi = findViewById(R.id.wire_wifi);
        wireWifiText = findViewById(R.id.text_wire_wifi);
        fileAccessText = findViewById(R.id.text_file_access);
        fileAccessButton = findViewById(R.id.button_file_access);
        fileAccessButton.setOnClickListener(v -> Permissions.openAllFilesAccessSettings(this));
        serverText = findViewById(R.id.text_server);
        serverFix = findViewById(R.id.button_server_fix);
        syncAllNote = findViewById(R.id.text_sync_all_note);
        findViewById(R.id.link_sync_all).setOnClickListener(v -> syncAll());
        piccoText = findViewById(R.id.text_picco);
        hotspotText = findViewById(R.id.text_hotspot);
        piccoSteps = findViewById(R.id.group_picco_steps);
        ((TextView) findViewById(R.id.text_step_1)).setText(html(R.string.conn_step_1));
        ((TextView) findViewById(R.id.text_step_2)).setText(html(R.string.conn_step_2));
        lookButton = findViewById(R.id.button_look);
        lookButton.setOnClickListener(v -> look());
        findViewById(R.id.button_wifi).setOnClickListener(v -> WifiHelper.openWifiPicker(this));
        addressLink = findViewById(R.id.link_address);
        addressLink.setOnClickListener(v -> askAddress());
    }

    @Override
    protected void onResume() {
        super.onResume();
        settings.registerListener(statusListener);
        try {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                    .registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "No network callback: " + e);
        }
        autoLooked = false;
        syncAllNote.setVisibility(View.GONE);
        render();
        checkPicco();
    }

    @Override
    protected void onPause() {
        super.onPause();
        settings.unregisterListener(statusListener);
        try {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                    .unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            // Not registered (registration failed in onResume).
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        serverText.removeCallbacks(stopRetrying);
    }

    // --- Rendering ----------------------------------------------------------------

    private void render() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // At most one black button: file access first, then the server's
        // fix, then "Look for the Picco" (the home slot's order).
        boolean primaryTaken = renderFileAccess();
        primaryTaken = renderServer(primaryTaken);
        renderPicco(primaryTaken);
    }

    /** True when it shows the Allow button. */
    private boolean renderFileAccess() {
        boolean allowed = Permissions.hasAllFilesAccess();
        setText(fileAccessText, allowed ? getString(R.string.conn_file_access_allowed)
                : html(R.string.conn_file_access_missing));
        setVisible(fileAccessButton, !allowed);
        if (!allowed) {
            styleButton(fileAccessButton, true);
        }
        return !allowed;
    }

    /** True when a black button is now taken (passed in, or the server's fix). */
    private boolean renderServer(boolean primaryTaken) {
        String fix = null;
        Runnable action = null;
        boolean enabled = true;
        CharSequence sentence;
        long lastSync = settings.getLastSyncMs();
        if (!settings.isAutoSyncEnabled()) {
            sentence = html(R.string.conn_server_paused);
            fix = getString(R.string.sug_paused_button);
            action = this::turnSyncOn;
        } else if (SyncProblem.isAlarm(settings.getFirstFailureMs(),
                settings.getConsecutiveFailures(), System.currentTimeMillis())) {
            SyncProblem.Type type = settings.getProblemType();
            if (type == SyncProblem.Type.NO_INTERNET) {
                sentence = html(R.string.conn_server_no_internet);
                fix = getString(R.string.button_wifi_settings);
                action = () -> WifiHelper.openWifiPicker(this);
            } else if (type == SyncProblem.Type.AUTH) {
                sentence = html(R.string.conn_server_auth);
                fix = getString(R.string.button_sign_in);
                action = () -> startActivity(new Intent(this, SignInActivity.class));
            } else {
                sentence = html(R.string.conn_server_unreachable,
                        clock(settings.getFirstFailureMs()));
                fix = getString(retrying ? R.string.status_retrying : R.string.button_retry_sync);
                enabled = !retrying;
                action = this::retrySync;
            }
        } else if (lastSync > 0) {
            long now = System.currentTimeMillis();
            String ago = now - lastSync < DateUtils.MINUTE_IN_MILLIS
                    ? getString(R.string.chip_just_now)
                    : DateUtils.getRelativeTimeSpanString(lastSync, now,
                            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
            String book = settings.getLastBook();
            sentence = book.isEmpty() ? html(R.string.conn_server_working_no_book, ago)
                    : html(R.string.conn_server_working, ago,
                            TitleCleaner.fromFileName(LibraryModel.keyOf(book)));
        } else {
            sentence = html(R.string.conn_server_signed_in);
        }
        setText(serverText, sentence);
        setVisible(serverFix, fix != null);
        if (fix == null) {
            return primaryTaken;
        }
        setText(serverFix, fix);
        if (serverFix.isEnabled() != enabled) {
            serverFix.setEnabled(enabled);
        }
        Runnable run = action;
        serverFix.setOnClickListener(v -> run.run());
        styleButton(serverFix, !primaryTaken);
        return true;
    }

    private void renderPicco(boolean primaryTaken) {
        String address = settings.getPiccoAddress();
        Boolean reachable = address.isEmpty() ? null : MainActivity.lastPiccoCheck(address);
        boolean connected = !looking && Boolean.TRUE.equals(reachable);
        boolean pending = !looking && !lookFailed && checking && reachable == null;

        CharSequence sentence;
        if (connected) {
            sentence = html(R.string.conn_picco_connected, address);
        } else if (pending) {
            sentence = html(R.string.conn_picco_checking, address);
        } else if (lookFailed) {
            sentence = html(R.string.conn_picco_not_found);
        } else if (address.isEmpty()) {
            sentence = html(R.string.conn_picco_not_set_up);
        } else {
            sentence = html(R.string.conn_picco_unreachable);
        }
        setText(piccoText, sentence);

        setVisible(piccoSteps, !connected && !pending);
        setText(lookButton, getString(looking ? R.string.conn_looking : R.string.conn_look));
        if (lookButton.isEnabled() == looking) {
            lookButton.setEnabled(!looking);
        }
        styleButton(lookButton, !primaryTaken);

        // The hotspot case: on a Wi-Fi that doesn't reach the internet.
        setVisible(hotspotText, WifiHelper.hasLocalNetwork(this)
                && !SyncProblem.wifiHasInternet(this));

        int wire = connected ? R.drawable.line_solid : R.drawable.line_dashed;
        if (!Integer.valueOf(wire).equals(wireWifi.getTag())) {
            wireWifi.setBackgroundResource(wire);
            wireWifi.setTag(wire);
        }
        setText(wireWifiText, connected ? getString(R.string.conn_wire_wifi_connected)
                : pending ? getString(R.string.conn_wire_wifi_checking)
                : html(R.string.conn_wire_wifi_not_connected));

        setText(addressLink, !address.isEmpty() && !connected
                ? html(R.string.conn_last_seen, address)
                : html(R.string.conn_enter_by_hand));
    }

    // --- The Picco ----------------------------------------------------------------

    /**
     * Asks the saved address whether the Picco is there. When it isn't (or
     * none is saved yet), looks for it once per visit, as step 3 promises.
     */
    private void checkPicco() {
        final String address = settings.getPiccoAddress();
        if (address.isEmpty()) {
            autoLook();
            return;
        }
        final int run = ++generation;
        checking = true;
        render();
        new Thread(() -> {
            boolean ok = new PiccoClient(address).probe(CHECK_TIMEOUT_MS);
            runOnUiThread(() -> {
                if (run != generation || isDestroyed()) {
                    return;
                }
                checking = false;
                MainActivity.rememberPiccoCheck(address, ok);
                if (!ok) {
                    autoLook();
                }
                render();
            });
        }, "NeoSync check Picco").start();
    }

    private void autoLook() {
        if (!autoLooked && WifiHelper.hasLocalNetwork(this)) {
            autoLooked = true;
            look();
        }
    }

    /** "Look for the Picco": PiccoFinder on this network; saves the address it finds. */
    private void look() {
        if (looking) {
            return;
        }
        final int run = ++generation;
        checking = false;
        looking = true;
        lookFailed = false;
        render();
        final String lastKnown = settings.getPiccoAddress();
        final String[] net = WifiHelper.localNetwork(this);
        new Thread(() -> {
            List<String> candidates = PiccoFinder.candidates(lastKnown, net[0], net[1]);
            String found = PiccoFinder.find(candidates,
                    candidate -> new PiccoClient(candidate).probe(PiccoFinder.PROBE_TIMEOUT_MS),
                    PiccoFinder.THREADS, PiccoFinder.DEADLINE_MS);
            runOnUiThread(() -> {
                if (run != generation || isDestroyed()) {
                    return;
                }
                looking = false;
                if (found != null) {
                    settings.setPiccoAddress(found);
                    MainActivity.rememberPiccoCheck(found, true);
                } else {
                    lookFailed = true;
                    if (!lastKnown.isEmpty()) {
                        MainActivity.rememberPiccoCheck(lastKnown, false);
                    }
                }
                render();
            });
        }, "NeoSync find Picco").start();
    }

    /** "Enter address by hand": saves the typed address and checks it. */
    private void askAddress() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setHint(R.string.hint_picco_address);
        field.setText(settings.getPiccoAddress());
        field.setSelection(field.getText().length());
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.conn_address_title)
                .setView(box)
                .setPositiveButton(R.string.conn_address_ok, (d, w) -> {
                    generation++; // a search still running no longer counts
                    looking = false;
                    lookFailed = false;
                    settings.setPiccoAddress(field.getText().toString());
                    checkPicco();
                })
                .setNegativeButton(R.string.conn_address_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setWindowAnimations(0);
            }
        });
        dialog.show();
    }

    // --- The sync server ----------------------------------------------------------

    private void turnSyncOn() {
        settings.setAutoSyncEnabled(true);
        SyncService.start(this);
        render();
    }

    /** Asks the sync service to try now; the button resets once the attempt is recorded. */
    private void retrySync() {
        retrying = true;
        render();
        SyncService.syncNow(this);
        serverText.removeCallbacks(stopRetrying);
        serverText.postDelayed(stopRetrying, 60_000);
    }

    /**
     * "Sync all books now": runs SyncAllJob as the app's one job and goes
     * back to home, whose progress card shows it.
     */
    private void syncAll() {
        if (JobSession.running()) {
            syncAllNote.setVisibility(View.VISIBLE);
            return;
        }
        // Application context: the job outlives this screen.
        Context app = getApplicationContext();
        SyncSettings jobSettings = new SyncSettings(app);
        String deviceId = MainActivity.deviceId(app);
        boolean started = JobSession.start(new SyncAllJob(app, jobSettings,
                new OnyxMetadataRepository(app), new SyncStateStore(app),
                new KoSyncClient(jobSettings, "NeoSync", deviceId), deviceId,
                new PiccoLibraryStore(app), jobSettings.getPiccoAddress()));
        if (started) {
            finish();
        } else {
            syncAllNote.setVisibility(View.VISIBLE);
        }
    }

    // --- Helpers ------------------------------------------------------------------

    /** A string resource with escaped <b>/<u> markup, its arguments HTML-escaped. */
    private CharSequence html(int id, Object... args) {
        Object[] escaped = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            escaped[i] = TextUtils.htmlEncode(String.valueOf(args[i]));
        }
        return Html.fromHtml(getString(id, escaped), Html.FROM_HTML_MODE_LEGACY);
    }

    /** "13:37" today, else a short date and time. */
    private String clock(long ms) {
        if (DateUtils.isToday(ms)) {
            return DateFormat.getTimeFormat(this).format(new java.util.Date(ms));
        }
        return DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME);
    }

    /** Black (primary) or outlined (secondary); changed only when it differs (e-ink). */
    private void styleButton(Button button, boolean primary) {
        if (Boolean.valueOf(primary).equals(button.getTag())) {
            return;
        }
        button.setTag(primary);
        button.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_secondary);
        button.setTextColor(getColorStateList(primary ? R.color.white : R.color.btn_secondary_text));
    }

    /** Sets text only when it differs, so an unchanged view isn't redrawn (e-ink). */
    private static void setText(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private static void setVisible(View view, boolean visible) {
        int wanted = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != wanted) {
            view.setVisibility(wanted);
        }
    }
}
