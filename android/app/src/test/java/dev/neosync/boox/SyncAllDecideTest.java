package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import dev.neosync.boox.kosync.SyncAll;
import dev.neosync.boox.kosync.SyncAll.Action;

public class SyncAllDecideTest {

    @Test
    public void samePositionDoesNothing() {
        assertEquals(Action.NOTHING, SyncAll.decide(true, true, true, 200, 100));
    }

    @Test
    public void piccoAheadAndNothingReadHereFetches() {
        // Even when NeoReader bumped lastAccess by just opening the book.
        assertEquals(Action.FETCH, SyncAll.decide(true, false, false, 100, 999));
    }

    @Test
    public void readHereAndServerHasOnlyOurOwnPositionSends() {
        assertEquals(Action.SEND, SyncAll.decide(false, false, true, 0, 100));
    }

    @Test
    public void bothChangedNewestWins() {
        assertEquals(Action.FETCH, SyncAll.decide(true, false, true, 200, 100));
        assertEquals(Action.SEND, SyncAll.decide(true, false, true, 100, 200));
    }

    @Test
    public void nothingNewAnywhere() {
        assertEquals(Action.NOTHING, SyncAll.decide(false, false, false, 0, 100));
    }
}
