package dev.neosync.boox.library;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;

import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.transfer.CoverFixer;

/**
 * The last synced book's title as home and the notification show it, from
 * the stored path (not only the listed books): an EPUB's own title
 * (BookMetaCache), else Neo Reader's (OnyxTitle). Shared by the home chip and
 * Connections. Looked up once per path off the UI thread.
 */
public final class LastBookTitle {

    private static final String TAG = "LastBookTitle";

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String path;
    private String title;

    public LastBookTitle(Context context) {
        app = context.getApplicationContext();
    }

    /**
     * On the UI thread. Returns the looked-up title for path, or fallback
     * until it is known; onFound runs on the UI thread when it becomes known.
     */
    public String get(String path, String fallback, Runnable onFound) {
        if (path == null || path.isEmpty()) {
            return fallback;
        }
        if (!path.equals(this.path)) {
            this.path = path;
            title = null;
            new Thread(() -> {
                String found = lookUp(app, path);
                main.post(() -> {
                    if (found != null && path.equals(this.path)) {
                        title = found;
                        onFound.run();
                    }
                });
            }, "NeoSync last book title").start();
        }
        return title != null ? title : fallback;
    }

    /** The shown title of the book at path, or null if unknown. Off the UI thread. */
    public static String lookUp(Context app, String path) {
        File file = new File(path);
        try {
            if (CoverFixer.isEpub(file)) {
                return new BookMetaCache(app).get(file).title;
            }
            OnyxMetadataRepository.Book book = new OnyxMetadataRepository(app).queryBook(path);
            return book == null ? null
                    : OnyxTitle.choose(book.title, book.authors, file.getName())[0];
        } catch (Exception e) {
            Log.d(TAG, "No title for the last synced book, using its file name", e);
            return null;
        }
    }
}
