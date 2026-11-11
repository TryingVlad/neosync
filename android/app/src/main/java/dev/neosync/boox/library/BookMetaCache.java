package dev.neosync.boox.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import dev.neosync.boox.epub.EpubCover;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Title, author and cover thumbnail per book, keyed by path + modified time +
 * size. Blocking: call from a background thread. No UI calls.
 */
public class BookMetaCache {

    private static final String PREFS = "book_meta";
    private static final String THUMB_DIR = "thumbs";
    /**
     * Entry format. 2: a book that couldn't be opened is no longer stored (older
     * entries may hold such a guess as "no cover, black"), so 1 is recomputed.
     */
    private static final int SCHEMA = 2;

    /** Where the entries are kept: SharedPreferences in the app, a map in tests. */
    public interface Store {
        String get(String key);

        void put(String key, String value);
    }

    private final Store store;
    private final File thumbDir;

    public BookMetaCache(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.store = new Store() {
            @Override
            public String get(String key) {
                return prefs.getString(key, null);
            }

            @Override
            public void put(String key, String value) {
                prefs.edit().putString(key, value).apply();
            }
        };
        this.thumbDir = new File(app.getCacheDir(), THUMB_DIR);
    }

    public BookMetaCache(Store store, File thumbDir) {
        this.store = store;
        this.thumbDir = thumbDir;
    }

    public BookMeta get(File book) {
        String pathKey = pathKey(book);
        String version = version(book);
        try {
            String stored = store.get(pathKey);
            if (stored != null) {
                JSONObject o = new JSONObject(stored);
                if (o.optInt("s") == SCHEMA && version.equals(o.optString("v"))) {
                    return new BookMeta(o.getString("title"), o.optString("author"),
                            o.optBoolean("cover"), o.optBoolean("black"));
                }
            }
        } catch (Exception ignored) {
            // fall through and recompute
        }
        Computed computed = compute(book);
        BookMeta meta = computed.meta;
        if (!computed.read) {
            // Likely no file access yet: ask again next time rather than remember a guess.
            return meta;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("s", SCHEMA);
            o.put("v", version);
            o.put("title", meta.title);
            o.put("author", meta.author);
            o.put("cover", meta.hasCover);
            o.put("black", meta.wouldShowBlack);
            store.put(pathKey, o.toString());
        } catch (Exception ignored) {
        }
        return meta;
    }

    /** A cover thumbnail no larger than widthPx x heightPx, or null when the book has none. */
    public Bitmap thumbnail(File book, int widthPx, int heightPx) {
        String name = pathKey(book) + "_" + version(book) + "_" + widthPx + "x" + heightPx + ".jpg";
        File cached = new File(thumbDir, name);
        if (cached.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(cached.getPath());
            if (b != null) return b;
        }
        if (!isEpub(book)) return null;
        Bitmap thumb = decodeCover(book, widthPx, heightPx);
        if (thumb == null) return null;
        try {
            //noinspection ResultOfMethodCallIgnored
            thumbDir.mkdirs();
            File[] old = thumbDir.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (f.getName().startsWith(pathKey(book) + "_")
                            && !f.getName().contains("_" + version(book) + "_")) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            File tmp = new File(thumbDir, name + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, out);
            }
            if (!tmp.renameTo(cached)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException ignored) {
        }
        return thumb;
    }

    private static final class Computed {
        final BookMeta meta;
        /** False when the book couldn't be opened: the meta is a guess. */
        final boolean read;

        Computed(BookMeta meta, boolean read) {
            this.meta = meta;
            this.read = read;
        }
    }

    private static Computed compute(File book) {
        String fallback = TitleCleaner.fromFileName(book.getName());
        if (!isEpub(book)) return new Computed(new BookMeta(fallback, "", false, false), true);
        String[] ta = EpubCover.titleAndAuthor(book);
        String title = ta == null || ta[0].isEmpty() ? fallback : ta[0];
        String author = ta == null ? "" : ta[1];
        EpubCover.Check check = EpubCover.check(book);
        // Only these are drawn black on the Picco; an unreadable book is unknown.
        boolean black = check.kind == EpubCover.Kind.TAG_IMAGE
                || check.kind == EpubCover.Kind.NEEDS_SNAPSHOT;
        boolean hasPicture = check.coverEntry != null;
        boolean read = ta != null && check.kind != EpubCover.Kind.UNREADABLE;
        return new Computed(new BookMeta(title, author, hasPicture, black), read);
    }

    private Bitmap decodeCover(File book, int widthPx, int heightPx) {
        EpubCover.Check check = EpubCover.check(book);
        if (check.coverEntry == null) return null;
        try (ZipFile zip = new ZipFile(book)) {
            byte[] data = EpubCover.read(zip, check.coverEntry);
            if (data == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= widthPx
                    && bounds.outHeight / (sample * 2) >= heightPx) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap full = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (full == null) return null;
            float scale = Math.min(1f, Math.min((float) widthPx / full.getWidth(),
                    (float) heightPx / full.getHeight()));
            if (scale >= 1f) return full;
            Bitmap scaled = Bitmap.createScaledBitmap(full,
                    Math.max(1, Math.round(full.getWidth() * scale)),
                    Math.max(1, Math.round(full.getHeight() * scale)), true);
            if (scaled != full) full.recycle();
            return scaled;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static boolean isEpub(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".epub");
    }

    private static String version(File f) {
        return f.lastModified() + "-" + f.length();
    }

    private static String pathKey(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(f.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(f.getAbsolutePath().hashCode());
        }
    }
}
