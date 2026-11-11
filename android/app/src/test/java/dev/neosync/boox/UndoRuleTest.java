package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.library.UndoRule;

import org.junit.Test;

public class UndoRuleTest {
    private static final long SAVED = 1_000_000_000L;

    @Test
    public void availableRightAfterJump() {
        assertTrue(UndoRule.available(SAVED, "50/300", "50/300", SAVED - 5000, SAVED + 1000));
    }

    @Test
    public void expiresAfter24h() {
        assertTrue(UndoRule.available(SAVED, "50/300", "50/300", SAVED, SAVED + 86399999L));
        assertFalse(UndoRule.available(SAVED, "50/300", "50/300", SAVED, SAVED + 86400000L));
    }

    @Test
    public void goneWhenReadAfterJump() {
        assertFalse(UndoRule.available(SAVED, "50/300", "60/300", SAVED, SAVED + 1000));
    }

    @Test
    public void goneWhenPiccoMovedAgain() {
        assertFalse(UndoRule.available(SAVED, "50/300", "50/300", SAVED + 1, SAVED + 1000));
    }

    @Test
    public void noUndoWhenNothingWasWritten() {
        assertFalse(UndoRule.available(0, "50/300", "50/300", 0, SAVED));
    }

    @Test
    public void chapterPositionReadsBackWhatWasWritten() {
        String value = OnyxMetadataRepository.chapterPosition(3, "ch3", 1200, 5400, 77);
        String extra = "{\"fontSize\":\"12\",\"current_page_position_v2\":\"" + value
                + "\",\"current_page_position_v3\":\"" + value + "\",\"zoom\":\"1\"}";
        assertEquals(value, OnyxMetadataRepository.chapterPositionValue(extra));
    }

    @Test
    public void noChapterPositionInOldFirmwareRow() {
        assertNull(OnyxMetadataRepository.chapterPositionValue(
                "{\"current_page_position_v2\":\"12345\"}"));
        assertNull(OnyxMetadataRepository.chapterPositionValue(null));
    }
}
