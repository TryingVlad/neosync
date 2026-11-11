package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import dev.neosync.boox.library.LibraryEntry;
import dev.neosync.boox.library.LibraryModel;
import dev.neosync.boox.library.RemotePosition;
import dev.neosync.boox.library.RowStatus;
import dev.neosync.boox.transfer.BookSyncStatus;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

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
    public void notOnPiccoOnlyWithAPiccoList() {
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
    public void samePercentButNewerRemoteIsSamePlace() {
        LibraryEntry e = both(BookSyncStatus.NEVER_SYNCED);
        e.booxPct = 0.503; // within half a percent of the remote 0.5
        e.remote = remote(2000);
        assertEquals(RowStatus.SAME_PLACE, RowStatus.of(e, true, true));
        e.syncStatus = BookSyncStatus.CHANGED_HERE;
        assertEquals(RowStatus.SAME_PLACE, RowStatus.of(e, true, false));
    }

    @Test
    public void syncedButOtherDeviceElsewhereIsPlaceSent() {
        // This Boox's place is on the server, but the Picco's last record
        // differs: not "Same place", the Picco hasn't picked it up yet.
        LibraryEntry e = both(BookSyncStatus.SYNCED);
        e.booxPct = 0.0;
        e.remote = new RemotePosition("d", 0.02, "CrossPoint", "id", 500);
        assertEquals(RowStatus.PLACE_SENT, RowStatus.of(e, true, false));
        e.remote = null; // no other device's record: nothing to differ from
        assertEquals(RowStatus.SAME_PLACE, RowStatus.of(e, true, false));
    }

    @Test
    public void differentAndNewerRemoteIsPiccoAhead() {
        LibraryEntry e = both(BookSyncStatus.NEVER_SYNCED);
        e.booxPct = 0.495; // exactly half a percent apart counts as different
        e.remote = remote(2000);
        assertEquals(RowStatus.PICCO_AHEAD, RowStatus.of(e, true, true));
        e.remote = remote(500); // different but older: not ahead
        assertEquals(RowStatus.NOT_SYNCED_YET, RowStatus.of(e, true, true));
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

    @Test
    public void noPiccoListMeansNoNotOnPicco() {
        // An address that never answered has no list: the merge is told
        // "no Picco list", so a Boox book shows its place, not "Not on the Picco".
        List<LibraryEntry> rows = LibraryModel.merge(Collections.singletonList(
                new LibraryModel.BooxBook("/b/a.epub", 0.4, 1000, BookSyncStatus.CHANGED_HERE,
                        "A", "")), Collections.<String>emptyList(), null, false, false);
        assertEquals(RowStatus.WAITING_TO_SYNC, rows.get(0).status);
        rows = LibraryModel.merge(Collections.singletonList(
                new LibraryModel.BooxBook("/b/a.epub", 0.4, 1000, BookSyncStatus.CHANGED_HERE,
                        "A", "")), Collections.<String>emptyList(), null, true, false);
        assertEquals(RowStatus.NOT_ON_PICCO, rows.get(0).status);
    }
}
