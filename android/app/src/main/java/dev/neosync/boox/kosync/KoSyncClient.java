package dev.neosync.boox.kosync;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal KOReader-Sync (kosync) protocol client.
 *
 * Wire format matches CrossPoint's KOReaderSyncClient:
 *   GET  {base}/users/auth               -> 200 ok / 401 bad credentials
 *   GET  {base}/syncs/progress/{docHash} -> progress JSON / 404 none
 *   PUT  {base}/syncs/progress           -> {document, progress, percentage, device, device_id}
 * Auth is the kosync header pair x-auth-user / x-auth-key, where the key is
 * the MD5 hex of the password (never the plaintext).
 */
public class KoSyncClient {

    private static final String TAG = "KoSyncClient";
    private static final int TIMEOUT_MS = 15000;

    /**
     * The public sync server rate-limits bursts per IP address. All requests
     * from this app, whichever screen or service makes them, are spaced out.
     */
    private static final long MIN_SPACING_MS = 6000;

    // After a 429 the app stops talking to the server altogether for a
    // while, doubling the pause each time it is refused again. Retrying
    // sooner only keeps the limiter tripped. Shared by all screens/services.
    private static final long COOLDOWN_BASE_MS = 5 * 60_000;
    private static final long COOLDOWN_MAX_MS = 60 * 60_000;
    private static long coolDownUntilMs = 0;
    private static int consecutiveRefusals = 0;

    /** Epoch ms until which no request will be sent, or 0 when not cooling down. */
    public static synchronized long coolDownUntilMs() {
        return System.currentTimeMillis() < coolDownUntilMs ? coolDownUntilMs : 0;
    }

    private static boolean isCoolingDown() {
        return coolDownUntilMs() > 0;
    }

    private static synchronized Result track(Result result) {
        if (result.error == Error.RATE_LIMITED) {
            long wait = result.retryAfterSec > 0
                    ? result.retryAfterSec * 1000L
                    : COOLDOWN_BASE_MS << Math.min(consecutiveRefusals, 4);
            coolDownUntilMs = System.currentTimeMillis() + Math.min(wait, COOLDOWN_MAX_MS);
            consecutiveRefusals++;
            Log.i(TAG, "Rate limited; no requests for " + Math.min(wait, COOLDOWN_MAX_MS) / 1000 + "s");
        } else if (result.error == Error.OK || result.error == Error.NOT_FOUND) {
            consecutiveRefusals = 0;
        }
        return result;
    }
    private static final Object PACING_LOCK = new Object();
    private static long lastRequestMs = 0;

    private static void pace() {
        synchronized (PACING_LOCK) {
            long wait = lastRequestMs + MIN_SPACING_MS - System.currentTimeMillis();
            if (wait > 0) sleep(wait);
            lastRequestMs = System.currentTimeMillis();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public enum Error {
        OK, NO_CREDENTIALS, NETWORK_ERROR, AUTH_FAILED, SERVER_ERROR, JSON_ERROR, NOT_FOUND,
        RATE_LIMITED
    }

    public static class Progress {
        public String document;
        public String progress;      // xpath string (KOReader/CrossPoint) or page number
        public double percentage;    // 0.0 .. 1.0
        public String device;
        public String deviceId;
        public long timestampSec;    // server-side timestamp, epoch seconds
        /** Other hashes the server merged into this document (changes feed only). */
        public java.util.List<String> aliases = new java.util.ArrayList<>();
    }

    /** One page of the server's progress change feed. */
    public static class Changes {
        /** Where the next request starts (pass back as since). */
        public long cursor;
        /** More changes are waiting after this page. */
        public boolean more;
        public final java.util.List<Progress> items = new java.util.ArrayList<>();
    }

    public static class Result {
        public final Error error;
        public final int httpCode;
        public final Progress progress;
        /** For RATE_LIMITED: the server's Retry-After in seconds, or 0 if not given. */
        public long retryAfterSec;
        /** For getChanges: the page read. */
        public Changes changes;

        Result(Error error, int httpCode, Progress progress) {
            this.error = error;
            this.httpCode = httpCode;
            this.progress = progress;
        }
    }

    private final SyncSettings settings;
    private final String deviceName;
    private final String deviceId;

    public KoSyncClient(SyncSettings settings, String deviceName, String deviceId) {
        this.settings = settings;
        this.deviceName = deviceName;
        this.deviceId = deviceId;
    }

    public Result authenticate() {
        if (isCoolingDown()) {
            return new Result(Error.RATE_LIMITED, 429, null);
        }
        return track(authenticateOnce());
    }

    private Result authenticateOnce() {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            HttpURLConnection conn = open("/users/auth", "GET");
            int code = conn.getResponseCode();
            drain(conn);
            Log.d(TAG, "auth -> " + code);
            if (code == 200) return new Result(Error.OK, code, null);
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null);
            if (code == 429) return rateLimited(conn);
            return new Result(Error.SERVER_ERROR, code, null);
        } catch (Exception e) {
            Log.w(TAG, "auth failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null);
        }
    }

    /**
     * Creates the account on the server: POST {base}/users/create with
     * {username, password: md5(password)} — same as KOReader and CrossPoint.
     * 201 created; 402 means the username exists (or registration is off).
     */
    public Result register() {
        if (isCoolingDown()) {
            return new Result(Error.RATE_LIMITED, 429, null);
        }
        return track(registerOnce());
    }

    private Result registerOnce() {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            JSONObject json = new JSONObject();
            json.put("username", settings.getUsername());
            json.put("password", settings.getPasswordMd5());
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = open("/users/create", "POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            drain(conn);
            Log.d(TAG, "register -> " + code);
            if (code == 200 || code == 201) return new Result(Error.OK, code, null);
            return new Result(Error.SERVER_ERROR, code, null);
        } catch (Exception e) {
            Log.w(TAG, "register failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null);
        }
    }

    public Result getProgress(String documentHash) {
        if (isCoolingDown()) {
            return new Result(Error.RATE_LIMITED, 429, null);
        }
        return track(getProgressOnce(documentHash));
    }

    private Result getProgressOnce(String documentHash) {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            HttpURLConnection conn = open("/syncs/progress/" + documentHash, "GET");
            int code = conn.getResponseCode();
            String body = readBody(conn);
            Log.d(TAG, "getProgress " + documentHash + " -> " + code);
            if (code == 200 && body != null) {
                try {
                    JSONObject json = new JSONObject(body);
                    Progress p = new Progress();
                    p.document = documentHash;
                    p.progress = json.optString("progress", "");
                    p.percentage = json.optDouble("percentage", 0.0);
                    p.device = json.optString("device", "");
                    p.deviceId = json.optString("device_id", "");
                    p.timestampSec = json.optLong("timestamp", 0);
                    // A 200 with no percentage/timestamp means the server has
                    // no progress for this document (some servers do this
                    // instead of returning 404).
                    if (p.timestampSec == 0 && !json.has("percentage")) {
                        return new Result(Error.NOT_FOUND, code, null);
                    }
                    return new Result(Error.OK, code, p);
                } catch (Exception e) {
                    Log.w(TAG, "getProgress JSON parse failed: " + e);
                    return new Result(Error.JSON_ERROR, code, null);
                }
            }
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null);
            if (code == 404) return new Result(Error.NOT_FOUND, code, null);
            if (code == 429) return rateLimited(conn);
            return new Result(Error.SERVER_ERROR, code, null);
        } catch (Exception e) {
            Log.w(TAG, "getProgress failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null);
        }
    }

    /**
     * The books whose position changed since a cursor, newest position per
     * book, without the ones this device wrote last. A crosspoint-sync
     * extension (GET /api/v1/progress/changes); stock kosync servers answer
     * 404, which comes back as NOT_FOUND so the caller can fall back to
     * asking about books one by one.
     */
    public Result getChanges(long since, int limit) {
        if (isCoolingDown()) {
            return new Result(Error.RATE_LIMITED, 429, null);
        }
        return track(getChangesOnce(since, limit));
    }

    private Result getChangesOnce(long since, int limit) {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            HttpURLConnection conn = open("/api/v1/progress/changes?since=" + since
                    + "&limit=" + limit + "&device="
                    + java.net.URLEncoder.encode(deviceId, "UTF-8"), "GET");
            int code = conn.getResponseCode();
            String body = readBody(conn);
            if (code == 200 && body != null) {
                try {
                    Changes changes = parseChanges(body);
                    Log.d(TAG, "getChanges since " + since + " -> " + changes.items.size()
                            + " change(s), cursor " + changes.cursor);
                    Result result = new Result(Error.OK, code, null);
                    result.changes = changes;
                    return result;
                } catch (Exception e) {
                    Log.w(TAG, "getChanges JSON parse failed: " + e);
                    return new Result(Error.JSON_ERROR, code, null);
                }
            }
            Log.d(TAG, "getChanges since " + since + " -> " + code);
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null);
            if (code == 404) return new Result(Error.NOT_FOUND, code, null);
            if (code == 429) return rateLimited(conn);
            return new Result(Error.SERVER_ERROR, code, null);
        } catch (Exception e) {
            Log.w(TAG, "getChanges failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null);
        }
    }

    /** Parses a change feed page; throws on anything that isn't one. */
    public static Changes parseChanges(String body) throws org.json.JSONException {
        JSONObject json = new JSONObject(body);
        if (!json.has("cursor") || !json.has("changes")) {
            throw new org.json.JSONException("not a change feed");
        }
        Changes changes = new Changes();
        changes.cursor = json.getLong("cursor");
        changes.more = json.optBoolean("more", false);
        org.json.JSONArray items = json.getJSONArray("changes");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            Progress p = new Progress();
            p.document = item.optString("document", "");
            p.progress = item.optString("progress", "");
            p.percentage = item.optDouble("percentage", 0.0);
            p.device = item.optString("device", "");
            p.deviceId = item.optString("device_id", "");
            p.timestampSec = item.optLong("timestamp", 0);
            org.json.JSONArray aliases = item.optJSONArray("aliases");
            if (aliases != null) {
                for (int j = 0; j < aliases.length(); j++) {
                    p.aliases.add(aliases.getString(j));
                }
            }
            if (!p.document.isEmpty()) {
                changes.items.add(p);
            }
        }
        return changes;
    }

    public Result updateProgress(String documentHash, String progress, double percentage) {
        if (isCoolingDown()) {
            return new Result(Error.RATE_LIMITED, 429, null);
        }
        return track(updateProgressOnce(documentHash, progress, percentage));
    }

    private Result updateProgressOnce(String documentHash, String progress, double percentage) {
        if (!settings.hasCredentials()) {
            return new Result(Error.NO_CREDENTIALS, 0, null);
        }
        try {
            JSONObject json = new JSONObject();
            json.put("document", documentHash);
            json.put("progress", progress);
            json.put("percentage", percentage);
            json.put("device", deviceName);
            json.put("device_id", deviceId);
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = open("/syncs/progress", "PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            drain(conn);
            Log.d(TAG, "updateProgress " + documentHash + " " + percentage + " -> " + code);
            if (code == 200 || code == 202) return new Result(Error.OK, code, null);
            if (code == 401) return new Result(Error.AUTH_FAILED, code, null);
            if (code == 429) return rateLimited(conn);
            return new Result(Error.SERVER_ERROR, code, null);
        } catch (Exception e) {
            Log.w(TAG, "updateProgress failed: " + e);
            return new Result(Error.NETWORK_ERROR, 0, null);
        }
    }

    public static String errorString(Result result) {
        switch (result.error) {
            case OK: return "Success";
            case NO_CREDENTIALS: return "No credentials configured";
            case NETWORK_ERROR: return "Network error";
            case AUTH_FAILED: return "Authentication failed";
            case SERVER_ERROR: return "Server error (HTTP " + result.httpCode + ")";
            case JSON_ERROR: return "Bad server response";
            case NOT_FOUND: return "No progress found on server";
            case RATE_LIMITED: {
                long until = coolDownUntilMs();
                return until > 0
                        ? "The sync server asked NeoSync to slow down. It will try again at "
                                + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                                        .format(new java.util.Date(until)) + "."
                        : "The sync server is busy. NeoSync will try again shortly.";
            }
            default: return "Unknown error";
        }
    }

    /** HTTP 429: the server asks us to slow down. Header fields stay readable after the body is drained. */
    private static Result rateLimited(HttpURLConnection conn) {
        Result result = new Result(Error.RATE_LIMITED, 429, null);
        try {
            String retryAfter = conn.getHeaderField("Retry-After");
            if (retryAfter != null) result.retryAfterSec = Long.parseLong(retryAfter.trim());
        } catch (Exception ignored) {
        }
        return result;
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        String base = settings.getBaseUrl();
        if (SyncSettings.isInsecureServerUrl(base)) {
            // Never send credentials over cleartext, even if an http:// address
            // got stored somehow. Surfaces as a network error.
            throw new java.io.IOException("Refusing plain-HTTP sync server: " + base);
        }
        pace();
        URL url = new URL(base + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/vnd.koreader.v1+json");
        conn.setRequestProperty("x-auth-user", settings.getUsername());
        conn.setRequestProperty("x-auth-key", settings.getPasswordMd5());
        return conn;
    }

    private static String readBody(HttpURLConnection conn) {
        try {
            InputStream stream = conn.getResponseCode() >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buf = new char[1024];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private static void drain(HttpURLConnection conn) {
        readBody(conn);
    }
}
