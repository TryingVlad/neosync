package dev.neosync.boox.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import dev.neosync.boox.R;
import dev.neosync.boox.activity.MainActivity;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.LibrarySweep;
import dev.neosync.boox.kosync.SyncActions;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.kosync.SyncStateStore;
import dev.neosync.boox.library.BookMetaCache;
import dev.neosync.boox.library.NotificationText;
import dev.neosync.boox.library.RemotePosition;
import dev.neosync.boox.library.RemotePositionStore;
import dev.neosync.boox.library.SyncProblem;
import dev.neosync.boox.transfer.PiccoLibraryStore;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Foreground service that keeps NeoReader's reading position in sync with a
 * kosync server.
 *
 * Watches Onyx's Metadata content provider (ContentObserver, with a periodic
 * poll as fallback in case the provider doesn't send change notifications):
 *  - progress changed by the user  -> debounced push to the server
 *  - lastAccess bumped (book open) -> pull; newer remote progress is written
 *    back into the Metadata provider (newest-timestamp-wins, never blind)
 */
public class SyncService extends Service {

    private static final String TAG = "SyncService";
    private static final String CHANNEL_ID = "kosync_service";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_SYNC_NOW = "dev.neosync.boox.action.SYNC_NOW";

    /** Debounce after a provider change before scanning, absorbs rapid page turns. */
    private static final long DEBOUNCE_MS = 3000;
    /** Poll fallback interval; also spaces pushes when notifications don't fire. */
    private static final long POLL_INTERVAL_MS = 60_000;

    // The default server is a free public service shared by every user, and
    // it rate-limits (HTTP 429). The local provider scan is free; server
    // requests are rationed:
    /** With nothing changed locally, ask the server about the latest book this often. */
    private static final long IDLE_CHECK_INTERVAL_MS = 5 * 60_000;
    /** While reading, re-check for a conflicting remote position at most this often. */
    private static final long CHANGE_CHECK_INTERVAL_MS = 5 * 60_000;
    /** While reading, upload at most this often; the poll sends the final position. */
    private static final long MIN_PUSH_INTERVAL_MS = 45_000;
    /** After the screen turns on, wait for Wi-Fi to reconnect before checking. */
    private static final long WAKE_CHECK_DELAY_MS = 8_000;
    /** Check the other (closed) books this often, and after every wake. */
    private static final long SWEEP_INTERVAL_MS = 15 * 60_000;
    /** Ask about one closed book at most this often. */
    private static final long SWEEP_BOOK_MIN_AGE_MS = 10 * 60_000;
    /**
     * Closed books per check (requests are 6 s apart). Picking the tablet up
     * or opening NeoSync is when a fetch matters, so a wake checks every book
     * shared with the Picco; the quiet periodic check takes a few.
     */
    private static final int SWEEP_MAX_BOOKS_WAKE = 25;
    private static final int SWEEP_MAX_BOOKS_PERIODIC = 5;
    /** With a change feed, ask it this often (one request each time). */
    private static final long FEED_INTERVAL_MS = 3 * 60_000;
    private static final int FEED_PAGE_SIZE = 50;
    /** Pages per check; a long backlog carries on at the next check. */
    private static final int FEED_MAX_PAGES = 10;

    private HandlerThread workerThread;
    private Handler worker;
    private ContentObserver observer;

    private SyncSettings settings;
    private SyncStateStore stateStore;
    private RemotePositionStore remoteStore;
    private OnyxMetadataRepository repository;
    private KoSyncClient client;
    private String deviceId;

    /** Last seen provider state per path, to detect what actually changed. */
    private static class Seen {
        String progress;
        long lastAccessMs;
    }

    private final Map<String, Seen> snapshot = new HashMap<>();
    private boolean primed = false;

    private final Map<String, Long> lastServerCheckMs = new HashMap<>();
    private final Map<String, Long> lastPushMs = new HashMap<>();
    private long lastSweepMs = 0;
    private long lastFeedMs = 0;
    /** Whether the server has crosspoint-sync's change feed; null until asked. */
    private Boolean feedSupported = null;
    private final Map<String, String> hashCache = new HashMap<>();
    private String hashCacheMethod = null;
    private PiccoLibraryStore piccoLibrary;
    /** Set on screen-on: picking the device up is when a fetch is most useful. */
    private volatile boolean wakeCheckPending = false;
    private android.content.BroadcastReceiver screenReceiver;

    /** What the notification says now (null: the default title). Worker thread. */
    private String shownText;
    private BookMetaCache metaCache;
    /** Set in onDestroy: a late status update must not post the notification again. */
    private volatile boolean destroyed;
    /**
     * Every sync status change, from the service or the app (Sync all, the
     * book sheet), updates the notification on the worker thread.
     */
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener
            statusListener = (prefs, key) -> {
                Handler handler = worker;
                if (handler != null) {
                    handler.post(this::showStatus);
                }
            };

    private final Runnable scanRunnable = this::scan;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            scan();
            worker.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new SyncSettings(this);
        if (!settings.isAutoSyncEnabled()) {
            // Toggled off while a start was already in flight (or a sticky
            // restart slipped through): satisfy the foreground contract, then
            // shut down without touching the network.
            startForeground(NOTIFICATION_ID, buildNotification());
            stopSelf();
            return;
        }
        stateStore = new SyncStateStore(this);
        remoteStore = new RemotePositionStore(this);
        repository = new OnyxMetadataRepository(this);
        piccoLibrary = new PiccoLibraryStore(this);
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        deviceId = androidId != null ? androidId : "boox-companion";
        client = new KoSyncClient(settings, "NeoSync", deviceId);
        metaCache = new BookMetaCache(this);

        startForeground(NOTIFICATION_ID, buildNotification());

        workerThread = new HandlerThread("kosync-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        observer = new ContentObserver(worker) {
            @Override
            public void onChange(boolean selfChange) {
                // Debounce: restart the timer on every change so a burst of
                // page turns results in one scan/push.
                worker.removeCallbacks(scanRunnable);
                worker.postDelayed(scanRunnable, DEBOUNCE_MS);
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    OnyxMetadataRepository.METADATA_URI, true, observer);
        } catch (Exception e) {
            Log.w(TAG, "Could not register content observer, relying on polling", e);
        }

        screenReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                wakeCheckPending = true;
                worker.removeCallbacks(scanRunnable);
                worker.postDelayed(scanRunnable, WAKE_CHECK_DELAY_MS);
            }
        };
        registerReceiver(screenReceiver, new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));

        settings.registerListener(statusListener);
        worker.post(this::showStatus);
        worker.post(pollRunnable);
        Log.i(TAG, "SyncService started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SYNC_NOW.equals(intent.getAction()) && worker != null) {
            // "Try again" on the home screen: check the latest book now
            // instead of waiting for the next poll. Pending failed pushes are
            // retried by the same scan (their snapshot was never updated).
            worker.post(() -> {
                wakeCheckPending = true;
                scan();
                settings.recordSyncAttempt();
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
        }
        if (metaCache != null) {
            // Registered together with the observer, in a full onCreate.
            settings.unregisterListener(statusListener);
        }
        if (screenReceiver != null) {
            unregisterReceiver(screenReceiver);
        }
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Starts the service, unless the user switched automatic sync off. Every
     * caller (home screen, sign-in, boot receiver) funnels through here, so
     * this one check governs all of them — including after a reboot.
     */
    public static void start(Context context) {
        if (!new SyncSettings(context).isAutoSyncEnabled()) {
            return;
        }
        Intent intent = new Intent(context, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Asks the running service (starting it if needed) to sync right away.
     * Does nothing while automatic sync is off.
     */
    public static void syncNow(Context context) {
        if (!new SyncSettings(context).isAutoSyncEnabled()) {
            return;
        }
        Intent intent = new Intent(context, SyncService.class).setAction(ACTION_SYNC_NOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Stops the service; it stays down until automatic sync is turned on again. */
    public static void stop(Context context) {
        context.stopService(new Intent(context, SyncService.class));
    }

    // --- Sync logic (runs on worker thread) --------------------------------

    private void scan() {
        if (!settings.hasCredentials()) {
            return;
        }
        if (KoSyncClient.coolDownUntilMs() > 0) {
            // Server asked us to slow down. The snapshot is left untouched, so
            // whatever changes meanwhile is detected and synced afterwards.
            return;
        }
        List<OnyxMetadataRepository.Book> books = repository.queryBooksWithProgress();
        if (!primed) {
            // First scan after service start: learn current state without
            // syncing, so a reboot doesn't re-push every book in the library.
            for (OnyxMetadataRepository.Book book : books) {
                remember(book);
                maybeCalibrate(book, stateStore.get(book.path));
            }
            primed = true;
            // But do reconcile the most recently accessed book: if the user
            // read elsewhere while this device was off, sync it now.
            if (!books.isEmpty()) {
                OnyxMetadataRepository.Book latest = books.get(0);
                resolve(latest, stateStore.get(latest.path), latest.lastAccessMs, false, true);
            }
            return;
        }

        java.util.Set<String> resolvedThisScan = new java.util.HashSet<>();
        for (OnyxMetadataRepository.Book book : books) {
            Seen seen = snapshot.get(book.path);
            boolean isNew = seen == null;
            boolean progressChanged = !isNew && !equalsSafe(book.progress, seen.progress);
            boolean accessChanged = !isNew && book.lastAccessMs != seen.lastAccessMs;

            if (!isNew && !progressChanged && !accessChanged) {
                continue;
            }
            resolvedThisScan.add(book.path);

            SyncStateStore.BookState state = stateStore.get(book.path);
            boolean isOurOwnWrite = equalsSafe(book.progress, state.lastWrittenProgress)
                    && book.lastAccessMs == state.lastWrittenAccessMs;

            if (isOurOwnWrite) {
                remember(book);
                continue; // echo of a pull we just applied
            }

            maybeCalibrate(book, state);

            // Compare against the state from BEFORE this change: NeoReader
            // bumps lastAccess — and may rewrite progress after repaginating —
            // just from opening a book, so the current values always look
            // newer than any remote progress made overnight on another device.
            long localBasisMs;
            if (!isNew) {
                localBasisMs = seen.lastAccessMs;
            } else {
                // First sighting of this book: no prior state. If it is at
                // the very start there is no local progress to protect.
                localBasisMs = book.currentPage() <= 1 ? 0 : book.lastAccessMs;
            }

            if (resolve(book, state, localBasisMs, progressChanged || isNew, isNew)) {
                remember(book);
            }
            // On transient failure the snapshot keeps the old values, so the
            // same change is re-detected and retried on the next poll tick.
        }

        // Also reconcile the most recently read book against the server even
        // when nothing changed locally: another device may have pushed newer
        // progress while the book sits closed here. Our own writes set
        // lastAccess to the applied server timestamp, so an unchanged remote
        // is never re-applied, and the device-id guard ignores our own pushes.
        boolean woke = wakeCheckPending;
        if (!books.isEmpty()) {
            OnyxMetadataRepository.Book latest = books.get(0);
            boolean due = wakeCheckPending
                    || sinceLast(lastServerCheckMs, latest.path) >= IDLE_CHECK_INTERVAL_MS;
            if (due && !resolvedThisScan.contains(latest.path)) {
                // Stay pending after a network failure (Wi-Fi still reconnecting).
                wakeCheckPending = !resolve(latest, stateStore.get(latest.path),
                        latest.lastAccessMs, false, true) && wakeCheckPending;
            } else if (resolvedThisScan.contains(latest.path)) {
                wakeCheckPending = false;
            }
            resolvedThisScan.add(latest.path);
        }

        // Then the other, closed books: one read on the Picco gets its newer
        // position written here while it is still closed, instead of only
        // once it is opened (too late, NeoReader has loaded it by then).
        // Right after waking, and otherwise every few minutes.
        if (wakeCheckPending || KoSyncClient.coolDownUntilMs() > 0) {
            return; // offline or told to slow down: try again on a later scan
        }
        if (!Boolean.FALSE.equals(feedSupported)) {
            // crosspoint-sync lists what changed since last time: one request.
            if (woke || sinceLast(lastFeedMs) >= FEED_INTERVAL_MS) {
                lastFeedMs = System.currentTimeMillis();
                readChangeFeed(books);
            }
            if (!Boolean.FALSE.equals(feedSupported)) {
                return;
            }
        }
        // Stock kosync servers can only be asked about one book at a time.
        if (woke || sinceLast(lastSweepMs) >= SWEEP_INTERVAL_MS) {
            // The first pass after a start (boot, update) is a full one too.
            boolean full = woke || lastSweepMs == 0;
            lastSweepMs = System.currentTimeMillis();
            String address = settings.getPiccoAddress();
            List<String> onPicco = piccoLibrary.hasList(address)
                    ? piccoLibrary.getBooks(address) : null;
            for (OnyxMetadataRepository.Book book : LibrarySweep.pick(books, onPicco,
                    resolvedThisScan, lastServerCheckMs, System.currentTimeMillis(),
                    SWEEP_BOOK_MIN_AGE_MS, full ? SWEEP_MAX_BOOKS_WAKE : SWEEP_MAX_BOOKS_PERIODIC)) {
                if (!resolve(book, stateStore.get(book.path), book.lastAccessMs, false, true)) {
                    break; // offline or rate-limited: the rest would fail the same way
                }
            }
        }
    }

    /**
     * Reads the server's change feed from the saved cursor and applies each
     * newer position from another device to the matching book here. The
     * cursor only moves on after a page was handled, so nothing is skipped
     * when the connection drops halfway.
     */
    private void readChangeFeed(List<OnyxMetadataRepository.Book> books) {
        Map<String, OnyxMetadataRepository.Book> byHash = null;
        long cursor = settings.getChangesCursor();
        for (int page = 0; page < FEED_MAX_PAGES; page++) {
            KoSyncClient.Result result = client.getChanges(cursor, FEED_PAGE_SIZE);
            if (result.error == KoSyncClient.Error.NOT_FOUND) {
                Log.i(TAG, "Server has no change feed; checking books one by one");
                feedSupported = false;
                return;
            }
            if (result.error == KoSyncClient.Error.RATE_LIMITED) {
                backOff(result);
                return;
            }
            if (result.error != KoSyncClient.Error.OK) {
                if (feedSupported == null && result.error == KoSyncClient.Error.JSON_ERROR) {
                    feedSupported = false; // something answers, but not a feed
                }
                return; // offline or a hiccup: same cursor next time
            }
            feedSupported = true;
            settings.clearSyncError();
            for (KoSyncClient.Progress change : result.changes.items) {
                if (deviceId.equals(change.deviceId)) {
                    continue;
                }
                remoteStore.recordAll(change.document, change.aliases, RemotePosition.from(change));
                if (byHash == null) {
                    byHash = hashIndex(books);
                }
                OnyxMetadataRepository.Book book = byHash.get(change.document);
                for (int a = 0; book == null && a < change.aliases.size(); a++) {
                    book = byHash.get(change.aliases.get(a));
                }
                Log.d(TAG, "change " + change.document + " " + Math.round(change.percentage * 1000) / 10.0
                        + "% by " + change.device + (book == null ? " (no book here)" : ""));
                if (book == null) {
                    continue; // not on this tablet, or never opened here
                }
                lastServerCheckMs.put(book.path, System.currentTimeMillis());
                long remoteMs = change.timestampSec * 1000L;
                if (remoteMs > book.lastAccessMs) {
                    applyRemote(book, change, book.totalPages(), remoteMs);
                }
            }
            cursor = result.changes.cursor;
            settings.setChangesCursor(cursor);
            if (!result.changes.more) {
                return;
            }
        }
    }

    /** Document hash → book, for the books with a position here. */
    private Map<String, OnyxMetadataRepository.Book> hashIndex(
            List<OnyxMetadataRepository.Book> books) {
        String method = settings.getMatchMethod();
        if (!method.equals(hashCacheMethod)) {
            hashCache.clear();
            hashCacheMethod = method;
        }
        Map<String, OnyxMetadataRepository.Book> index = new HashMap<>();
        for (OnyxMetadataRepository.Book book : books) {
            if (book.totalPages() <= 0) {
                continue;
            }
            String hash = hashCache.get(book.path);
            if (hash == null) {
                hash = SyncActions.documentHash(settings, book.path);
                if (hash == null) {
                    continue;
                }
                hashCache.put(book.path, hash);
            }
            index.put(hash, book);
            for (String other : SyncActions.alternateHashes(book.path, hash)) {
                index.put(other, book);
            }
        }
        return index;
    }

    private static long sinceLast(long ms) {
        return ms == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - ms;
    }

    /**
     * Conflict-safe sync for one changed book: ask the server who is newest
     * FIRST. Progress from another device that is newer than the local
     * pre-change baseline wins and is applied locally; otherwise local
     * progress (if it changed) is pushed.
     *
     * @param localBasisMs when local progress was last actually made
     *                     (pre-open-bump); remote must be strictly newer.
     * @param mayPush      whether local progress changed and may be pushed
     * @param forceCheck   always ask the server first; otherwise the check is
     *                     skipped when this book was checked recently
     * @return false on a transient error, so the caller retries this change.
     */
    private boolean resolve(OnyxMetadataRepository.Book book, SyncStateStore.BookState state,
                            long localBasisMs, boolean mayPush, boolean forceCheck) {
        int total = book.totalPages();
        if (total <= 0) {
            return true; // unparseable row; nothing to do
        }
        String hash = documentHash(book.path);
        if (hash == null) {
            return true; // missing/unreadable file won't fix itself by retrying
        }

        boolean check = forceCheck
                || sinceLast(lastServerCheckMs, book.path) >= CHANGE_CHECK_INTERVAL_MS;
        if (!check) {
            return !mayPush || push(book, state, hash, total);
        }
        KoSyncClient.Result remote = SyncActions.getProgress(client, book.path, hash);
        if (remote.error == KoSyncClient.Error.RATE_LIMITED) {
            backOff(remote);
            return false;
        }
        if (remote.error == KoSyncClient.Error.OK || remote.error == KoSyncClient.Error.NOT_FOUND) {
            lastServerCheckMs.put(book.path, System.currentTimeMillis());
            settings.clearSyncError();
        }
        if (remote.error == KoSyncClient.Error.OK && remote.progress != null) {
            remoteStore.recordFromOthers(hash, null, RemotePosition.from(remote.progress), deviceId);
            long remoteMs = remote.progress.timestampSec * 1000L;
            boolean fromOtherDevice = !deviceId.equals(remote.progress.deviceId);
            if (fromOtherDevice && remoteMs > localBasisMs) {
                applyRemote(book, remote.progress, total, remoteMs);
                return true;
            }
        } else if (remote.error != KoSyncClient.Error.NOT_FOUND) {
            // Can't tell who is newest — never push blind over what might be
            // another device's newer progress.
            settings.recordSyncError("Sync check failed: " + KoSyncClient.errorString(remote),
                    SyncProblem.classify(remote.error, SyncProblem.wifiHasInternet(this)));
            return false;
        }

        if (!mayPush) {
            return true;
        }
        return push(book, state, hash, total);
    }

    private boolean push(OnyxMetadataRepository.Book book, SyncStateStore.BookState state,
                         String hash, int total) {
        int page = book.currentPage();
        if (page <= 0) {
            Log.d(TAG, "push: unparseable progress '" + book.progress + "' for " + book.path);
            return true;
        }
        if (equalsSafe(book.progress, state.lastPushedProgress)) {
            return true; // already on the server
        }
        if (sinceLast(lastPushMs, book.path) < MIN_PUSH_INTERVAL_MS) {
            // Too soon after the last upload. Returning false keeps the old
            // snapshot, so the next poll re-detects this change and sends it.
            return false;
        }
        // Onyx gives no paragraph-level position, so progress is the page
        // number and percentage carries the cross-device position (CrossPoint
        // treats percentage as the primary sync mechanism).
        KoSyncClient.Result result = SyncActions.uploadLocal(client, stateStore, book, hash);
        if (result.error == KoSyncClient.Error.RATE_LIMITED) {
            backOff(result);
            return false;
        }
        if (result.error == KoSyncClient.Error.OK) {
            lastPushMs.put(book.path, System.currentTimeMillis());
            settings.recordSyncSuccess(book.name != null ? book.name : book.path, book.path,
                    SyncActions.localPercentage(book));
            Log.i(TAG, "Pushed " + book.progress + " (" + hash + ") for " + book.path);
            return true;
        }
        settings.recordSyncError("Push failed: " + KoSyncClient.errorString(result),
                SyncProblem.classify(result.error, SyncProblem.wifiHasInternet(this)));
        return false;
    }

    private void applyRemote(OnyxMetadataRepository.Book book, KoSyncClient.Progress remote,
                             int total, long remoteMs) {
        double pct = Math.max(0.0, Math.min(1.0, remote.percentage));
        int page = Math.max(1, Math.min(total, (int) Math.round(pct * total)));
        // "Same position" is judged in the shared byte-based percentage, not
        // pages: a remote byte percentage rounded onto local pages can be
        // several pages away from the page it actually corresponds to.
        double localPct = SyncActions.localPercentage(book);
        if (Math.abs(pct - localPct) < 0.5 / total) {
            remember(book);
            return; // same position, don't churn the provider
        }

        String written = SyncActions.applyRemote(this, repository, stateStore, book, remote);
        if (written != null) {
            // Update the snapshot to what we wrote so the next scan doesn't
            // mistake our own write for user activity.
            Seen seen = new Seen();
            seen.progress = written;
            seen.lastAccessMs = remoteMs;
            snapshot.put(book.path, seen);

            settings.recordSyncSuccess(book.name != null ? book.name : book.path, book.path,
                    pct);
            Log.i(TAG, "Pulled " + written + " from " + remote.device + " for " + book.path);
        }
    }

    /**
     * Records a NeoReader-authored (internal position, percentage) pair for
     * later percentage→position conversion on pulls. Skips rows we wrote
     * ourselves (their position is our estimate, not ground truth) and
     * positions too close to the start to calibrate reliably.
     */
    private void maybeCalibrate(OnyxMetadataRepository.Book book, SyncStateStore.BookState state) {
        if (equalsSafe(book.progress, state.lastWrittenProgress)) {
            return;
        }
        long position = book.positionV2();
        int page = book.currentPage();
        int total = book.totalPages();
        if (position < 0 || page <= 0 || total <= 0) {
            return;
        }
        double pct = (double) page / total;
        if (pct < 0.01) {
            return;
        }
        if (position != state.calibPosition || pct != state.calibPercentage) {
            state.calibPosition = position;
            state.calibPercentage = pct;
            stateStore.put(book.path, state);
        }
    }

    private static long sinceLast(Map<String, Long> times, String path) {
        Long last = times.get(path);
        return last == null ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    private void backOff(KoSyncClient.Result result) {
        // The pause itself is enforced inside KoSyncClient for every caller.
        settings.recordSyncError(KoSyncClient.errorString(result),
                SyncProblem.classify(result.error, SyncProblem.wifiHasInternet(this)));
    }

    private String documentHash(String path) {
        String hash = SyncActions.documentHash(settings, path);
        if (hash == null) {
            Log.w(TAG, "Could not hash " + path + " (file missing or unreadable?)");
            settings.recordSyncError("Cannot read book file: " + path, SyncProblem.Type.OTHER);
        }
        return hash;
    }

    private void remember(OnyxMetadataRepository.Book book) {
        Seen seen = new Seen();
        seen.progress = book.progress;
        seen.lastAccessMs = book.lastAccessMs;
        snapshot.put(book.path, seen);
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // --- Notification -------------------------------------------------------

    /**
     * "Positions not syncing · tap to fix" while the problem is an alarm, else
     * "<title> synced · 88%" for the latest successful sync (spec 7.7).
     */
    private void showStatus() {
        boolean alarm = SyncProblem.isAlarm(settings.getFirstFailureMs(),
                settings.getConsecutiveFailures(), System.currentTimeMillis());
        String path = settings.getLastBookPath();
        updateNotification(NotificationText.of(alarm, getString(R.string.notification_alarm),
                getString(R.string.notification_synced),
                alarm || path.isEmpty() ? null : metaTitle(path), path,
                settings.getLastPercent()));
    }

    /** The book's title from BookMetaCache, or null when it can't be read. */
    private String metaTitle(String path) {
        try {
            return metaCache.get(new File(path)).title;
        } catch (Exception e) {
            Log.d(TAG, "No title for a book, using its file name", e);
            return null;
        }
    }

    /**
     * Shows text as the notification's line (null: the default title). Only
     * re-posted when it changes. The notification opens the home screen.
     */
    void updateNotification(String text) {
        if (destroyed || (text == null ? shownText == null : text.equals(shownText))) {
            return;
        }
        shownText = text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        return builder
                .setContentTitle(shownText != null ? shownText : getString(R.string.notification_title))
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }
}
