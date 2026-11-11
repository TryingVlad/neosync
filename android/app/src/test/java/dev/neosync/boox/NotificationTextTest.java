package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.neosync.boox.library.NotificationText;

import org.junit.Test;

public class NotificationTextTest {
    private static final String SYNCED = "%1$s synced · %2$s";
    private static final String ALARM = "Positions not syncing · tap to fix";

    private static String text(boolean alarm, String metaTitle, String path, double pct) {
        return NotificationText.of(alarm, ALARM, SYNCED, metaTitle, path, pct);
    }

    @Test public void syncedUsesTheBookTitle() {
        assertEquals("Fictions synced · 88%",
                text(false, "Fictions", "/storage/emulated/0/Books/borges.epub", 0.876));
    }
    @Test public void missingTitleFallsBackToCleanedFileName() {
        assertEquals("platon banquet synced · 12%",
                text(false, null, "/storage/emulated/0/Books/platon_banquet.epub", 0.12));
        assertEquals("platon banquet synced · 12%",
                text(false, "", "/storage/emulated/0/Books/platon_banquet.epub", 0.12));
    }
    @Test public void percentageIsClamped() {
        assertEquals("A synced · 100%", text(false, "A", "/b/a.epub", 1.7));
        assertEquals("A synced · 0%", text(false, "A", "/b/a.epub", 0.004));
    }
    @Test public void alarmWinsOverLastSync() {
        assertEquals(ALARM, text(true, "Fictions", "/b/f.epub", 0.5));
        assertEquals(ALARM, text(true, null, null, -1));
    }
    @Test public void nothingSyncedYetGivesNoText() {
        assertNull(text(false, null, null, -1));
        assertNull(text(false, null, "", 0.5));
    }
    @Test public void unknownPercentageGivesNoText() {
        assertNull(text(false, "A", "/b/a.epub", -1));
    }
}
