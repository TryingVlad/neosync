package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.neosync.boox.kosync.KoSyncClient.Error;
import dev.neosync.boox.library.SyncProblem;
import dev.neosync.boox.library.SyncProblem.Type;

public class SyncProblemTest {

    @Test
    public void classifyMapsErrors() {
        assertEquals(Type.AUTH, SyncProblem.classify(Error.AUTH_FAILED, true));
        assertEquals(Type.NO_INTERNET, SyncProblem.classify(Error.NETWORK_ERROR, false));
        assertEquals(Type.SERVER_UNREACHABLE, SyncProblem.classify(Error.NETWORK_ERROR, true));
        assertEquals(Type.SERVER_UNREACHABLE, SyncProblem.classify(Error.SERVER_ERROR, true));
        assertEquals(Type.RATE_LIMITED, SyncProblem.classify(Error.RATE_LIMITED, true));
        assertEquals(Type.OTHER, SyncProblem.classify(Error.JSON_ERROR, true));
        assertEquals(Type.NONE, SyncProblem.classify(Error.OK, true));
    }

    @Test
    public void singleFreshFailureIsNotAlarm() {
        long now = 1_000_000_000L;
        assertFalse(SyncProblem.isAlarm(now - 1000, 1, now));
    }

    @Test
    public void twoFailuresIsAlarm() {
        long now = 1_000_000_000L;
        assertTrue(SyncProblem.isAlarm(now - 1000, 2, now));
    }

    @Test
    public void fiveMinutesIsAlarm() {
        long now = 1_000_000_000L;
        assertTrue(SyncProblem.isAlarm(now - 300000, 1, now));
    }

    @Test
    public void noFailureIsNotAlarm() {
        assertFalse(SyncProblem.isAlarm(0, 0, 1_000_000_000L));
    }
}
