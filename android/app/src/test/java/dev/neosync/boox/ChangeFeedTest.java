package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;

import dev.neosync.boox.kosync.KoSyncClient;

public class ChangeFeedTest {

    @Test
    public void parsesAPageOfChanges() throws Exception {
        KoSyncClient.Changes changes = KoSyncClient.parseChanges("{\"cursor\":4812,\"more\":true,"
                + "\"changes\":[{\"document\":\"a1b2\",\"aliases\":[\"ffee\",\"0011\"],"
                + "\"percentage\":0.4213,\"progress\":\"/body/p[3]\",\"position\":null,"
                + "\"device\":\"CrossPoint\",\"device_id\":\"crosspoint-reader\","
                + "\"timestamp\":1791234567}]}");
        assertEquals(4812, changes.cursor);
        assertTrue(changes.more);
        assertEquals(1, changes.items.size());
        KoSyncClient.Progress p = changes.items.get(0);
        assertEquals("a1b2", p.document);
        assertEquals(Arrays.asList("ffee", "0011"), p.aliases);
        assertEquals(0.4213, p.percentage, 1e-9);
        assertEquals("crosspoint-reader", p.deviceId);
        assertEquals(1791234567L, p.timestampSec);
    }

    @Test
    public void emptyFeedKeepsTheCursor() throws Exception {
        KoSyncClient.Changes changes = KoSyncClient.parseChanges(
                "{\"cursor\":77,\"more\":false,\"changes\":[]}");
        assertEquals(77, changes.cursor);
        assertFalse(changes.more);
        assertTrue(changes.items.isEmpty());
    }

    @Test(expected = JSONException.class)
    public void somethingElseIsNotAFeed() throws Exception {
        // e.g. a server that answers every path with its progress JSON
        KoSyncClient.parseChanges("{\"percentage\":0.5,\"timestamp\":1}");
    }
}
