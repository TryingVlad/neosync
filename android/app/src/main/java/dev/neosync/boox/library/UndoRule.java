package dev.neosync.boox.library;

/** When "Undo jump" is still offered for a book (spec §7.5). */
public final class UndoRule {

    /** How long an undo stays available after the jump. */
    public static final long WINDOW_MS = 86400000L;

    private UndoRule() {
    }

    /**
     * True for 24 hours after a jump (on the Boox clock), as long as neither
     * device has moved the book since: the Boox row still holds what the jump
     * wrote, and the server's position is no newer than the one the jump
     * applied (both server timestamps, so clock skew doesn't matter).
     *
     * @param savedAtMs            when the jump was saved (Boox clock), 0 when there is none
     * @param writtenProgress      the "page/total" the jump wrote
     * @param currentProgress      the row's "page/total" now
     * @param jumpRemoteMs         server timestamp of the position the jump applied
     * @param remoteChangedAfterMs server timestamp of the latest known server position
     */
    public static boolean available(long savedAtMs, String writtenProgress, String currentProgress,
                                    long jumpRemoteMs, long remoteChangedAfterMs, long nowMs) {
        return savedAtMs > 0
                && nowMs - savedAtMs < WINDOW_MS
                && currentProgress != null && currentProgress.equals(writtenProgress)
                && remoteChangedAfterMs <= jumpRemoteMs;
    }
}
