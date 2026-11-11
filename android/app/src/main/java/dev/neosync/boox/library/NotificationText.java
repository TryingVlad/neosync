package dev.neosync.boox.library;

/**
 * The foreground notification's line (spec 7.7): the alarm while sync is
 * failing, else "<title> synced · 88%" for the latest successful sync,
 * else null (the default title). Pure; formats come from resources.
 */
public final class NotificationText {

    private NotificationText() {
    }

    /**
     * @param metaTitle the book's title from BookMetaCache, or null/empty
     * @param path      the last synced book's path, or null/empty when none
     * @param percentage 0..1, or negative when unknown
     */
    public static String of(boolean alarm, String alarmText, String syncedFormat,
                            String metaTitle, String path, double percentage) {
        if (alarm) {
            return alarmText;
        }
        if (path == null || path.isEmpty() || percentage < 0) {
            return null;
        }
        return String.format(syncedFormat, title(metaTitle, path), percent(percentage));
    }

    /** The cached title, or the file name cleaned up (TitleCleaner). */
    static String title(String metaTitle, String path) {
        if (metaTitle != null && !metaTitle.isEmpty()) {
            return metaTitle;
        }
        return TitleCleaner.fromFileName(path.substring(path.lastIndexOf('/') + 1));
    }

    /** "88%", clamped to 0..100. */
    static String percent(double percentage) {
        return Math.round(Math.max(0.0, Math.min(1.0, percentage)) * 100) + "%";
    }
}
