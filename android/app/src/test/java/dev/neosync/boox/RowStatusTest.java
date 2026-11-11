package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import dev.neosync.boox.library.LibraryEntry;
import dev.neosync.boox.library.RemotePosition;
import dev.neosync.boox.library.RowStatus;
import dev.neosync.boox.transfer.BookSyncStatus;

import org.junit.Test;

public class RowStatusTest {
    private static LibraryEntry both(BookSyncStatus s) {
        LibraryEntry e = new LibraryEntry();
        e.key = "a.epub";
        e.booxPath = "/b/a.epub";
        e.piccoPath = "a.epub";
        e.isEpub = true;
        e.syncStatus = s;
        e.booxLastMs = 1000;
        return e;
    }

    private static RemotePosition remote(long ts) {
        return new RemotePosition("d", 0.5, "CrossPoint", "id", ts);
    }

    @Test
    public void pdfIsBooxOnlyType() {
        LibraryEntry e = both(BookSyncStatus.SYNCED);
        e.isEpub = false;
        assertEquals(RowStatus.BOOX_ONLY_TYPE, RowStatus.of(e, true, false));
    }

    @Test
    public void onlyOnPicco() {
        LibraryEntry e = both(BookSyncStatus.NOT_HERE);
        e.booxPath = null;
        assertEquals(RowStatus.ONLY_ON_PICCO, RowStatus.of(e, true, false));
    }

    @Test
    public void notOnPiccoWhenConfigured() {
        LibraryEntry e = both(BookSyncStatus.SYNCED);
        e.piccoPath = null;
        assertEquals(RowStatus.NOT_ON_PICCO, RowStatus.of(e, true, false));
        assertEquals(RowStatus.SAME_PLACE, RowStatus.of(e, false, false));
    }

    @Test
    public void piccoAheadWhenRemoteNewerAndNotSynced() {
        LibraryEntry e = both(BookSyncStatus.CHANGED_HERE);
        e.remote = remote(2000);
        assertEquals(RowStatus.PICCO_AHEAD, RowStatus.of(e, true, false));
    }

    @Test
    public void piccoAheadNotShownWhenSynced() {
        LibraryEntry e = both(BookSyncStatus.SYNCED);
        e.remote = remote(2000);
        assertEquals(RowStatus.SAME_PLACE, RowStatus.of(e, true, false));
    }

    @Test
    public void changedHereDependsOnSyncing() {
        LibraryEntry e = both(BookSyncStatus.CHANGED_HERE);
        assertEquals(RowStatus.READ_FURTHER_HERE, RowStatus.of(e, true, true));
        assertEquals(RowStatus.WAITING_TO_SYNC, RowStatus.of(e, true, false));
    }

    @Test
    public void neverSynced() {
        assertEquals(RowStatus.NOT_SYNCED_YET,
                RowStatus.of(both(BookSyncStatus.NEVER_SYNCED), true, false));
    }

    @Test
    public void notOpenedOtherwise() {
        assertEquals(RowStatus.NOT_OPENED,
                RowStatus.of(both(BookSyncStatus.NOT_STARTED), true, false));
    }

    @Test
    public void doesNotMutateEntry() {
        LibraryEntry e = both(BookSyncStatus.SYNCED);
        RowStatus.of(e, true, false);
        assertEquals(null, e.status);
    }
}
