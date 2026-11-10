package dev.neosync.boox.transfer;

/**
 * How a book on the Picco stands with this tablet, judged only from what the
 * tablet already knows (its Onyx reading position and NeoSync's per-book
 * record). No server request: the default sync server is rate-limited, and
 * asking it about every book in the library would use that budget up.
 */
public enum BookSyncStatus {
    /** The position here is the one NeoSync last sent or fetched. */
    SYNCED,
    /** Read further here since the last sync; the sync service picks it up. */
    CHANGED_HERE,
    /** On this tablet, but NeoSync never synced it. */
    NEVER_SYNCED,
    /** Opened on this tablet, but no position yet (not started). */
    NOT_STARTED,
    /** Not in this tablet's library (or never opened here). */
    NOT_HERE;

    /**
     * @param localProgress   Onyx progress string ("42/300"), null when the
     *                        book is not in this tablet's library
     * @param pushedProgress  progress NeoSync last pushed ("" if never)
     * @param writtenProgress progress NeoSync last wrote locally ("" if never)
     */
    public static BookSyncStatus of(boolean onThisTablet, String localProgress,
                                    int currentPage, String pushedProgress,
                                    String writtenProgress) {
        if (!onThisTablet) {
            return NOT_HERE;
        }
        boolean everSynced = !isEmpty(pushedProgress) || !isEmpty(writtenProgress);
        if (localProgress != null && (localProgress.equals(pushedProgress)
                || localProgress.equals(writtenProgress))) {
            return SYNCED;
        }
        if (currentPage <= 0) {
            return everSynced ? CHANGED_HERE : NOT_STARTED;
        }
        return everSynced ? CHANGED_HERE : NEVER_SYNCED;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
