package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.library.RemotePosition;

import org.junit.Test;

public class RemotePositionTest {

    private static RemotePosition at(long ms) {
        return new RemotePosition("abc", 0.5, "CrossPoint", "dev1", ms);
    }

    @Test
    public void jsonRoundTrip() {
        RemotePosition p = new RemotePosition("abc", 0.734, "CrossPoint", "dev1", 1700000000000L);
        RemotePosition q = RemotePosition.fromJson(p.toJson());
        assertEquals("abc", q.document);
        assertEquals(0.734, q.percentage, 0.0);
        assertEquals("CrossPoint", q.device);
        assertEquals("dev1", q.deviceId);
        assertEquals(1700000000000L, q.timestampMs);
    }

    @Test
    public void fromJsonRejectsGarbage() {
        assertNull(RemotePosition.fromJson("{"));
        assertNull(RemotePosition.fromJson(null));
    }

    @Test
    public void newerKeepsLatestTimestamp() {
        RemotePosition a1 = at(1000), a3 = at(3000), b = at(2000);
        assertSame(b, RemotePosition.newer(a1, b));
        assertSame(a3, RemotePosition.newer(a3, b));
        assertSame(b, RemotePosition.newer(null, b));
        assertSame(a1, RemotePosition.newer(a1, null));
    }

    @Test
    public void fromProgressConvertsSeconds() {
        KoSyncClient.Progress p = new KoSyncClient.Progress();
        p.document = "abc";
        p.timestampSec = 1700000000L;
        assertEquals(1700000000000L, RemotePosition.from(p).timestampMs);
    }

    @Test
    public void crossPointDetection() {
        assertTrue(new RemotePosition("a", 0, "CrossPoint", "d", 0).isCrossPoint());
        assertTrue(new RemotePosition("a", 0, "", "d", 0).isCrossPoint());
        assertFalse(new RemotePosition("a", 0, "KOReader", "d", 0).isCrossPoint());
    }
}
