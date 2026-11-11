package dev.neosync.boox.library;

import dev.neosync.boox.transfer.BookSyncStatus;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Merges the Boox library and the Picco list into one row per book. */
public final class LibraryModel {
    private LibraryModel() {
    }

    public enum Filter { ALL, READING, ONLY_HERE, ONLY_ON_PICCO }

    /** A book on this tablet, as the activity builds it. */
    public static final class BooxBook {
        public String path;
        public double pct;
        public long lastAccessMs;
        public BookSyncStatus status;
        public String title;
        public String author;

        public BooxBook(String path, double pct, long lastAccessMs, BookSyncStatus status,
                        String title, String author) {
            this.path = path;
            this.pct = pct;
            this.lastAccessMs = lastAccessMs;
            this.status = status;
            this.title = title;
            this.author = author;
        }
    }

    /** File extensions that are books, on either device. */
    private static final Set<String> BOOK_EXTENSIONS = new HashSet<>(Arrays.asList(
            "epub", "pdf", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "djvu"));

    /**
     * True for a book file name or path (by extension, any case). Onyx's
     * library also holds note files (.md) and the like, which aren't books.
     */
    public static boolean isBookFile(String name) {
        String file = name.substring(name.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 && BOOK_EXTENSIONS.contains(file.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Whether an Onyx library row is a book to list. Rows inside a hidden
     * folder (Onyx cloud copies under .ksync, NeoSync's backups) never are; a
     * missing file only counts as gone when NeoSync has all-files access,
     * since without it exists() can wrongly say false.
     */
    public static boolean isListable(String path, boolean exists, boolean hasAllFilesAccess) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].startsWith(".")) {
                return false;
            }
        }
        return exists || !hasAllFilesAccess;
    }

    /** File name after the last '/', NFC-normalised. */
    public static String keyOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return Normalizer.normalize(name, Normalizer.Form.NFC);
    }

    /**
     * @param remoteByKey positions keyed by the same NFC file name as the merge key
     */
    public static List<LibraryEntry> merge(List<BooxBook> boox, List<String> piccoPaths,
                                           Map<String, RemotePosition> remoteByKey,
                                           boolean piccoConfigured, boolean syncing) {
        Map<String, BooxBook> booxByKey = new LinkedHashMap<>();
        if (boox != null) {
            for (BooxBook b : boox) {
                String k = keyOf(b.path);
                BooxBook prev = booxByKey.get(k);
                if (prev == null || b.lastAccessMs > prev.lastAccessMs) {
                    booxByKey.put(k, b);
                }
            }
        }
        Map<String, String> piccoByKey = new LinkedHashMap<>();
        if (piccoPaths != null) {
            for (String p : piccoPaths) {
                String k = keyOf(p);
                if (!piccoByKey.containsKey(k)) {
                    piccoByKey.put(k, p);
                }
            }
        }
        Map<String, LibraryEntry> out = new LinkedHashMap<>();
        for (Map.Entry<String, BooxBook> en : booxByKey.entrySet()) {
            BooxBook b = en.getValue();
            LibraryEntry e = new LibraryEntry();
            e.key = en.getKey();
            e.booxPath = b.path;
            e.booxPct = b.pct;
            e.booxLastMs = b.lastAccessMs;
            e.syncStatus = b.status != null ? b.status : BookSyncStatus.NOT_HERE;
            e.title = b.title != null ? b.title : TitleCleaner.fromFileName(e.key);
            e.author = b.author != null ? b.author : "";
            out.put(e.key, e);
        }
        for (Map.Entry<String, String> en : piccoByKey.entrySet()) {
            LibraryEntry e = out.get(en.getKey());
            if (e == null) {
                e = new LibraryEntry();
                e.key = en.getKey();
                e.title = TitleCleaner.fromFileName(e.key);
                e.author = "";
                out.put(e.key, e);
            }
            e.piccoPath = en.getValue();
        }
        List<LibraryEntry> list = new ArrayList<>(out.values());
        for (LibraryEntry e : list) {
            e.remote = remoteByKey != null ? remoteByKey.get(e.key) : null;
            String path = e.booxPath != null ? e.booxPath : e.piccoPath;
            e.isEpub = path.toLowerCase(Locale.ROOT).endsWith(".epub");
            if (e.booxPath == null || e.syncStatus == null) {
                e.piccoPct = e.remote != null ? e.remote.percentage : -1;
            } else if (e.syncStatus == BookSyncStatus.SYNCED) {
                e.piccoPct = e.booxPct;
            } else {
                e.piccoPct = e.remote != null ? e.remote.percentage : -1;
            }
            e.activityMs = Math.max(e.booxLastMs, e.remote != null ? e.remote.timestampMs : 0);
            e.status = RowStatus.of(e, piccoConfigured, syncing);
        }
        Collections.sort(list, new Comparator<LibraryEntry>() {
            @Override
            public int compare(LibraryEntry a, LibraryEntry b) {
                int c = Long.compare(b.activityMs, a.activityMs);
                return c != 0 ? c : a.title.compareToIgnoreCase(b.title);
            }
        });
        return list;
    }

    public static List<LibraryEntry> filter(List<LibraryEntry> all, Filter f, String query) {
        String q = fold(query == null ? "" : query.trim());
        List<LibraryEntry> out = new ArrayList<>();
        for (LibraryEntry e : all) {
            if (!matches(e, f)) {
                continue;
            }
            if (!q.isEmpty() && !fold(e.title + " " + e.author).contains(q)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    public static int count(List<LibraryEntry> all, Filter f) {
        int n = 0;
        for (LibraryEntry e : all) {
            if (matches(e, f)) {
                n++;
            }
        }
        return n;
    }

    private static boolean matches(LibraryEntry e, Filter f) {
        switch (f) {
            case READING:
                return (e.booxPct > 0 && e.booxPct < 1) || (e.piccoPct > 0 && e.piccoPct < 1);
            case ONLY_HERE:
                return e.booxPath != null && e.piccoPath == null && e.isEpub;
            case ONLY_ON_PICCO:
                return e.booxPath == null;
            default:
                return true;
        }
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
