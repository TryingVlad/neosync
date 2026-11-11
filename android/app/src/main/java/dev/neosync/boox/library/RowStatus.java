package dev.neosync.boox.library;

import dev.neosync.boox.transfer.BookSyncStatus;

/** What a library row says about where the two devices stand. */
public enum RowStatus {
    SAME_PLACE, READ_FURTHER_HERE, WAITING_TO_SYNC, PICCO_AHEAD, NOT_ON_PICCO,
    ONLY_ON_PICCO, NOT_SYNCED_YET, NOT_OPENED, BOOX_ONLY_TYPE;

    /** Does not modify the entry. */
    public static RowStatus of(LibraryEntry e, boolean piccoConfigured, boolean syncing) {
        boolean onBoox = e.booxPath != null;
        if (!e.isEpub && onBoox) {
            return BOOX_ONLY_TYPE;
        }
        if (!onBoox) {
            return ONLY_ON_PICCO;
        }
        if (piccoConfigured && e.piccoPath == null) {
            return NOT_ON_PICCO;
        }
        if (e.remote != null && e.remote.timestampMs > e.booxLastMs
                && e.syncStatus != BookSyncStatus.SYNCED) {
            return PICCO_AHEAD;
        }
        if (e.syncStatus == BookSyncStatus.SYNCED) {
            return SAME_PLACE;
        }
        if (e.syncStatus == BookSyncStatus.CHANGED_HERE) {
            return syncing ? READ_FURTHER_HERE : WAITING_TO_SYNC;
        }
        if (e.syncStatus == BookSyncStatus.NEVER_SYNCED) {
            return NOT_SYNCED_YET;
        }
        return NOT_OPENED;
    }
}
