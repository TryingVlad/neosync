package dev.neosync.boox.kosync;

import android.content.Context;
import android.content.SharedPreferences;

import dev.neosync.boox.library.SyncProblem;

/**
 * Persistent sync configuration and status, backed by SharedPreferences.
 *
 * Only the MD5 hex of the password is ever stored — it is computed once when
 * the user types the password (same as CrossPoint does internally), so the
 * x-auth-key credential lines up byte-for-byte across both devices. The
 * plaintext password never touches disk or logs.
 */
public class SyncSettings {

    public static final String MATCH_BINARY = "binary";     // KOReader partial MD5
    public static final String MATCH_FILENAME = "filename"; // MD5 of filename (default, as on CrossPoint)

    private static final String PREFS = "kosync_settings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD_MD5 = "password_md5";
    private static final String KEY_MATCH_METHOD = "match_method";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_PROBLEM_TYPE = "problem_type";
    private static final String KEY_FIRST_FAILURE_MS = "first_failure_ms";
    private static final String KEY_CONSECUTIVE_FAILURES = "consecutive_failures";
    private static final String KEY_LAST_BOOK = "last_book";
    private static final String KEY_LAST_BOOK_PATH = "last_book_path";
    private static final String KEY_LAST_PERCENT = "last_percent";
    private static final String KEY_ONBOARDED = "onboarded";
    private static final String KEY_AUTO_SYNC = "auto_sync";
    private static final String KEY_LIBRARY_TYPES = "library_types";
    private static final String KEY_LIBRARY_HIDDEN_FOLDERS = "library_hidden_folders";
    private static final String KEY_PICCO_ADDRESS = "picco_address";
    private static final String KEY_PICCO_FOLDER = "picco_folder";
    public static final String KEY_LIBRARY_FILTER = "library_filter";
    public static final String KEY_DISMISSED_SUGGESTIONS = "dismissed_suggestions";
    // Bumped after every sync attempt, so listeners hear about an attempt
    // even when its outcome (same error text) leaves the other keys unchanged.
    public static final String KEY_LAST_ATTEMPT_MS = "last_attempt_ms";

    private static final String DEFAULT_SERVER_URL = "https://sync.crosspointreader.com";

    private final SharedPreferences prefs;

    public SyncSettings(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, "");
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getPasswordMd5() {
        return prefs.getString(KEY_PASSWORD_MD5, "");
    }

    public String getMatchMethod() {
        return prefs.getString(KEY_MATCH_METHOD, MATCH_FILENAME);
    }

    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply();
    }

    public void setUsername(String username) {
        prefs.edit().putString(KEY_USERNAME, username.trim()).apply();
    }

    /** Hashes and stores the password; the plaintext is discarded here. */
    public void setPassword(String plaintextPassword) {
        prefs.edit().putString(KEY_PASSWORD_MD5,
                KoReaderDocumentHash.md5Hex(plaintextPassword)).apply();
    }

    public void setMatchMethod(String method) {
        prefs.edit().putString(KEY_MATCH_METHOD, method).apply();
    }

    /** Signs out: forgets the account, keeps server and matching settings. */
    public void clearCredentials() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD_MD5).apply();
    }

    public boolean isOnboarded() {
        return prefs.getBoolean(KEY_ONBOARDED, false);
    }

    public void setOnboarded() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply();
    }

    /**
     * Automatic background sync. When off, the SyncService is stopped and is
     * not started again (not even after a reboot); the manual buttons on the
     * home screen still work. Default on.
     */
    public boolean isAutoSyncEnabled() {
        return prefs.getBoolean(KEY_AUTO_SYNC, true);
    }

    public void setAutoSyncEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply();
    }

    /**
     * Address of the Picco's CrossPoint file-transfer server, as shown on its
     * File Transfer screen ("192.168.1.50", "crosspoint.local", …). Used only
     * for sending book files over the local network.
     */
    public String getPiccoAddress() {
        return prefs.getString(KEY_PICCO_ADDRESS, "");
    }

    public void setPiccoAddress(String address) {
        prefs.edit().putString(KEY_PICCO_ADDRESS, address.trim()).apply();
    }

    /**
     * The Picco folder books are sent to ("/" or "/books"), or null until
     * the user picked one: NeoSync asks the first time it sends a book.
     */
    public String getPiccoFolder() {
        return prefs.getString(KEY_PICCO_FOLDER, null);
    }

    /** Null forgets the choice, so the next send asks again. */
    public void setPiccoFolder(String folder) {
        if (folder == null) {
            prefs.edit().remove(KEY_PICCO_FOLDER).apply();
        } else {
            prefs.edit().putString(KEY_PICCO_FOLDER, folder).apply();
        }
    }

    /** File types (lower case) the home list shows; EPUB and PDF until chosen. */
    public java.util.Set<String> getLibraryTypes() {
        java.util.Set<String> saved = prefs.getStringSet(KEY_LIBRARY_TYPES, null);
        return new java.util.HashSet<>(saved != null ? saved
                : dev.neosync.boox.library.LibraryScope.DEFAULT_TYPES);
    }

    public void setLibraryTypes(java.util.Set<String> types) {
        prefs.edit().putStringSet(KEY_LIBRARY_TYPES, new java.util.HashSet<>(types)).apply();
    }

    /** Folders the user unticked: their books (and subfolders') aren't listed. */
    public java.util.Set<String> getHiddenFolders() {
        return new java.util.HashSet<>(prefs.getStringSet(KEY_LIBRARY_HIDDEN_FOLDERS,
                java.util.Collections.emptySet()));
    }

    public void setHiddenFolders(java.util.Set<String> folders) {
        prefs.edit().putStringSet(KEY_LIBRARY_HIDDEN_FOLDERS, new java.util.HashSet<>(folders))
                .apply();
    }

    public dev.neosync.boox.library.LibraryScope getLibraryScope() {
        return new dev.neosync.boox.library.LibraryScope(getLibraryTypes(), getHiddenFolders());
    }

    /** The home screen's last filter (a LibraryModel.Filter name), or null. */
    public String getLibraryFilter() {
        return prefs.getString(KEY_LIBRARY_FILTER, null);
    }

    public void setLibraryFilter(String filter) {
        prefs.edit().putString(KEY_LIBRARY_FILTER, filter).apply();
    }

    /** Suggestion.dismissKey() values the user hid with ✕. */
    public java.util.Set<String> getDismissedSuggestions() {
        return new java.util.HashSet<>(
                prefs.getStringSet(KEY_DISMISSED_SUGGESTIONS, java.util.Collections.emptySet()));
    }

    public void addDismissedSuggestion(String key) {
        java.util.Set<String> keys = getDismissedSuggestions();
        keys.add(key);
        prefs.edit().putStringSet(KEY_DISMISSED_SUGGESTIONS, keys).apply();
    }

    public boolean hasCredentials() {
        return !getUsername().isEmpty() && !getPasswordMd5().isEmpty();
    }

    /**
     * True for a plain-HTTP sync server address. Those are refused: the
     * username and password hash would travel unencrypted.
     */
    public static boolean isInsecureServerUrl(String url) {
        return url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("http://");
    }

    public String getBaseUrl() {
        return baseUrlOf(getServerUrl());
    }

    /** The server as people see it: the base URL without its scheme. */
    public String getServerLabel() {
        return serverLabel(getServerUrl());
    }

    /**
     * Base URL for a stored server address: the default server when empty,
     * https:// added when there is no scheme, trailing slashes stripped.
     */
    public static String baseUrlOf(String serverUrl) {
        String url = serverUrl == null ? "" : serverUrl.trim();
        if (url.isEmpty()) {
            url = DEFAULT_SERVER_URL;
        } else if (!url.contains("://")) {
            // The app allows cleartext HTTP (for the Picco on the local
            // network), so the sync server must be HTTPS explicitly.
            url = "https://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** "sync.crosspointreader.com" for an empty address; never the scheme. */
    public static String serverLabel(String serverUrl) {
        String url = baseUrlOf(serverUrl);
        int scheme = url.indexOf("://");
        return scheme >= 0 ? url.substring(scheme + 3) : url;
    }

    // --- Change feed -------------------------------------------------------

    /**
     * Where the server's change feed was last read up to. Tied to the server
     * and account it came from: another server's cursor means nothing here,
     * so a different one starts over from 0 (a full snapshot).
     */
    public long getChangesCursor() {
        return feedOwner().equals(prefs.getString("changes_owner", ""))
                ? prefs.getLong("changes_cursor", 0) : 0;
    }

    public void setChangesCursor(long cursor) {
        prefs.edit()
                .putString("changes_owner", feedOwner())
                .putLong("changes_cursor", cursor)
                .apply();
    }

    private String feedOwner() {
        // Bump the version when book matching gets better, so the feed is
        // read again from the start and changes it skipped are matched now
        // (v3: both Unicode spellings of accented file names).
        return "v3|" + getBaseUrl() + "|" + getUsername();
    }

    // --- Status area -------------------------------------------------------

    public long getLastSyncMs() {
        return prefs.getLong(KEY_LAST_SYNC_MS, 0);
    }

    public String getLastError() {
        return prefs.getString(KEY_LAST_ERROR, "");
    }

    public String getLastBook() {
        return prefs.getString(KEY_LAST_BOOK, "");
    }

    /** Path of the last synced book, for the notification; empty when unknown. */
    public String getLastBookPath() {
        return prefs.getString(KEY_LAST_BOOK_PATH, "");
    }

    /** Position (0..1) of the last synced book; negative when unknown. */
    public double getLastPercent() {
        return prefs.getFloat(KEY_LAST_PERCENT, -1f);
    }

    /**
     * A successful sync of one book (any caller: the service, Sync all, the
     * book sheet). The notification follows it (spec 7.7).
     *
     * @param percentage the position now on both sides, 0..1
     */
    public void recordSyncSuccess(String bookName, String path, double percentage) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putLong(KEY_LAST_SYNC_MS, now)
                .putString(KEY_LAST_BOOK, bookName)
                .putString(KEY_LAST_BOOK_PATH, path)
                .putFloat(KEY_LAST_PERCENT, (float) percentage)
                .putString(KEY_LAST_ERROR, "")
                .putString(KEY_PROBLEM_TYPE, SyncProblem.Type.NONE.name())
                .putLong(KEY_FIRST_FAILURE_MS, 0)
                .putInt(KEY_CONSECUTIVE_FAILURES, 0)
                .putLong(KEY_LAST_ATTEMPT_MS, now)
                .apply();
    }

    public void recordSyncError(String error) {
        recordSyncError(error, SyncProblem.Type.OTHER);
    }

    /**
     * Records a failed attempt: the free-text message, its type, and (only
     * for the first failure of a streak) when the trouble started.
     */
    public void recordSyncError(String error, SyncProblem.Type type) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor edit = prefs.edit()
                .putString(KEY_LAST_ERROR, error)
                .putString(KEY_PROBLEM_TYPE, type.name())
                .putInt(KEY_CONSECUTIVE_FAILURES, getConsecutiveFailures() + 1)
                .putLong(KEY_LAST_ATTEMPT_MS, now);
        if (getFirstFailureMs() == 0) {
            edit.putLong(KEY_FIRST_FAILURE_MS, now);
        }
        edit.apply();
    }

    public SyncProblem.Type getProblemType() {
        try {
            return SyncProblem.Type.valueOf(
                    prefs.getString(KEY_PROBLEM_TYPE, SyncProblem.Type.NONE.name()));
        } catch (IllegalArgumentException e) {
            return SyncProblem.Type.NONE;
        }
    }

    public long getFirstFailureMs() {
        return prefs.getLong(KEY_FIRST_FAILURE_MS, 0);
    }

    public int getConsecutiveFailures() {
        return prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0);
    }

    /**
     * The server answered normally, so whatever went wrong before is over —
     * even when there was nothing to push or pull. Without this a passing
     * network blip would keep "Needs attention" up until the next page turn.
     */
    public void clearSyncError() {
        if (!getLastError().isEmpty() || getFirstFailureMs() != 0
                || getConsecutiveFailures() != 0) {
            prefs.edit()
                    .putString(KEY_LAST_ERROR, "")
                    .putString(KEY_PROBLEM_TYPE, SyncProblem.Type.NONE.name())
                    .putLong(KEY_FIRST_FAILURE_MS, 0)
                    .putInt(KEY_CONSECUTIVE_FAILURES, 0)
                    .apply();
        }
    }

    /** Marks the end of an attempt (e.g. a manual retry) whatever its outcome. */
    public void recordSyncAttempt() {
        prefs.edit().putLong(KEY_LAST_ATTEMPT_MS, System.currentTimeMillis()).apply();
    }

    /** Notifies about any change to the sync status (success, error, attempt). */
    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
