package dev.neosync.boox.library;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Title and author for a book NeoSync can't read itself (PDF and the like):
 * what Neo Reader keeps in its Onyx library row, when that is a real title.
 * EPUBs keep their own metadata (BookMetaCache).
 */
public final class OnyxTitle {

    /** An id rather than a title, like the names of Onyx's cloud copies. */
    private static final Pattern HEX_ID = Pattern.compile("[0-9a-fA-F]{16,}");

    private OnyxTitle() {
    }

    /**
     * @param onyxTitle   the row's title column, may be null
     * @param onyxAuthors the row's authors column, may be null
     * @param fileName    the book's file name, the fallback title
     * @return {title, author}; the author is empty when Onyx has none
     */
    public static String[] choose(String onyxTitle, String onyxAuthors, String fileName) {
        String title = onyxTitle == null ? "" : onyxTitle.trim();
        String author = onyxAuthors == null ? "" : onyxAuthors.trim();
        if (title.isEmpty() || isFileName(title, fileName) || HEX_ID.matcher(title).matches()) {
            title = TitleCleaner.fromFileName(fileName);
        }
        return new String[]{title, author};
    }

    private static boolean isFileName(String title, String fileName) {
        String t = title.toLowerCase(Locale.ROOT);
        String f = fileName.toLowerCase(Locale.ROOT);
        int dot = f.lastIndexOf('.');
        return t.equals(f) || (dot > 0 && t.equals(f.substring(0, dot)));
    }
}
