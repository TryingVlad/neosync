package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import dev.neosync.boox.kosync.KoReaderDocumentHash;
import dev.neosync.boox.kosync.SyncActions;

/** The same accented name, spelled two ways, must find the same book. */
public class FilenameSpellingTest {

    // As the Boox stores it: "e" followed by a combining acute accent.
    private static final String BOOX_PATH = "/storage/emulated/0/Push/Jours de révolte "
            + "(Lee-Chi, Gigi Leung) (z-library.sk, 1lib.sk, z-lib.sk).epub";
    private static final String DECOMPOSED = "fdf67fed6e775bf8d556dff5755801df";
    // The same name with a single "é" character, as the Picco has it.
    private static final String COMPOSED = "f63c1c3ea6363fc9658f9a32d3eb1de2";

    @Test
    public void accentedNameHasBothSpellings() {
        assertEquals(Arrays.asList(DECOMPOSED, COMPOSED),
                KoReaderDocumentHash.filenameVariants(BOOX_PATH));
        assertEquals(Collections.singletonList(COMPOSED),
                SyncActions.alternateHashes(BOOX_PATH, DECOMPOSED));
    }

    @Test
    public void plainNameHasOneSpelling() {
        assertEquals(1, KoReaderDocumentHash.filenameVariants("/b/platon_banquet_0.epub").size());
        assertTrue(SyncActions.alternateHashes("/b/platon_banquet_0.epub",
                "0bb96148723c92379599ea670adb56ce").isEmpty());
    }

    @Test
    public void contentHashHasNoAlternates() {
        assertTrue(SyncActions.alternateHashes(BOOX_PATH, "bada864b770592bdba8ba88aa59e8e42")
                .isEmpty());
    }
}
