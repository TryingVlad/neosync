package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import dev.neosync.boox.R;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.epub.EpubCover;
import dev.neosync.boox.jobs.CleanPlan;
import dev.neosync.boox.jobs.FixCoversJob;
import dev.neosync.boox.jobs.Job;
import dev.neosync.boox.jobs.SendMissingJob;
import dev.neosync.boox.kosync.KoReaderDocumentHash;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.SyncActions;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.kosync.SyncStateStore;
import dev.neosync.boox.library.BookMeta;
import dev.neosync.boox.library.BookMetaCache;
import dev.neosync.boox.library.LibraryEntry;
import dev.neosync.boox.library.LibraryModel;
import dev.neosync.boox.library.LibraryPager;
import dev.neosync.boox.library.LibraryScope;
import dev.neosync.boox.library.OnyxTitle;
import dev.neosync.boox.library.PiccoFinder;
import dev.neosync.boox.library.RemotePosition;
import dev.neosync.boox.library.RemotePositionStore;
import dev.neosync.boox.library.Suggestion;
import dev.neosync.boox.library.SuggestionPicker;
import dev.neosync.boox.library.SyncProblem;
import dev.neosync.boox.library.TitleCleaner;
import dev.neosync.boox.service.SyncService;
import dev.neosync.boox.transfer.BookSyncStatus;
import dev.neosync.boox.transfer.CoverFixer;
import dev.neosync.boox.transfer.PiccoClient;
import dev.neosync.boox.transfer.PiccoLibraryStore;
import dev.neosync.boox.update.UpdateChecker;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home: the two-device library (spec 5.1). Two status chips, one suggestion
 * slot (or a running job's progress card), filters and search, and the
 * merged Boox + Picco book list in fixed pages. First launch is routed
 * through the onboarding guide and the sign-in screen.
 */
public class MainActivity extends Activity implements JobSession.Screen {

    private static final String TAG = "MainActivity";
    /** A job's result sentence (or a notice) disappears after this long. */
    private static final long NOTICE_SHOWN_MS = 10_000;
    /** The Picco list refreshes by itself this often while home is open. */
    private static final long PICCO_RECHECK_MS = 60_000;

    // The last Picco check, kept across visits so the chip doesn't flash
    // "looking…" every time home opens. UI thread only.
    private static String checkedPiccoAddress;
    private static Boolean checkedPiccoReachable;
    // Document hash and Boox percentage per book version: both read the
    // book file, so they are worked out once per path + modified time.
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();
    private static final Map<String, Double> PERCENTAGES = new ConcurrentHashMap<>();

    // Package-visible for the book and cover sheets.
    SyncSettings settings;
    OnyxMetadataRepository repository;
    SyncStateStore stateStore;
    PiccoLibraryStore piccoLibrary;
    BookMetaCache metaCache;
    RemotePositionStore remoteStore;

    private View chipPositions;
    private TextView chipPositionsTitle;
    private TextView chipPositionsSub;
    private TextView chipPositionsChev;
    private View chipPositionsDot;
    private TextView chipPiccoTitle;
    private TextView chipPiccoSub;
    private View chipPiccoDot;
    private View slot;
    private View cardSuggest;
    private TextView suggestTitle;
    private TextView suggestBody;
    private Button suggestPrimary;
    private Button suggestSecondary;
    private View suggestDismiss;
    private View cardProgress;
    private TextView progressTitle;
    private TextView progressCount;
    private ProgressBar progressBar;
    private TextView progressNow;
    private Button progressStop;
    private TextView cardNotice;
    private TextView filterAll;
    private TextView filterReading;
    private TextView filterOnlyHere;
    private TextView filterOnlyPicco;
    private EditText searchField;
    private LinearLayout bookList;
    private TextView emptyView;
    private View pagerGroup;
    private Button pagePrev;
    private Button pageNext;
    private TextView pageText;

    /** What the last reload found; null until the first one is done. */
    private Snapshot snapshot;
    private boolean reloadRunning;
    private boolean reloadAgain;
    private LibraryModel.Filter filter = LibraryModel.Filter.ALL;
    private String query = "";
    private int page;
    private int pageSize = 1;
    private int rowHeightPx;
    /** What the rows on screen show; a redraw with the same content is skipped (e-ink). */
    private String shownPage;

    /** From a Send / Fix tap until its job ends or is called off. */
    private boolean busy;
    /** The book or cover sheet on screen, or null; closed with this screen. */
    private android.app.Dialog openSheet;
    /** The open book sheet, which shows its own single-book send. */
    private BookSheet bookSheet;
    /** A result sentence or a "checking…" line in the slot, or null. */
    private CharSequence notice;
    private final Runnable hideNotice = this::hideNotice;

    private boolean retrying;
    private boolean finding;
    private boolean findFailed;
    private UpdateChecker.Update update;

    // Picco list refresh bookkeeping: which run is current, and which
    // address it used (to re-check after the address changes in Settings).
    private int piccoCheckGeneration;
    private boolean piccoRefreshing;

    private final LruCache<String, Bitmap> thumbs = new LruCache<>(80);
    /** Books whose cover couldn't be made into a thumbnail; not tried again this visit. */
    private final Set<String> noThumb = new HashSet<>();
    private boolean thumbsLoading;
    private final ExecutorService thumbLoader = Executors.newSingleThreadExecutor();

    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            renderChips(); // "2 min ago" moves on
            if (!busy && !JobSession.running()) {
                refreshPiccoLibrary();
            }
            bookList.postDelayed(this, PICCO_RECHECK_MS);
        }
    };

    // Redraws the chips and the slot whenever the sync service records an
    // outcome, and reloads the rows (their status depends on it).
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            (prefs, key) -> runOnUiThread(() -> {
                if (SyncSettings.KEY_LIBRARY_FILTER.equals(key)
                        || SyncSettings.KEY_DISMISSED_SUGGESTIONS.equals(key)) {
                    return; // changed here, already drawn
                }
                if (SyncSettings.KEY_LAST_ATTEMPT_MS.equals(key)) {
                    retrying = false;
                }
                renderChips();
                renderSlot();
                reload();
            });

    // Keeps the chips and the problem card current as the Boox switches networks.
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    runOnUiThread(MainActivity.this::renderHeader);
                }

                @Override
                public void onLost(Network network) {
                    runOnUiThread(MainActivity.this::renderHeader);
                }
            };

    /** Everything one reload found, built off the UI thread. */
    private static final class Snapshot {
        List<LibraryEntry> entries = new ArrayList<>();
        Map<String, BookMeta> metaByKey = new HashMap<>();
        /** Keys of Boox books whose file NeoSync can read. */
        Set<String> readable = new HashSet<>();
        Map<String, Long> lastSyncMsByKey = new HashMap<>();
        boolean piccoConfigured;
        boolean hasPiccoList;
        long piccoListMs;
        int missingCount;
        String missingNames;
        long missingBytes;
        int blackCovers;
        boolean anyPosition;
    }

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
        repository = new OnyxMetadataRepository(this);
        stateStore = new SyncStateStore(this);
        piccoLibrary = new PiccoLibraryStore(this);
        metaCache = new BookMetaCache(this);
        remoteStore = new RemotePositionStore(this);

        findViewById(R.id.button_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        chipPositions = findViewById(R.id.chip_positions);
        chipPositionsTitle = findViewById(R.id.chip_positions_title);
        chipPositionsSub = findViewById(R.id.chip_positions_sub);
        chipPositionsChev = findViewById(R.id.chip_positions_chev);
        chipPositionsDot = findViewById(R.id.chip_positions_dot);
        chipPiccoTitle = findViewById(R.id.chip_picco_title);
        chipPiccoSub = findViewById(R.id.chip_picco_sub);
        chipPiccoDot = findViewById(R.id.chip_picco_dot);
        chipPositions.setOnClickListener(v -> openConnections());
        findViewById(R.id.chip_picco).setOnClickListener(v -> openConnections());

        slot = findViewById(R.id.slot);
        cardSuggest = findViewById(R.id.card_suggest);
        suggestTitle = findViewById(R.id.suggest_title);
        suggestBody = findViewById(R.id.suggest_body);
        suggestPrimary = findViewById(R.id.suggest_primary);
        suggestSecondary = findViewById(R.id.suggest_secondary);
        suggestDismiss = findViewById(R.id.suggest_dismiss);
        cardProgress = findViewById(R.id.card_progress);
        progressTitle = findViewById(R.id.progress_title);
        progressCount = findViewById(R.id.progress_count);
        progressBar = findViewById(R.id.progress_bar);
        progressNow = findViewById(R.id.progress_now);
        progressStop = findViewById(R.id.progress_stop);
        progressStop.setOnClickListener(v -> {
            JobSession.stop();
            renderSlot();
        });
        cardNotice = findViewById(R.id.card_notice);
        cardNotice.setOnClickListener(v -> hideNotice());

        filterAll = findViewById(R.id.filter_all);
        filterReading = findViewById(R.id.filter_reading);
        filterOnlyHere = findViewById(R.id.filter_only_here);
        filterOnlyPicco = findViewById(R.id.filter_only_picco);
        filterAll.setOnClickListener(v -> chooseFilter(LibraryModel.Filter.ALL));
        filterReading.setOnClickListener(v -> chooseFilter(LibraryModel.Filter.READING));
        filterOnlyHere.setOnClickListener(v -> chooseFilter(LibraryModel.Filter.ONLY_HERE));
        filterOnlyPicco.setOnClickListener(v -> chooseFilter(LibraryModel.Filter.ONLY_ON_PICCO));
        try {
            String saved = settings.getLibraryFilter();
            if (saved != null) {
                filter = LibraryModel.Filter.valueOf(saved);
            }
        } catch (IllegalArgumentException ignored) {
            // An older or unknown value: keep All.
        }
        searchField = findViewById(R.id.field_search);
        findViewById(R.id.button_search).setOnClickListener(v -> toggleSearch());
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                page = 0;
                renderPage();
            }
        });

        bookList = findViewById(R.id.list_books);
        emptyView = findViewById(R.id.text_empty);
        pagerGroup = findViewById(R.id.group_pager);
        pagePrev = findViewById(R.id.button_page_prev);
        pageNext = findViewById(R.id.button_page_next);
        pageText = findViewById(R.id.text_page);
        pagePrev.setOnClickListener(v -> turnPage(-1));
        pageNext.setOnClickListener(v -> turnPage(1));
        // The page size follows the space the list gets: it changes when the
        // suggestion card or the search field comes and goes, or on rotation.
        bookList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot || r - l != or - ol) {
                v.post(this::onListSizeChanged);
            }
        });

        if (!Permissions.hasNotifications(this)) {
            Permissions.requestNotifications(this);
        }
        SyncService.start(this);
        // A job started before this screen was (re)created carries on here.
        JobSession.attach(this);
        renderHeader();
        renderFilters();
        if (JobSession.resultUnseen) {
            JobSession.resultUnseen = false;
            jobFinished(JobSession.unseenResult);
        } else if (!JobSession.running()) {
            JobSession.rows.clear(); // its result was already shown
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bookList == null) {
            return; // finishing: routed to onboarding or sign-in
        }
        settings.registerListener(statusListener);
        try {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                    .registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "No network callback: " + e);
        }
        renderHeader();
        reload();
        refreshUpdate();
        if (!busy && !JobSession.running()) {
            refreshPiccoLibrary();
        }
        bookList.removeCallbacks(recheck);
        bookList.postDelayed(recheck, PICCO_RECHECK_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bookList == null) {
            return;
        }
        bookList.removeCallbacks(recheck);
        settings.unregisterListener(statusListener);
        try {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                    .unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            // Not registered (e.g. registration failed in onResume).
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (openSheet != null) {
            openSheet.dismiss(); // a running job carries on (JobSession)
        }
        JobSession.detach(this);
        thumbLoader.shutdownNow();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (bookList != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    turnPage(1);
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_VOLUME_UP:
                    turnPage(-1);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // --- Hooks for the book sheet ------------------------------------------------

    /** Opens the sheet for one book (spec 5.2). */
    void openBook(LibraryEntry e) {
        if (snapshot == null || openSheet != null || gone(this)) {
            return;
        }
        BookSheet.show(this, e);
    }

    void sheetOpened(android.app.Dialog d) {
        openSheet = d;
    }

    void sheetClosed(android.app.Dialog d) {
        if (openSheet == d) {
            openSheet = null;
        }
    }

    void bookSheetOpened(BookSheet s, android.app.Dialog d) {
        bookSheet = s;
        sheetOpened(d);
    }

    void bookSheetClosed(BookSheet s, android.app.Dialog d) {
        if (bookSheet == s) {
            bookSheet = null;
        }
        sheetClosed(d);
    }

    boolean isBusy() {
        return busy;
    }

    void setBusy(boolean b) {
        busy = b;
        renderSlot();
    }

    /**
     * True when a background result must not show a dialog or sheet here:
     * finishing, or destroyed by a rotation (the window is gone; showing
     * would throw BadTokenException). Callers release busy when they bail.
     */
    static boolean gone(Activity activity) {
        return activity.isFinishing() || activity.isDestroyed();
    }

    boolean piccoConfigured() {
        return !settings.getPiccoAddress().isEmpty();
    }

    /** A Picco is set up and its book list is known (so "not on the Picco" means something). */
    boolean hasPiccoList() {
        String address = settings.getPiccoAddress();
        return !address.isEmpty() && piccoLibrary.hasList(address);
    }

    /** The book's metadata when its file can be read; null otherwise. */
    BookMeta readableMeta(LibraryEntry e) {
        if (snapshot == null || e.booxPath == null || !snapshot.readable.contains(e.key)) {
            return null;
        }
        return snapshot.metaByKey.get(e.key);
    }

    /** The list's thumbnail for a Boox book, or null. */
    Bitmap thumbOf(String path) {
        return thumbs.get(path);
    }

    /** Re-reads both libraries and redraws; the work runs off the UI thread. */
    void reload() {
        if (reloadRunning) {
            reloadAgain = true;
            return;
        }
        reloadRunning = true;
        final String address = settings.getPiccoAddress();
        final boolean syncing = isSyncing();
        new Thread(() -> {
            Snapshot s;
            try {
                s = buildSnapshot(address, syncing);
            } catch (Exception e) {
                Log.e(TAG, "Library reload failed", e);
                s = null;
            }
            final Snapshot result = s;
            runOnUiThread(() -> {
                reloadRunning = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (result != null) {
                    snapshot = result;
                    render();
                }
                if (reloadAgain) {
                    reloadAgain = false;
                    reload();
                }
            });
        }, "NeoSync library").start();
    }

    private void openConnections() {
        startActivity(new Intent(this, ConnectionsActivity.class));
    }

    // --- Building the library ----------------------------------------------------

    /**
     * The library's books, skipping Onyx cloud copies, NeoSync's backups and
     * files deleted since. Home and every job read the library through this.
     */
    private List<OnyxMetadataRepository.Book> listableBooks() {
        return LibraryModel.listable(repository.queryAllBooks(), book -> book.path,
                path -> new File(path).exists(), Permissions.hasAllFilesAccess());
    }

    private Snapshot buildSnapshot(String address, boolean syncing) {
        Snapshot s = new Snapshot();
        boolean filenameMode = SyncSettings.MATCH_FILENAME.equals(settings.getMatchMethod());
        // Only the list shows the chosen scope; the suggestion counts (missing on
        // the Picco, black covers) still cover every book, as the jobs do.
        LibraryScope scope = settings.getLibraryScope();
        List<LibraryModel.BooxBook> boox = new ArrayList<>();
        List<LibraryModel.BooxBook> everyBoox = new ArrayList<>();
        Map<String, RemotePosition> remoteByKey = new HashMap<>();
        // Most recently read first, so the first copy of a duplicate name is
        // the one LibraryModel.merge keeps.
        for (OnyxMetadataRepository.Book book : listableBooks()) {
            if (!LibraryModel.isBookFile(book.path)) {
                continue; // Onyx also lists note files (.md) and the like
            }
            File file = new File(book.path);
            String key = LibraryModel.keyOf(book.path);
            boolean readable = file.canRead();
            BookMeta meta = metaCache.get(file);
            if (!CoverFixer.isEpub(file)) {
                // NeoSync can't read a PDF's title; Neo Reader has one.
                String[] ta = OnyxTitle.choose(book.title, book.authors, file.getName());
                meta = new BookMeta(ta[0], ta[1], meta.hasCover, meta.wouldShowBlack);
            }
            SyncStateStore.BookState state = stateStore.get(book.path);
            BookSyncStatus status = BookSyncStatus.of(true, book.progress, book.currentPage(),
                    state.lastPushedProgress, state.lastWrittenProgress);
            double pct = book.progress != null && book.totalPages() > 0
                    ? percentage(book, file) : -1;
            LibraryModel.BooxBook entry = new LibraryModel.BooxBook(book.path, pct,
                    book.lastAccessMs, status, meta.title, meta.author);
            everyBoox.add(entry);
            if (scope.includes(book.path)) {
                boox.add(entry);
            }
            if (s.metaByKey.containsKey(key)) {
                continue;
            }
            s.metaByKey.put(key, meta);
            s.lastSyncMsByKey.put(key, state.lastSyncMs);
            if (readable) {
                s.readable.add(key);
            }
            String hash = documentHash(book.path, file);
            RemotePosition remote = hash != null ? remoteStore.get(hash) : null;
            if (remote != null) {
                remoteByKey.put(key, remote);
            }
        }
        s.piccoConfigured = !address.isEmpty();
        s.hasPiccoList = s.piccoConfigured && piccoLibrary.hasList(address);
        s.piccoListMs = piccoLibrary.getUpdatedMs();
        List<String> onPicco = s.piccoConfigured ? piccoLibrary.getBooks(address)
                : new ArrayList<>();
        if (filenameMode) {
            // Books only on the Picco: their record is found by the file
            // name's hash. In binary mode there is no file to hash.
            for (String path : onPicco) {
                String key = LibraryModel.keyOf(path);
                if (!s.metaByKey.containsKey(key) && !remoteByKey.containsKey(key)) {
                    String hash = KoReaderDocumentHash.fromFilename(path);
                    RemotePosition remote = hash != null ? remoteStore.get(hash) : null;
                    if (remote != null) {
                        remoteByKey.put(key, remote);
                    }
                }
            }
        }
        s.entries = LibraryModel.merge(boox, onPicco, remoteByKey, s.hasPiccoList, syncing);

        for (LibraryEntry e : s.entries) {
            if (e.booxPct > 0 || e.piccoPct > 0 || e.remote != null) {
                s.anyPosition = true;
            }
        }
        List<String> missingTitles = new ArrayList<>();
        List<LibraryEntry> everyEntry = boox.size() == everyBoox.size() ? s.entries
                : LibraryModel.merge(everyBoox, onPicco, remoteByKey, s.hasPiccoList, syncing);
        for (LibraryEntry e : everyEntry) {
            if (e.booxPath == null || !e.isEpub || !s.readable.contains(e.key)) {
                continue;
            }
            BookMeta meta = s.metaByKey.get(e.key);
            if (meta != null && meta.wouldShowBlack) {
                s.blackCovers++;
            }
            if (s.hasPiccoList && e.piccoPath == null) {
                s.missingCount++;
                s.missingBytes += new File(e.booxPath).length();
                missingTitles.add(e.title);
            }
        }
        if (!missingTitles.isEmpty()) {
            s.missingNames = missingTitles.size() <= 2 ? String.join(", ", missingTitles)
                    : getString(R.string.sug_names_more,
                            String.join(", ", missingTitles.subList(0, 2)),
                            missingTitles.size() - 2);
        }
        return s;
    }

    /** The book's document hash, worked out once per file version. */
    String documentHash(String path, File file) {
        String cacheKey = settings.getMatchMethod() + "|" + path + "|" + file.lastModified()
                + "|" + file.length();
        String hash = HASHES.get(cacheKey);
        if (hash == null) {
            hash = SyncActions.documentHash(settings, path);
            if (hash != null) {
                HASHES.put(cacheKey, hash);
            }
        }
        return hash;
    }

    /** SyncActions.localPercentage, once per file version and position. */
    private static double percentage(OnyxMetadataRepository.Book book, File file) {
        String cacheKey = book.path + "|" + file.lastModified() + "|" + file.length() + "|"
                + book.progress + "|" + Arrays.toString(book.chapterPosition());
        Double pct = PERCENTAGES.get(cacheKey);
        if (pct == null) {
            pct = SyncActions.localPercentage(book);
            PERCENTAGES.put(cacheKey, pct);
        }
        return pct;
    }

    private boolean isAlarm() {
        return SyncProblem.isAlarm(settings.getFirstFailureMs(),
                settings.getConsecutiveFailures(), System.currentTimeMillis());
    }

    /** Positions are flowing: sync is on and no problem has lasted. */
    private boolean isSyncing() {
        return settings.isAutoSyncEnabled() && !isAlarm();
    }

    /** The last check's answer for the current address; null when unknown. */
    private Boolean piccoReachable() {
        return lastPiccoCheck(settings.getPiccoAddress());
    }

    /** The last check's answer for this address (any screen's); null when unknown. Main thread. */
    static Boolean lastPiccoCheck(String address) {
        return address.equals(checkedPiccoAddress) ? checkedPiccoReachable : null;
    }

    /** Records whether the Picco at this address just answered (any screen). Main thread. */
    static void rememberPiccoCheck(String address, boolean reachable) {
        checkedPiccoAddress = address;
        checkedPiccoReachable = reachable;
    }

    // --- Rendering ----------------------------------------------------------------

    private void render() {
        renderHeader();
        renderFilters();
        renderPage();
    }

    private void renderHeader() {
        if (bookList == null || isFinishing()) {
            return;
        }
        renderChips();
        renderSlot();
    }

    private void renderChips() {
        long now = System.currentTimeMillis();
        boolean alarm = false;
        boolean good = false;
        String title;
        String sub;
        long lastSync = settings.getLastSyncMs();
        if (!settings.isAutoSyncEnabled()) {
            title = getString(R.string.chip_paused);
            sub = getString(R.string.chip_paused_sub);
        } else if (isAlarm()) {
            alarm = true;
            title = getString(R.string.chip_not_syncing);
            sub = getString(R.string.chip_not_syncing_sub, clock(settings.getFirstFailureMs()));
        } else if (lastSync > 0) {
            good = true;
            title = getString(R.string.chip_positions_synced);
            String ago = now - lastSync < DateUtils.MINUTE_IN_MILLIS
                    ? getString(R.string.chip_just_now)
                    : DateUtils.getRelativeTimeSpanString(lastSync, now,
                            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString();
            String book = settings.getLastBook();
            sub = book.isEmpty() ? ago : getString(R.string.chip_synced_sub, ago, bookTitle(book));
        } else {
            title = getString(R.string.chip_signed_in);
            sub = getString(R.string.chip_nothing_synced);
        }
        setText(chipPositionsTitle, title);
        setText(chipPositionsSub, sub);
        int ink = getColor(alarm ? R.color.white : R.color.black);
        setBackground(chipPositions, alarm ? R.drawable.chip_bg_alarm : R.drawable.chip_bg);
        chipPositionsTitle.setTextColor(ink);
        chipPositionsSub.setTextColor(alarm ? ink : getColor(R.color.ink_soft));
        chipPositionsChev.setTextColor(alarm ? ink : getColor(R.color.ink_soft));
        setBackground(chipPositionsDot, good ? R.drawable.status_dot_ok
                : R.drawable.status_dot_idle);

        String address = settings.getPiccoAddress();
        Boolean reachable = piccoReachable();
        boolean connected = false;
        if (address.isEmpty()) {
            title = getString(R.string.chip_picco);
            sub = getString(R.string.chip_picco_not_set_up);
        } else if (Boolean.TRUE.equals(reachable)) {
            connected = true;
            title = getString(R.string.chip_picco_connected);
            sub = address;
        } else if (reachable == null && piccoRefreshing) {
            title = getString(R.string.chip_picco);
            sub = getString(R.string.chip_picco_looking);
        } else {
            title = getString(R.string.chip_picco_not_connected);
            sub = piccoLibrary.hasList(address)
                    ? getString(R.string.chip_picco_list_from, clock(piccoLibrary.getUpdatedMs()))
                    : getString(R.string.chip_picco_no_list);
        }
        setText(chipPiccoTitle, title);
        setText(chipPiccoSub, sub);
        setBackground(chipPiccoDot, connected ? R.drawable.status_dot_ok
                : R.drawable.status_dot_idle);
    }

    /**
     * The slot shows a running job, else a notice, else the picked
     * suggestion. Views change only when their content does (this runs on
     * every network change, and each change is an e-ink redraw).
     */
    private void renderSlot() {
        if (slot == null) {
            return;
        }
        View shown = null;
        if (JobSession.running()) {
            renderProgressCard();
            shown = cardProgress;
        } else if (notice != null) {
            setText(cardNotice, notice);
            shown = cardNotice;
        } else {
            Suggestion suggestion = snapshot != null ? SuggestionPicker.pick(pickerInput()) : null;
            if (suggestion != null && renderSuggestion(suggestion)) {
                shown = cardSuggest;
            }
        }
        setVisible(cardProgress, shown == cardProgress);
        setVisible(cardNotice, shown == cardNotice);
        setVisible(cardSuggest, shown == cardSuggest);
        setVisible(slot, shown != null);
    }

    /** Sets a background drawable only when it differs from the last one set here. */
    private static void setBackground(View view, int drawable) {
        if (!Integer.valueOf(drawable).equals(view.getTag(R.id.tag_background))) {
            view.setBackgroundResource(drawable);
            view.setTag(R.id.tag_background, drawable);
        }
    }

    private static void setVisible(View view, boolean visible) {
        int wanted = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != wanted) {
            view.setVisibility(wanted);
        }
    }

    private SuggestionPicker.Input pickerInput() {
        SuggestionPicker.Input in = new SuggestionPicker.Input();
        in.jobRunning = JobSession.running();
        in.fileAccessMissing = !Permissions.hasAllFilesAccess();
        in.autoSyncOn = settings.isAutoSyncEnabled();
        in.problem = settings.getProblemType();
        in.problemIsAlarm = isAlarm();
        in.piccoConfigured = !settings.getPiccoAddress().isEmpty();
        in.piccoReachable = Boolean.TRUE.equals(piccoReachable());
        in.missingOnPicco = snapshot.missingCount;
        in.missingNames = snapshot.missingNames;
        in.missingBytes = snapshot.missingBytes;
        in.blackCovers = snapshot.blackCovers;
        in.updateVersion = update != null ? update.version : null;
        in.dismissed = settings.getDismissedSuggestions();
        return in;
    }

    /** Fills the suggestion card; false for a kind it doesn't know. */
    private boolean renderSuggestion(Suggestion s) {
        String title;
        String body;
        String button;
        boolean primary;
        boolean dismissible = true;
        boolean enabled = true;
        Runnable action;
        switch (s.kind) {
            case FILE_ACCESS:
                title = getString(R.string.sug_file_access_title);
                body = getString(R.string.sug_file_access_body);
                button = getString(R.string.button_allow);
                primary = true;
                dismissible = false;
                action = () -> Permissions.openAllFilesAccessSettings(this);
                break;
            case PROBLEM_NO_INTERNET:
                title = getString(R.string.sug_no_internet_title);
                body = getString(R.string.sug_no_internet_body);
                button = getString(R.string.sug_no_internet_button);
                primary = true;
                dismissible = false;
                action = () -> WifiHelper.openWifiPicker(this);
                break;
            case PROBLEM_AUTH:
                title = getString(R.string.sug_auth_title);
                body = getString(R.string.sug_auth_body);
                button = getString(R.string.button_sign_in);
                primary = true;
                dismissible = false;
                action = () -> startActivity(new Intent(this, SignInActivity.class));
                break;
            case PROBLEM_SERVER:
                title = getString(R.string.sug_server_title);
                body = getString(R.string.sug_server_body);
                button = getString(retrying ? R.string.status_retrying : R.string.button_retry_sync);
                primary = true;
                dismissible = false;
                enabled = !retrying;
                action = this::retrySync;
                break;
            case PROBLEM_PAUSED:
                title = getString(R.string.sug_paused_title);
                body = getString(R.string.sug_paused_body);
                button = getString(R.string.sug_paused_button);
                primary = true;
                dismissible = false;
                action = this::turnSyncOn;
                break;
            case SETUP_PICCO:
                title = getString(R.string.sug_setup_title);
                body = getString(findFailed ? R.string.sug_setup_not_found : R.string.sug_setup_body);
                button = getString(finding ? R.string.sug_setup_looking : R.string.sug_setup_button);
                primary = true;
                enabled = !finding;
                action = this::findPicco;
                break;
            case SEND_MISSING:
                title = getResources().getQuantityString(R.plurals.sug_send_title, s.count, s.count);
                body = getString(R.string.sug_send_body, s.detail,
                        android.text.format.Formatter.formatShortFileSize(this, s.bytes));
                button = getString(R.string.sug_send_button);
                primary = false;
                enabled = !busy;
                action = this::sendMissingBooks;
                break;
            case FIX_COVERS:
                title = getResources().getQuantityString(R.plurals.sug_fix_title, s.count, s.count);
                body = getString(R.string.sug_fix_body);
                button = getString(R.string.sug_fix_button);
                primary = false;
                enabled = !busy;
                action = this::cleanLibrary;
                break;
            case UPDATE:
                title = getString(R.string.sug_update_title, s.detail);
                body = getString(R.string.sug_update_body);
                button = getString(R.string.button_get_update);
                primary = false;
                action = () -> {
                    if (update != null) {
                        SettingsActivity.openDownload(this, update);
                    }
                };
                break;
            default:
                return false;
        }
        // Problems and setup are solid cards with the one black button;
        // suggestions that can wait are dashed.
        setBackground(cardSuggest, primary ? R.drawable.card_bg : R.drawable.suggest_bg);
        setText(suggestTitle, title);
        setText(suggestBody, body);
        Button shown = primary ? suggestPrimary : suggestSecondary;
        setVisible(primary ? suggestSecondary : suggestPrimary, false);
        setText(shown, button);
        if (shown.isEnabled() != enabled) {
            shown.setEnabled(enabled);
        }
        shown.setOnClickListener(v -> action.run());
        setVisible(shown, true);
        setVisible(suggestDismiss, dismissible);
        suggestDismiss.setOnClickListener(v -> {
            settings.addDismissedSuggestion(s.dismissKey());
            renderSlot();
        });
        return true;
    }

    private void renderProgressCard() {
        Job job = JobSession.job;
        int total = JobSession.total;
        setText(progressTitle, job.title(this));
        setText(progressCount, total > 0
                ? getString(R.string.job_count,
                        Math.min(total, shownIndex(JobSession.done, JobSession.state)), total)
                : "");
        int progress = total > 0 ? JobSession.done * 1000 / total : 0;
        if (progressBar.getProgress() != progress) {
            progressBar.setProgress(progress);
        }
        boolean talksToPicco = job instanceof SendMissingJob || job instanceof FixCoversJob;
        setText(progressNow, JobSession.key == null ? ""
                : getString(talksToPicco ? R.string.job_now_picco : R.string.job_now,
                        titleOf(JobSession.key)));
        setText(progressStop, getString(JobSession.stopping ? R.string.job_stopping
                : R.string.job_stop));
        if (progressStop.isEnabled() == JobSession.stopping) {
            progressStop.setEnabled(!JobSession.stopping);
        }
    }

    private void renderFilters() {
        if (filterAll == null) {
            return;
        }
        boolean piccoConfigured = !settings.getPiccoAddress().isEmpty();
        if (!piccoConfigured && filter == LibraryModel.Filter.ONLY_ON_PICCO) {
            filter = LibraryModel.Filter.ALL;
        }
        int all = snapshot != null
                ? LibraryModel.count(snapshot.entries, LibraryModel.Filter.ALL) : 0;
        setText(filterAll, getString(R.string.filter_all, all));
        filterOnlyPicco.setVisibility(piccoConfigured ? View.VISIBLE : View.GONE);
        styleFilter(filterAll, filter == LibraryModel.Filter.ALL);
        styleFilter(filterReading, filter == LibraryModel.Filter.READING);
        styleFilter(filterOnlyHere, filter == LibraryModel.Filter.ONLY_HERE);
        styleFilter(filterOnlyPicco, filter == LibraryModel.Filter.ONLY_ON_PICCO);
    }

    private void styleFilter(TextView view, boolean on) {
        view.setBackgroundResource(on ? R.drawable.filter_on : R.drawable.filter_off);
        view.setTextColor(getColor(on ? R.color.white : R.color.black));
    }

    private void chooseFilter(LibraryModel.Filter f) {
        if (f == filter) {
            return;
        }
        filter = f;
        settings.setLibraryFilter(f.name());
        page = 0;
        renderFilters();
        renderPage();
    }

    private void toggleSearch() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (searchField.getVisibility() == View.VISIBLE) {
            searchField.setText("");
            searchField.setVisibility(View.GONE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
            }
        } else {
            searchField.setVisibility(View.VISIBLE);
            searchField.requestFocus();
            if (imm != null) {
                imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    private void turnPage(int delta) {
        if (snapshot == null) {
            return;
        }
        LibraryPager pager = new LibraryPager(visibleEntries().size(), pageSize);
        int next = pager.clamp(page + delta);
        if (next != page) {
            page = next;
            renderPage();
        }
    }

    private List<LibraryEntry> visibleEntries() {
        return snapshot == null ? new ArrayList<>()
                : LibraryModel.filter(snapshot.entries, filter, query);
    }

    /** Rows that fit the list's height; re-run whenever that height changes. */
    private void onListSizeChanged() {
        if (isFinishing() || bookList.getHeight() <= 0) {
            return;
        }
        int size = Math.max(1, bookList.getHeight() / rowHeight());
        if (size != pageSize) {
            pageSize = size;
            renderPage();
        }
    }

    /** One row's height, measured once from item_book at the list's width. */
    private int rowHeight() {
        if (rowHeightPx <= 0) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_book, bookList, false);
            ((TextView) row.findViewById(R.id.book_title)).setText("Ag");
            ((TextView) row.findViewById(R.id.book_author)).setText("Ag");
            ((TextView) row.findViewById(R.id.book_status)).setText("Ag");
            ((TextView) row.findViewById(R.id.book_pct_boox)).setText("88%");
            ((TextView) row.findViewById(R.id.book_pct_picco)).setText("88%");
            row.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, bookList.getWidth()),
                            View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            rowHeightPx = Math.max(1, row.getMeasuredHeight());
        }
        return rowHeightPx;
    }

    private void renderPage() {
        if (bookList == null || snapshot == null) {
            return;
        }
        List<LibraryEntry> visible = visibleEntries();
        LibraryPager pager = new LibraryPager(visible.size(), pageSize);
        page = pager.clamp(page);
        List<LibraryEntry> rows = visible.subList(pager.from(page), pager.to(page));

        if (snapshot.entries.isEmpty()) {
            emptyView.setText(R.string.library_none);
            emptyView.setVisibility(View.VISIBLE);
        } else if (visible.isEmpty()) {
            emptyView.setText(R.string.library_no_match);
            emptyView.setVisibility(View.VISIBLE);
        } else if (!snapshot.anyPosition) {
            emptyView.setText(getText(R.string.library_empty_state));
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
        int pages = pager.pageCount();
        pagerGroup.setVisibility(pages > 1 ? View.VISIBLE : View.GONE);
        setText(pageText, getString(R.string.page_of, page + 1, pages));
        pagePrev.setEnabled(page > 0);
        pageNext.setEnabled(page < pages - 1);

        // Skip the redraw when nothing on the page changed (e-ink).
        StringBuilder sig = new StringBuilder();
        List<String> needThumbs = new ArrayList<>();
        for (LibraryEntry e : rows) {
            sig.append(e.key).append('|').append(e.title).append('|').append(e.author)
                    .append('|').append(statusText(e)).append('|').append(pct(e.booxPct))
                    .append('|').append(pct(e.piccoPct)).append('|').append(coverState(e))
                    .append('\n');
            if (wantsThumb(e) && thumbs.get(e.booxPath) == null) {
                needThumbs.add(e.booxPath);
            }
        }
        String signature = sig.toString();
        if (!signature.equals(shownPage)) {
            shownPage = signature;
            bookList.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(this);
            for (LibraryEntry e : rows) {
                View row = inflater.inflate(R.layout.item_book, bookList, false);
                bindRow(row, e);
                bookList.addView(row);
            }
        }
        loadThumbs(needThumbs);
    }

    private void bindRow(View row, LibraryEntry e) {
        ((TextView) row.findViewById(R.id.book_title)).setText(e.title);
        TextView author = row.findViewById(R.id.book_author);
        author.setText(e.author);
        // No blank line for a missing author. Pages are sized for rows with
        // one, so a page of such rows is under-filled, never clipped.
        author.setVisibility(e.author.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) row.findViewById(R.id.book_status)).setText(statusText(e));
        bindBar(row.findViewById(R.id.book_bar_boox), row.findViewById(R.id.book_pct_boox),
                e.booxPct);
        bindBar(row.findViewById(R.id.book_bar_picco), row.findViewById(R.id.book_pct_picco),
                e.piccoPct);
        View cover = row.findViewById(R.id.book_cover);
        TextView coverText = row.findViewById(R.id.book_cover_text);
        ImageView coverImage = row.findViewById(R.id.book_cover_image);
        String state = coverState(e);
        if ("black".equals(state)) {
            // How the Picco itself would show it.
            cover.setBackgroundColor(getColor(R.color.black));
            coverText.setText("");
        } else if ("thumb".equals(state)) {
            coverImage.setImageBitmap(thumbs.get(e.booxPath));
            coverImage.setVisibility(View.VISIBLE);
            coverText.setText("");
        } else {
            coverText.setText(e.title);
        }
        row.setOnClickListener(v -> openBook(e));
    }

    private void bindBar(ProgressBar bar, TextView label, double pct) {
        if (pct < 0) {
            bar.setVisibility(View.INVISIBLE);
            label.setText(R.string.bar_none);
        } else {
            bar.setVisibility(View.VISIBLE);
            bar.setProgress((int) Math.round(Math.min(1, pct) * 1000));
            label.setText(pct(pct));
        }
    }

    private String pct(double pct) {
        return pct < 0 ? "-" : getString(R.string.pct, (int) Math.round(Math.min(1, pct) * 100));
    }

    /** "black", "thumb" or "title": what the cover tile shows. */
    private String coverState(LibraryEntry e) {
        BookMeta meta = e.booxPath != null ? snapshot.metaByKey.get(e.key) : null;
        if (meta == null || !snapshot.readable.contains(e.key)) {
            return "title";
        }
        if (e.isEpub && meta.wouldShowBlack) {
            return "black";
        }
        return thumbs.get(e.booxPath) != null ? "thumb" : "title";
    }

    private boolean wantsThumb(LibraryEntry e) {
        BookMeta meta = e.booxPath != null ? snapshot.metaByKey.get(e.key) : null;
        // Only books with a cover picture: BookMetaCache keeps no "no
        // thumbnail" answer, so asking for others would re-open them each time.
        return meta != null && meta.hasCover && !meta.wouldShowBlack
                && snapshot.readable.contains(e.key) && !noThumb.contains(e.booxPath);
    }

    /** Makes the page's missing thumbnails in one go, then redraws once. */
    private void loadThumbs(List<String> paths) {
        if (paths.isEmpty() || thumbsLoading) {
            return;
        }
        thumbsLoading = true;
        final int w = dp(34);
        final int h = dp(48);
        thumbLoader.execute(() -> {
            Map<String, Bitmap> made = new HashMap<>();
            for (String path : paths) {
                made.put(path, metaCache.thumbnail(new File(path), w, h));
            }
            runOnUiThread(() -> {
                thumbsLoading = false;
                for (Map.Entry<String, Bitmap> en : made.entrySet()) {
                    if (en.getValue() != null) {
                        thumbs.put(en.getKey(), en.getValue());
                    } else {
                        noThumb.add(en.getKey());
                    }
                }
                if (!isFinishing()) {
                    renderPage();
                }
            });
        });
    }

    /** The row's status line: the job's state for this book while it has one. */
    private String statusText(LibraryEntry e) {
        String jobRow = JobSession.rows.get(e.key);
        if (jobRow != null) {
            switch (jobRow) {
                case Job.SENT:
                    return getString(R.string.row_job_sent);
                case Job.SENDING:
                    return getString(R.string.row_job_sending);
                case Job.WAITING:
                    return getString(R.string.row_job_waiting);
                case Job.FETCHED:
                    return getString(R.string.row_job_fetched);
                case Job.SENT_PLACE:
                    return getString(R.string.row_job_sent_place);
                case Job.FAILED:
                    return getString(R.string.row_job_failed);
                default:
                    break;
            }
        }
        switch (e.status) {
            case SAME_PLACE:
                Long ms = snapshot.lastSyncMsByKey.get(e.key);
                return ms != null && ms > 0 ? getString(R.string.row_same_place, clock(ms))
                        : getString(R.string.row_same_place_no_time);
            case READ_FURTHER_HERE:
                return getString(R.string.row_read_further);
            case WAITING_TO_SYNC:
                return getString(R.string.row_waiting);
            case PICCO_AHEAD:
                return getString(R.string.row_picco_ahead);
            case NOT_ON_PICCO:
                return getString(R.string.row_not_on_picco);
            case ONLY_ON_PICCO:
                return getString(R.string.row_only_on_picco);
            case NOT_SYNCED_YET:
                return getString(R.string.row_not_synced);
            case BOOX_ONLY_TYPE:
                return getString(R.string.row_boox_only_type, fileType(e.booxPath));
            default:
                return getString(R.string.row_not_opened);
        }
    }

    private static String fileType(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : name;
    }

    /** The library title for a key, else a cleaned file name. */
    private String titleOf(String key) {
        if (snapshot != null) {
            for (LibraryEntry e : snapshot.entries) {
                if (e.key.equals(key)) {
                    return e.title;
                }
            }
        }
        return TitleCleaner.fromFileName(key);
    }

    /** settings.getLastBook() (an Onyx name or path) as a title. */
    private String bookTitle(String nameOrPath) {
        return titleOf(LibraryModel.keyOf(nameOrPath));
    }

    /** "13:37" today, else a short date and time. */
    private String clock(long ms) {
        if (DateUtils.isToday(ms)) {
            return DateFormat.getTimeFormat(this).format(new java.util.Date(ms));
        }
        return DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_TIME);
    }

    /** Sets text only when it differs, so an unchanged view isn't redrawn (e-ink). */
    private static void setText(TextView view, CharSequence text) {
        if (!android.text.TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // --- Notices and jobs in the slot -------------------------------------------

    /** Shows a line in the slot; autoHide for results (gone after 10 s or a tap). */
    private void showNotice(CharSequence text, boolean autoHide) {
        notice = text;
        bookList.removeCallbacks(hideNotice);
        if (autoHide) {
            bookList.postDelayed(hideNotice, NOTICE_SHOWN_MS);
        }
        renderSlot();
    }

    private void hideNotice() {
        bookList.removeCallbacks(hideNotice);
        notice = null;
        if (!JobSession.running() && !JobSession.rows.isEmpty()) {
            JobSession.rows.clear(); // the rows go back to their own status
            renderPage();
        }
        renderSlot();
    }

    /**
     * Runs a long job in the slot's progress card (busy is already set); its
     * rows show their state, and the result sentence follows. The job is
     * process-wide (JobSession), so it carries on if this screen is recreated.
     */
    boolean startJob(Job job) {
        boolean started = JobSession.start(job);
        if (started) {
            bookList.removeCallbacks(hideNotice);
            notice = null;
        } else {
            busy = false;
            JobSession.rows.clear();
            renderPage();
        }
        renderSlot();
        return started;
    }

    @Override
    public void jobProgress() {
        renderSlot();
        if (bookSheet != null) {
            bookSheet.jobProgress();
        }
    }

    @Override
    public void jobRows() {
        renderPage();
    }

    @Override
    public void jobFinished(String resultSentence) {
        busy = false;
        if (bookSheet != null) {
            bookSheet.jobFinished(resultSentence);
        }
        showNotice(resultSentence != null ? resultSentence : getString(R.string.job_stopped), true);
        reload();
    }

    /** "3 of 10": the book being worked on, or the last one finished. */
    private static int shownIndex(int done, String rowState) {
        return rowState == null || Job.SENDING.equals(rowState) || Job.WAITING.equals(rowState)
                ? done + 1 : done;
    }

    // --- Suggestion actions --------------------------------------------------------

    /**
     * Asks the sync service to try again now. The card updates through
     * statusListener once the attempt is recorded; the timeout only guards
     * against an attempt that never reports back (service killed meanwhile).
     */
    private void retrySync() {
        retrying = true;
        renderSlot();
        SyncService.syncNow(this);
        bookList.postDelayed(() -> {
            if (retrying) {
                retrying = false;
                renderSlot();
            }
        }, 60_000);
    }

    private void turnSyncOn() {
        settings.setAutoSyncEnabled(true);
        SyncService.start(this);
        renderHeader();
        reload();
    }

    /** Looks for the Picco on this network (PiccoFinder), then lists its books. */
    private void findPicco() {
        if (finding) {
            return;
        }
        finding = true;
        findFailed = false;
        renderSlot();
        final String lastKnown = settings.getPiccoAddress();
        final String[] net = WifiHelper.localNetwork(this);
        new Thread(() -> {
            List<String> candidates = PiccoFinder.candidates(lastKnown, net[0], net[1]);
            String found = PiccoFinder.find(candidates,
                    address -> new PiccoClient(address).probe(PiccoFinder.PROBE_TIMEOUT_MS),
                    PiccoFinder.THREADS, PiccoFinder.DEADLINE_MS);
            runOnUiThread(() -> {
                finding = false;
                if (found != null) {
                    settings.setPiccoAddress(found);
                    refreshPiccoLibrary();
                } else {
                    findFailed = true;
                }
                render();
            });
        }, "NeoSync find Picco").start();
    }

    // --- The Picco's book list -----------------------------------------------------

    /**
     * Fetches the Picco's book list in the background and saves it. The saved
     * list stays on screen meanwhile and when the Picco can't be reached. A
     * newer refresh (another address) makes an older one's result irrelevant.
     */
    private void refreshPiccoLibrary() {
        final int generation = ++piccoCheckGeneration;
        final String address = settings.getPiccoAddress();
        if (address.isEmpty()) {
            piccoRefreshing = false;
            renderChips();
            return;
        }
        piccoRefreshing = true;
        renderChips();
        new Thread(() -> {
            boolean listed = false;
            try {
                piccoLibrary.save(address, new PiccoClient(address).listBooks());
                listed = true;
            } catch (IOException e) {
                Log.d(TAG, "Picco listing failed: " + e);
            } catch (Exception e) {
                Log.e(TAG, "Picco listing error", e);
            }
            final boolean ok = listed;
            runOnUiThread(() -> {
                if (generation != piccoCheckGeneration) {
                    return;
                }
                piccoRefreshing = false;
                markPicco(address, ok);
                reload();
            });
        }, "NeoSync Picco list").start();
    }

    /** Records whether the Picco at this address just answered. UI thread. */
    private void markPicco(String address, boolean reachable) {
        rememberPiccoCheck(address, reachable);
        renderChips();
    }

    private static String fileName(String path) {
        return path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Runs then(folder) with the Picco folder new books go to. The first
     * time, lists the Picco's folders and asks which one to use (suggesting
     * "books"), then remembers it; Settings can reset the choice. On the UI
     * thread. onStop gets an error message, or null when the user cancelled.
     */
    void withPiccoFolder(String address, java.util.function.Consumer<String> then,
                                 java.util.function.Consumer<String> onStop) {
        String chosen = settings.getPiccoFolder();
        if (chosen != null) {
            then.accept(chosen);
            return;
        }
        new Thread(() -> {
            List<String> folders;
            try {
                folders = new PiccoClient(address).listFolders();
            } catch (Exception e) {
                final String message = piccoErrorMessage(e, address);
                runOnUiThread(() -> onStop.accept(message));
                return;
            }
            runOnUiThread(() -> PiccoFolderChooser.ask(this, folders, then, onStop));
        }).start();
    }

    /** "Top level of the Picco" for "/", else the folder name ("books"). */
    public static String piccoFolderLabel(android.content.Context context, String folder) {
        return folder == null || folder.equals("/")
                ? context.getString(R.string.picco_folder_root)
                : folder.substring(folder.lastIndexOf('/') + 1);
    }

    /** What to tell the user when the Picco could not be reached. */
    String piccoErrorMessage(Exception e, String address) {
        return !(e instanceof IOException)
                ? getString(R.string.transfer_unexpected, String.valueOf(e))
                : WifiHelper.hasLocalNetwork(this)
                ? getString(R.string.transfer_unreachable, address)
                : getString(R.string.transfer_no_network);
    }

    // --- Send missing books ----------------------------------------------------------

    /** Ends a Send / Fix that stopped before its job started. */
    private void callOff(String message) {
        busy = false;
        if (message != null) {
            showNotice(message, true);
        } else {
            hideNotice();
        }
    }

    /**
     * Copies every EPUB on this Boox that the Picco doesn't have yet, after
     * asking. Compares against a fresh listing so nothing is sent twice.
     */
    private void sendMissingBooks() {
        if (busy || JobSession.running()) {
            return;
        }
        final String address = settings.getPiccoAddress();
        if (address.isEmpty()) {
            showNotice(getString(R.string.transfer_no_address), true);
            return;
        }
        busy = true;
        showNotice(getString(R.string.send_missing_checking), false);
        withPiccoFolder(address, folder -> findAndSendMissing(address, folder), this::callOff);
    }

    private void findAndSendMissing(String address, String folder) {
        new Thread(() -> {
            PiccoClient client = new PiccoClient(address);
            List<String> onPicco;
            try {
                onPicco = client.listBooks();
                piccoLibrary.save(address, onPicco);
            } catch (Exception e) {
                final String message = piccoErrorMessage(e, address);
                runOnUiThread(() -> {
                    markPicco(address, false);
                    callOff(message);
                });
                return;
            }
            final List<File> missing = new ArrayList<>();
            long bytes = 0;
            Set<String> seen = new HashSet<>();
            for (OnyxMetadataRepository.Book book : listableBooks()) {
                String name = LibraryModel.keyOf(book.path);
                File file = new File(book.path);
                // Same NFC key as the list and its "missing" count.
                if (!name.toLowerCase(Locale.ROOT).endsWith(".epub") || !seen.add(name)
                        || LibraryModel.findSameName(onPicco, name) != null || !file.canRead()) {
                    continue;
                }
                missing.add(file);
                bytes += file.length();
            }
            final String size = android.text.format.Formatter.formatShortFileSize(this, bytes);
            runOnUiThread(() -> {
                markPicco(address, true);
                if (gone(this)) {
                    busy = false; // rotated or closed while checking: no dialog
                    return;
                }
                reload();
                if (missing.isEmpty()) {
                    callOff(getString(R.string.send_missing_none));
                    return;
                }
                hideNotice();
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.send_missing_confirm_title)
                        .setMessage(getString(R.string.send_missing_confirm_body,
                                missing.size(), size, piccoFolderLabel(this, folder)))
                        .setPositiveButton(R.string.send_missing_confirm_ok, (d, w) -> {
                            // Every book waits its turn, so the rows show it.
                            for (File file : missing) {
                                JobSession.rows.put(LibraryModel.keyOf(file.getPath()), Job.WAITING);
                            }
                            renderPage();
                            // Application context: the job outlives this screen.
                            startJob(new SendMissingJob(getApplicationContext(), client,
                                    address, folder, missing, piccoLibrary));
                        })
                        .setNegativeButton(R.string.send_missing_cancel, (d, w) -> busy = false)
                        .setOnCancelListener(d -> busy = false)
                        .show();
            });
        }).start();
    }

    // --- Fix missing covers ------------------------------------------------------------

    private static String coverTitle(File file) {
        String[] meta = EpubCover.titleAndAuthor(file);
        return meta != null && !meta[0].isEmpty() ? meta[0] : TitleCleaner.fromFileName(file.getName());
    }

    static String titleList(List<String> titles) {
        int shown = Math.min(5, titles.size());
        String list = String.join(", ", titles.subList(0, shown));
        return titles.size() > shown ? list + ", …" : list;
    }

    /**
     * Finds every book without a cover the Picco can show (on this Boox, and
     * the Picco's own books), asks, then fixes them. See CoverFixer.
     */
    private void cleanLibrary() {
        if (busy || JobSession.running()) {
            return;
        }
        final String address = settings.getPiccoAddress();
        if (address.isEmpty()) {
            showNotice(getString(R.string.transfer_no_address), true);
            return;
        }
        if (!ensureFilesAccess()) {
            return;
        }
        busy = true;
        showNotice(getString(R.string.send_missing_checking), false);
        renderSlot();
        new Thread(() -> {
            PiccoClient client = new PiccoClient(address);
            List<String> onPicco;
            try {
                onPicco = client.listBooks();
                piccoLibrary.save(address, onPicco);
            } catch (Exception e) {
                final String message = piccoErrorMessage(e, address);
                runOnUiThread(() -> {
                    markPicco(address, false);
                    callOff(message);
                });
                return;
            }
            runOnUiThread(() -> markPicco(address, true));
            CleanPlan plan = new CleanPlan();
            Set<String> seenPaths = new HashSet<>();
            List<OnyxMetadataRepository.Book> books = listableBooks();
            List<String> libraryPaths = new ArrayList<>();
            for (OnyxMetadataRepository.Book book : books) {
                libraryPaths.add(book.path);
            }
            for (int i = 0; i < books.size(); i++) {
                File file = new File(books.get(i).path);
                if (!CoverFixer.isEpub(file) || !file.canRead() || !seenPaths.add(file.getPath())) {
                    continue;
                }
                String text = getString(R.string.clean_checking_boox, i + 1, books.size());
                runOnUiThread(() -> showNotice(text, false));
                EpubCover.Kind kind = CoverFixer.check(file).kind;
                if (kind == EpubCover.Kind.TAG_IMAGE) {
                    plan.tagged++;
                } else if (kind == EpubCover.Kind.NEEDS_SNAPSHOT) {
                    plan.pictured++;
                } else {
                    continue;
                }
                plan.booxBooks.add(file);
                plan.booxTitles.add(coverTitle(file));
                plan.kindOf.put(file.getPath(), kind);
                String piccoPath = LibraryModel.findSameName(onPicco, file.getPath());
                if (piccoPath != null) {
                    plan.piccoPathOf.put(file.getPath(), piccoPath);
                }
            }
            List<String> others = CoverFixer.piccoOnlyEpubs(onPicco, libraryPaths);
            File work = CleanPlan.workDir(this);
            for (int i = 0; i < others.size(); i++) {
                String piccoPath = others.get(i);
                String text = getString(R.string.clean_checking_picco, i + 1, others.size(),
                        TitleCleaner.fromFileName(fileName(piccoPath)));
                runOnUiThread(() -> showNotice(text, false));
                File copy = new File(work, "picco-" + i + ".epub");
                try {
                    client.download(piccoPath, copy);
                } catch (IOException e) {
                    Log.w(TAG, "Could not check " + piccoPath + ": " + e);
                    continue;
                }
                if (CoverFixer.needsCover(copy)) {
                    plan.piccoOnly.add(piccoPath);
                    plan.downloaded.put(piccoPath, copy);
                    plan.piccoOnlyTitles.add(coverTitle(copy));
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    copy.delete();
                }
            }
            runOnUiThread(() -> confirmClean(plan));
        }).start();
    }

    private void confirmClean(CleanPlan plan) {
        if (gone(this)) {
            busy = false; // rotated or closed while checking: no cover sheet
            return;
        }
        reload();
        if (plan.booxBooks.isEmpty() && plan.piccoOnly.isEmpty()) {
            callOff(getString(R.string.clean_none));
            return;
        }
        hideNotice();
        CoverSheet.show(this, plan);
    }

    // --- Device and network helpers --------------------------------------------------

    /**
     * True when NeoSync may change book files; otherwise asks for the
     * permission and returns false. Without it no book can be read or fixed,
     * and every Picco book would wrongly look like it isn't on this Boox.
     */
    boolean ensureFilesAccess() {
        if (Permissions.hasAllFilesAccess()) {
            return true;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.clean_needs_files_title)
                .setMessage(R.string.clean_needs_files_body)
                .setPositiveButton(R.string.clean_needs_files_open,
                        (d, w) -> Permissions.openAllFilesAccessSettings(this))
                .setNegativeButton(R.string.send_missing_cancel, null)
                .show();
        return false;
    }

    KoSyncClient buildClient() {
        return new KoSyncClient(settings, "NeoSync", deviceId());
    }

    String deviceId() {
        return deviceId(this);
    }

    static String deviceId(android.content.Context context) {
        String androidId = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ANDROID_ID);
        return androidId != null ? androidId : "boox-companion";
    }

    /**
     * Shows the last known update answer, then asks GitHub in the background
     * (at most once a day) and updates the slot.
     */
    private void refreshUpdate() {
        UpdateChecker checker = new UpdateChecker(this);
        update = checker.knownUpdate();
        renderSlot();
        new Thread(() -> {
            UpdateChecker.Update found = checker.check(false);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    update = found;
                    renderSlot();
                }
            });
        }).start();
    }
}
