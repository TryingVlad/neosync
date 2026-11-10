package dev.neosync.boox.contentprovider;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Read/write access to Onyx's Metadata content provider — the table NeoReader
 * keeps per-book state in (path, progress "page/total", lastAccess, name).
 */
public class OnyxMetadataRepository {

    private static final String TAG = "OnyxMetadataRepo";

    public static final Uri METADATA_URI = Uri.parse(
            "content://com.onyx.content.database.ContentProvider/Metadata"
    );

    private static final String COL_PATH = "nativeAbsolutePath";
    private static final String COL_PROGRESS = "progress";
    private static final String COL_LAST_ACCESS = "lastAccess";
    private static final String COL_READING_STATUS = "readingStatus";
    private static final String COL_NAME = "name";
    private static final String COL_EXTRA = "extraAttributes";

    /**
     * NeoReader's actual reading position inside the extraAttributes JSON —
     * a character-offset-like value, NOT a page number. The progress column
     * only drives the library shelf; this field is what the reader restores
     * from when a book is opened.
     */
    private static final java.util.regex.Pattern POSITION_V2 =
            java.util.regex.Pattern.compile("\"current_page_position_v2\"\\s*:\\s*\"(\\d+)\"");

    /** Matches the escaped inner JSON of current_page_position_v2 (firmware 4.x). */
    private static final java.util.regex.Pattern CHAPTER_POSITION = java.util.regex.Pattern.compile(
            "\"current_page_position_v2\":\"\\{\\\\\"chapterIndex\\\\\":(\\d+),"
                    + "\\\\\"nodePosition\\\\\":(\\d+),\\\\\"textOffset\\\\\":\\d+,"
                    + "\\\\\"chapterStartPosition\\\\\":(\\d+)");

    private final ContentResolver resolver;

    public OnyxMetadataRepository(Context context) {
        this.resolver = context.getApplicationContext().getContentResolver();
    }

    public static class Book {
        public String path;
        public String name;
        public String progress;   // Onyx format: "currentPage/totalPages", may be null
        public long lastAccessMs;
        public int readingStatus;
        public String extraAttributes; // NeoReader's settings/position JSON, may be null

        public String uuid;    // Onyx's docId for this book (stats rows key on it)
        public String hashTag; // Onyx's own file MD5

        /**
         * NeoReader firmware 4.x chapter-based position:
         * {chapterIndex, nodePosition (chars into the chapter), chapterStartPosition},
         * or null when the row doesn't carry one (older firmware, PDFs).
         */
        public long[] chapterPosition() {
            if (extraAttributes == null) return null;
            java.util.regex.Matcher m = CHAPTER_POSITION.matcher(extraAttributes);
            if (!m.find()) return null;
            try {
                return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                        Long.parseLong(m.group(3))};
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** NeoReader's internal position, or -1 when absent. */
        public long positionV2() {
            if (extraAttributes == null) return -1;
            java.util.regex.Matcher m = POSITION_V2.matcher(extraAttributes);
            if (!m.find()) return -1;
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        public int currentPage() {
            return parseProgressPart(progress, 0);
        }

        public int totalPages() {
            return parseProgressPart(progress, 1);
        }

        private static int parseProgressPart(String progress, int index) {
            if (progress == null) return 0;
            String[] parts = progress.split("/");
            if (parts.length != 2) return 0;
            try {
                return Integer.parseInt(parts[index].trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** All books that have been opened at least once (progress recorded). */
    public List<Book> queryBooksWithProgress() {
        return queryBooks(COL_PROGRESS + " IS NOT NULL");
    }

    /** Every book in the Boox library, opened or not, most recently read first. */
    public List<Book> queryAllBooks() {
        return queryBooks(null);
    }

    private List<Book> queryBooks(String selection) {
        List<Book> books = new ArrayList<>();
        try (Cursor cursor = resolver.query(
                METADATA_URI,
                new String[]{COL_PATH, COL_NAME, COL_PROGRESS, COL_LAST_ACCESS, COL_READING_STATUS,
                        COL_EXTRA, "uuid", "hashTag"},
                selection,
                null,
                COL_LAST_ACCESS + " DESC")) {
            if (cursor == null) {
                Log.w(TAG, "queryBooks: null cursor — Onyx provider unavailable?");
                return books;
            }
            while (cursor.moveToNext()) {
                Book book = new Book();
                book.path = cursor.getString(0);
                book.name = cursor.getString(1);
                book.progress = cursor.getString(2);
                book.lastAccessMs = cursor.isNull(3) ? 0 : cursor.getLong(3);
                book.readingStatus = cursor.isNull(4) ? 0 : cursor.getInt(4);
                book.extraAttributes = cursor.getString(5);
                book.uuid = cursor.getString(6);
                book.hashTag = cursor.getString(7);
                if (book.path != null) {
                    books.add(book);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "queryBooksWithProgress failed", e);
        }
        return books;
    }

    /** NeoReader firmware 4.x: the position is a JSON object stored as a string. */
    private static final java.util.regex.Pattern POSITION_OBJ = java.util.regex.Pattern.compile(
            "\"(current_page_position_v[23])\":\"\\{.*?\\}\"");

    /** Builds NeoReader's chapter-based position value (inner JSON, quotes escaped). */
    public static String chapterPosition(int chapterIndex, String chapterId,
                                         long chapterStart, long chapterEnd, long nodePosition) {
        return String.format(java.util.Locale.ROOT,
                "{\\\"chapterIndex\\\":%010d,\\\"nodePosition\\\":%010d,\\\"textOffset\\\":%010d,"
                        + "\\\"chapterStartPosition\\\":%010d,\\\"chapterEndPosition\\\":%010d,"
                        + "\\\"chapterID\\\":\\\"%s\\\",\\\"xpath\\\":[]}",
                chapterIndex, nodePosition, 0, chapterStart, chapterEnd,
                chapterId.replace("\\", "").replace("\"", ""));
    }

    /**
     * Replaces NeoReader's chapter-based position (v2 and v3) in the book's
     * extraAttributes, leaving every other setting byte-identical.
     *
     * @return rows updated, or -1 when the row has no chapter-based position
     */
    public int writeChapterPosition(String path, String extraAttributes, String positionValue) {
        if (extraAttributes == null) return -1;
        java.util.regex.Matcher m = POSITION_OBJ.matcher(extraAttributes);
        StringBuffer sb = new StringBuffer();
        int found = 0;
        while (m.find()) {
            found++;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    "\"" + m.group(1) + "\":\"" + positionValue + "\""));
        }
        if (found == 0) return -1;
        m.appendTail(sb);
        try (ContentProviderClient client =
                     resolver.acquireUnstableContentProviderClient(METADATA_URI)) {
            if (client == null) return 0;
            ContentValues values = new ContentValues();
            values.put(COL_EXTRA, sb.toString());
            int rows = client.update(METADATA_URI, values, COL_PATH + " = ?", new String[]{path});
            Log.i(TAG, "writeChapterPosition " + path + " -> " + positionValue + " (" + rows + " row(s))");
            return rows;
        } catch (Exception e) {
            Log.e(TAG, "writeChapterPosition failed for " + path, e);
            return 0;
        }
    }

    /**
     * Writes remote progress into Onyx's Metadata row for this book so
     * NeoReader reflects it. Keeps the Onyx "page/total" string format.
     *
     * @param book       current row (source of the extraAttributes to patch)
     * @param positionV2 NeoReader-internal position to set, or -1 to leave the
     *                   reader's own position untouched (shelf-only update)
     */
    public boolean writeProgress(Book book, int page, int totalPages, long lastAccessMs,
                                 long positionV2) {
        String progress = page + "/" + totalPages;
        try (ContentProviderClient client =
                     resolver.acquireUnstableContentProviderClient(METADATA_URI)) {
            if (client == null) {
                Log.e(TAG, "writeProgress: could not acquire ContentProviderClient");
                return false;
            }
            ContentValues values = new ContentValues();
            values.put(COL_PROGRESS, progress);
            values.put(COL_LAST_ACCESS, lastAccessMs);
            values.put(COL_READING_STATUS, 1);
            if (positionV2 >= 0 && book.extraAttributes != null
                    && POSITION_V2.matcher(book.extraAttributes).find()) {
                // Patch only the position value; the rest of NeoReader's
                // settings blob must stay byte-identical.
                String patched = POSITION_V2.matcher(book.extraAttributes).replaceFirst(
                        "\"current_page_position_v2\":\"" + positionV2 + "\"");
                values.put(COL_EXTRA, patched);
            }
            int rows = client.update(METADATA_URI, values,
                    COL_PATH + " = ?", new String[]{book.path});
            Log.i(TAG, "writeProgress " + book.path + " -> " + progress
                    + (positionV2 >= 0 ? " pos=" + positionV2 : "") + " (" + rows + " row(s))");
            return rows > 0;
        } catch (Exception e) {
            Log.e(TAG, "writeProgress failed for " + book.path, e);
            return false;
        }
    }
}
