package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.neosync.boox.contentprovider.OnyxMetadataRepository;

import org.junit.Test;

public class ChapterPositionValueTest {
    @Test
    public void readsBackWhatWasWritten() {
        String value = OnyxMetadataRepository.chapterPosition(3, "ch3", 1200, 5400, 77);
        String extra = "{\"fontSize\":\"12\",\"current_page_position_v2\":\"" + value
                + "\",\"current_page_position_v3\":\"" + value + "\",\"zoom\":\"1\"}";
        assertEquals(value, OnyxMetadataRepository.chapterPositionValue(extra));
    }

    @Test
    public void noneInOldFirmwareRow() {
        assertNull(OnyxMetadataRepository.chapterPositionValue(
                "{\"current_page_position_v2\":\"12345\"}"));
        assertNull(OnyxMetadataRepository.chapterPositionValue(null));
    }
}
