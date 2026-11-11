package dev.neosync.boox.library;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** The Boox place from before the latest jump, per book path, so it can be undone. */
public class UndoStore {

    private static final String PREFS = "undo_jumps";

    private final SharedPreferences prefs;

    public static final class Saved {
        public String prevProgress;
        public long prevLastAccessMs;
        public String prevExtra; // Neo Reader's whole extraAttributes before the jump, may be null
        public String writtenProgress;
        public long savedAtMs;
    }

    public UndoStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(String path, String prevProgress, long prevLastAccessMs,
                     String prevExtraAttributes, String writtenProgress, long nowMs) {
        if (path == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("progress", prevProgress);
            json.put("access", prevLastAccessMs);
            if (prevExtraAttributes != null) {
                json.put("extra", prevExtraAttributes);
            }
            json.put("written", writtenProgress);
            json.put("savedAt", nowMs);
            prefs.edit().putString(path, json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** The saved place, or null when there is none. */
    public Saved get(String path) {
        String raw = path == null ? null : prefs.getString(path, null);
        if (raw == null) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(raw);
            Saved saved = new Saved();
            saved.prevProgress = json.optString("progress", null);
            saved.prevLastAccessMs = json.optLong("access", 0);
            saved.prevExtra = json.has("extra") ? json.getString("extra") : null;
            saved.writtenProgress = json.optString("written", null);
            saved.savedAtMs = json.optLong("savedAt", 0);
            return saved;
        } catch (Exception e) {
            return null;
        }
    }

    public void clear(String path) {
        if (path != null) {
            prefs.edit().remove(path).apply();
        }
    }
}
