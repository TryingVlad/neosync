package dev.neosync.boox.transfer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The last known list of books on the Picco, kept between visits so the home
 * screen can show it straight away and refresh it quietly in the background.
 * Remembers which Picco address it came from: a list from another address is
 * treated as unknown.
 */
public class PiccoLibraryStore {

    private static final String PREFS = "picco_library";
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_BOOKS = "books";
    private static final String KEY_UPDATED_MS = "updated_ms";

    private final SharedPreferences prefs;

    public PiccoLibraryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True when a list was fetched from this address at least once. */
    public boolean hasList(String address) {
        return getUpdatedMs() > 0 && address.equals(prefs.getString(KEY_ADDRESS, null));
    }

    /** Paths relative to the Picco's root, e.g. "Fiction/Dune.epub"; empty if unknown. */
    public List<String> getBooks(String address) {
        List<String> books = new ArrayList<>();
        if (!hasList(address)) {
            return books;
        }
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_BOOKS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                books.add(array.getString(i));
            }
        } catch (Exception ignored) {
        }
        return books;
    }

    public long getUpdatedMs() {
        return prefs.getLong(KEY_UPDATED_MS, 0);
    }

    /** Replaces the list with a fresh listing from the Picco at this address. */
    public void save(String address, List<String> books) {
        prefs.edit()
                .putString(KEY_ADDRESS, address)
                .putString(KEY_BOOKS, new JSONArray(books).toString())
                .putLong(KEY_UPDATED_MS, System.currentTimeMillis())
                .apply();
    }

    /**
     * Adds a file NeoSync just uploaded (uploads go to the top-level folder),
     * so the list is right without another full listing. No-op when there is
     * no list for this address yet.
     */
    public void addUploaded(String address, String fileName) {
        if (!hasList(address)) {
            return;
        }
        List<String> books = getBooks(address);
        if (books.contains(fileName)) {
            return;
        }
        books.add(fileName);
        Collections.sort(books, String.CASE_INSENSITIVE_ORDER);
        prefs.edit().putString(KEY_BOOKS, new JSONArray(books).toString()).apply();
    }
}
