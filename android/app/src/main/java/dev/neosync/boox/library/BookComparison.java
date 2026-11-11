package dev.neosync.boox.library;

/** Pure rules deciding the book sheet's verdict and which buttons it offers. */
public final class BookComparison {
    public enum Verdict { SAME, OTHER_MORE_RECENT, HERE_MORE_RECENT, NOTHING_ONLINE, NOT_CHECKED }

    public enum Action { NONE, JUMP_TO_REMOTE, SEND_PLACE, SEND_FILE }

    private static final double SAME_PLACE = 0.005;

    public final Verdict verdict;
    public final Action primary;
    /** The "instead" link: SEND_PLACE, JUMP_TO_REMOTE or NONE. */
    public final Action secondary;

    private BookComparison(Verdict verdict, Action primary, Action secondary) {
        this.verdict = verdict;
        this.primary = primary;
        this.secondary = secondary;
    }

    public static BookComparison of(LibraryEntry e, boolean liveCheckOk, boolean hasPiccoList) {
        return of(e, liveCheckOk, hasPiccoList, null);
    }

    /**
     * @param hasPiccoList a Picco list exists, so e.piccoPath == null means
     *                     the Picco lacks the file
     * @param ownOnServer the server's record when this Boox wrote it (the live
     *                    check's answer was our own), else null. When it holds
     *                    the Boox's place, that place is already sent: no
     *                    "Send this place", and with no other device's record
     *                    the verdict is "Same place.". e.remote stays the
     *                    other device's last record.
     */
    public static BookComparison of(LibraryEntry e, boolean liveCheckOk, boolean hasPiccoList,
                                    RemotePosition ownOnServer) {
        if (e.booxPath == null) {
            return new BookComparison(Verdict.NOT_CHECKED, Action.NONE, Action.NONE);
        }
        boolean sendFile = hasPiccoList && e.piccoPath == null && e.isEpub;
        BookComparison c = compare(e, liveCheckOk);
        if (liveCheckOk && serverHasThisPlace(e, ownOnServer)) {
            if (e.remote != null && c.verdict != Verdict.SAME
                    && ownOnServer.timestampMs > e.remote.timestampMs) {
                // The server's newest record is this place, as the list says
                // ("Same place"); the other device's older place stays a link.
                c = new BookComparison(Verdict.SAME, Action.NONE, Action.JUMP_TO_REMOTE);
            } else {
                c = new BookComparison(e.remote == null ? Verdict.SAME : c.verdict,
                        c.primary == Action.SEND_PLACE ? Action.NONE : c.primary,
                        c.secondary == Action.SEND_PLACE ? Action.NONE : c.secondary);
            }
        }
        if (sendFile) {
            return new BookComparison(c.verdict, Action.SEND_FILE, Action.NONE);
        }
        return c;
    }

    /** The server's latest record is this Boox's own, at the Boox's place now. */
    static boolean serverHasThisPlace(LibraryEntry e, RemotePosition ownOnServer) {
        return ownOnServer != null && e.booxPct >= 0
                && Math.abs(e.booxPct - ownOnServer.percentage) < SAME_PLACE;
    }

    private static BookComparison compare(LibraryEntry e, boolean liveCheckOk) {
        if (!liveCheckOk) {
            return new BookComparison(Verdict.NOT_CHECKED, Action.NONE, Action.NONE);
        }
        RemotePosition r = e.remote;
        if (r == null) {
            return new BookComparison(Verdict.NOTHING_ONLINE, Action.SEND_PLACE, Action.NONE);
        }
        if (Math.abs(e.booxPct - r.percentage) < SAME_PLACE) {
            return new BookComparison(Verdict.SAME, Action.NONE, Action.NONE);
        }
        if (r.timestampMs > e.booxLastMs) {
            return new BookComparison(Verdict.OTHER_MORE_RECENT, Action.JUMP_TO_REMOTE,
                    Action.SEND_PLACE);
        }
        if (r.percentage > e.booxPct) {
            // More recent here only by Neo Reader's last open, while the other
            // device is further along: sending must not be one black tap.
            return new BookComparison(Verdict.HERE_MORE_RECENT, Action.NONE, Action.SEND_PLACE);
        }
        return new BookComparison(Verdict.HERE_MORE_RECENT, Action.SEND_PLACE,
                Action.JUMP_TO_REMOTE);
    }
}
