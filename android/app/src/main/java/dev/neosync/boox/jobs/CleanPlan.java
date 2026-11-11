package dev.neosync.boox.jobs;

import android.content.Context;

import dev.neosync.boox.epub.EpubCover;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What "Fix missing covers" found, before anything is changed. */
public final class CleanPlan {
    /** Books on this Boox without a cover the Picco can show. */
    public final List<File> booxBooks = new ArrayList<>();
    /** Boox file path -> the same book's path on the Picco, when it's there. */
    public final Map<String, String> piccoPathOf = new HashMap<>();
    /** Picco-only books that need a cover, and where they were downloaded. */
    public final List<String> piccoOnly = new ArrayList<>();
    public final Map<String, File> downloaded = new HashMap<>();
    public final List<String> booxTitles = new ArrayList<>();
    /** Boox file path -> what its cover becomes (TAG_IMAGE or NEEDS_SNAPSHOT). */
    public final Map<String, EpubCover.Kind> kindOf = new HashMap<>();
    public final List<String> piccoOnlyTitles = new ArrayList<>();
    public int tagged;
    public int pictured;

    /** The empty folder Picco-only books are downloaded to (emptied on each call). */
    public static File workDir(Context context) {
        File dir = new File(context.getCacheDir(), "clean-library");
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }
}
