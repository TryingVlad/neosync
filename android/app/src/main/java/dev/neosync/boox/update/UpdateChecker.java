package dev.neosync.boox.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Tells sideloaded installs when a newer NeoSync is on GitHub. Installs from
 * Google Play never ask: Play delivers their updates. Otherwise it asks the
 * public releases API at most once a day (no account, nothing sent but the
 * request itself) and remembers the answer, so screens can show it at once.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String LATEST_URL =
            "https://api.github.com/repos/TryingVlad/neosync/releases/latest";
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 10_000;

    private static final String PREFS = "updates";
    private static final String KEY_CHECKED_MS = "checked_ms";
    private static final String KEY_LATEST = "latest_version";
    private static final String KEY_URL = "download_url";

    /** A newer release than the running one. */
    public static class Update {
        public final String version;
        public final String downloadUrl;

        Update(String version, String downloadUrl) {
            this.version = version;
            this.downloadUrl = downloadUrl;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;

    public UpdateChecker(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The running app's version name, e.g. "7.0.2". */
    public String currentVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * True when Google Play installed this copy. Play handles its updates,
     * and Play policy forbids pointing those users at outside downloads.
     */
    public boolean installedFromPlay() {
        String installer = null;
        try {
            String pkg = context.getPackageName();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                installer = context.getPackageManager()
                        .getInstallSourceInfo(pkg).getInstallingPackageName();
            } else {
                installer = context.getPackageManager().getInstallerPackageName(pkg);
            }
        } catch (Exception e) {
            // Unknown installer: treat as sideloaded.
        }
        return "com.android.vending".equals(installer);
    }

    /** The update known from the last check, or null. No network. */
    public Update knownUpdate() {
        if (installedFromPlay()) {
            return null;
        }
        String latest = prefs.getString(KEY_LATEST, null);
        String url = prefs.getString(KEY_URL, null);
        if (latest == null || url == null || !isNewer(latest, currentVersion())) {
            return null;
        }
        return new Update(latest, url);
    }

    /**
     * Asks GitHub when the last check is older than a day (or always, with
     * force). Blocking: call from a background thread. Returns the update, if
     * any; failures keep the previous answer.
     */
    public Update check(boolean force) {
        if (installedFromPlay()) {
            return null;
        }
        long last = prefs.getLong(KEY_CHECKED_MS, 0);
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) {
            return knownUpdate();
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LATEST_URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "NeoSync/" + currentVersion());
            if (conn.getResponseCode() != 200) {
                Log.d(TAG, "release check -> HTTP " + conn.getResponseCode());
                return knownUpdate();
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    body.append(buf, 0, n);
                }
            }
            JSONObject release = new JSONObject(body.toString());
            String version = stripV(release.optString("tag_name", ""));
            String url = release.optString("html_url", "");
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && asset.optString("name", "").endsWith(".apk")) {
                        url = asset.optString("browser_download_url", url);
                        break;
                    }
                }
            }
            if (!version.isEmpty() && !url.isEmpty()) {
                prefs.edit()
                        .putLong(KEY_CHECKED_MS, System.currentTimeMillis())
                        .putString(KEY_LATEST, version)
                        .putString(KEY_URL, url)
                        .apply();
            }
        } catch (Exception e) {
            Log.d(TAG, "release check failed: " + e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return knownUpdate();
    }

    private static String stripV(String tag) {
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /**
     * True when version a is newer than b, comparing dot-separated numbers
     * ("7.1.0" > "7.0.2", "7.0.10" > "7.0.9", "7.1" == "7.1.0"). Anything
     * after a "-" or "+" (pre-release tags) is ignored.
     */
    public static boolean isNewer(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) {
                return p > q;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        String core = stripV(version == null ? "" : version.trim()).split("[-+ ]", 2)[0];
        String[] parts = core.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }
}
