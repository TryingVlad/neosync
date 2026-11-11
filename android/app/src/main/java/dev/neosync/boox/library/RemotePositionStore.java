package dev.neosync.boox.library;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/** Latest server-side position per document hash, kept for the library view. */
public class RemotePositionStore {

    private static final String PREFS = "remote_positions";

    private final SharedPreferences prefs;

    public RemotePositionStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public RemotePosition get(String hash) {
        return RemotePosition.fromJson(prefs.getString(hash, null));
    }

    /** Keeps the newer of the stored position and p, under p.document. */
    public void record(RemotePosition p) {
        if (p == null || p.document == null || p.document.isEmpty()) {
            return;
        }
        recordAll(p.document, null, p);
    }

    /** Records p under the hash and each alias. */
    public void recordAll(String hash, List<String> aliases, RemotePosition p) {
        if (p == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        put(editor, hash, p);
        if (aliases != null) {
            for (String alias : aliases) {
                put(editor, alias, p);
            }
        }
        editor.apply();
    }

    private void put(SharedPreferences.Editor editor, String key, RemotePosition p) {
        if (key == null || key.isEmpty()) {
            return;
        }
        RemotePosition kept = RemotePosition.newer(get(key), p);
        if (kept == p) {
            editor.putString(key, p.toJson());
        }
    }
}
