package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import dev.neosync.boox.library.TitleCleaner;

import org.junit.Test;

public class TitleCleanerTest {
    @Test public void stripsExtension() {
        assertEquals("republic", TitleCleaner.fromFileName("republic.epub"));
    }
    @Test public void underscoresBecomeSpaces() {
        assertEquals("platon banquet 0", TitleCleaner.fromFileName("platon_banquet_0.epub"));
    }
    @Test public void stripsDomainParenthetical() {
        assertEquals("Fictions (Jorge Luis Borges)", TitleCleaner.fromFileName(
                "Fictions (Jorge Luis Borges) (z-library.sk, 1lib.sk, z-lib.sk).epub"));
    }
    @Test public void keepsNonDomainParenthetical() {
        assertEquals("Dune (Frank Herbert)", TitleCleaner.fromFileName("Dune (Frank Herbert).epub"));
    }
    @Test public void trimsTruncationUnderscore() {
        assertEquals("Sufism An Introduction to t…", TitleCleaner.fromFileName(
                "Sufism An Introduction to t_ (z-library.sk, 1lib.sk, z-lib.sk).epub"));
    }
    @Test public void emptyFallsBackToFileName() {
        assertEquals(".epub", TitleCleaner.fromFileName(".epub"));
    }
}
