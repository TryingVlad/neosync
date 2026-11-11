package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import dev.neosync.boox.transfer.CoverFixer;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * "Only on the Picco" must come from the Boox library itself, never from
 * which files the app can read: without file access every book looked
 * Picco-only, and fixing those would have made the two copies differ.
 */
public class PiccoOnlyBooksTest {

    @Test
    public void booksInTheBooxLibraryAreNeverPiccoOnly() {
        List<String> onPicco = Arrays.asList("books/Sufism (z-lib).epub", "books/Dune.epub",
                "Notes.pdf", "Other/republic.epub");
        List<String> booxPaths = Arrays.asList(
                "/storage/emulated/0/Books/Sufism/Sufism (z-lib).epub",
                "/storage/emulated/0/Books/republic.epub");
        assertEquals(Collections.singletonList("books/Dune.epub"),
                CoverFixer.piccoOnlyEpubs(onPicco, booxPaths));
    }

    @Test
    public void emptyBooxLibraryMeansEveryPiccoEpub() {
        assertEquals(Arrays.asList("a.epub", "b/c.EPUB"),
                CoverFixer.piccoOnlyEpubs(Arrays.asList("a.epub", "b/c.EPUB", "d.pdf"),
                        Collections.emptyList()));
    }

    @Test
    public void accentedTwinInOtherNormalFormIsNotPiccoOnly() {
        String nfc = java.text.Normalizer.normalize("Café.epub", java.text.Normalizer.Form.NFC);
        String nfd = java.text.Normalizer.normalize("Café.epub", java.text.Normalizer.Form.NFD);
        assertEquals(Collections.emptyList(), CoverFixer.piccoOnlyEpubs(
                Collections.singletonList("books/" + nfd),
                Collections.singletonList("/storage/emulated/0/Books/" + nfc)));
    }
}
