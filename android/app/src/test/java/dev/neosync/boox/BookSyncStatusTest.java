package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dev.neosync.boox.transfer.BookSyncStatus;

public class BookSyncStatusTest {

    @Test
    public void notOnThisTablet() {
        assertEquals(BookSyncStatus.NOT_HERE, BookSyncStatus.of(false, null, 0, "", ""));
    }

    @Test
    public void syncedWhenPositionMatchesLastPushOrPull() {
        assertEquals(BookSyncStatus.SYNCED, BookSyncStatus.of(true, "42/300", 42, "42/300", ""));
        assertEquals(BookSyncStatus.SYNCED, BookSyncStatus.of(true, "42/300", 42, "10/300", "42/300"));
    }

    @Test
    public void readFurtherSinceLastSync() {
        assertEquals(BookSyncStatus.CHANGED_HERE, BookSyncStatus.of(true, "50/300", 50, "42/300", ""));
    }

    @Test
    public void neverSyncedOrNotStarted() {
        assertEquals(BookSyncStatus.NEVER_SYNCED, BookSyncStatus.of(true, "5/300", 5, "", ""));
        assertEquals(BookSyncStatus.NOT_STARTED, BookSyncStatus.of(true, null, 0, "", ""));
    }
}
