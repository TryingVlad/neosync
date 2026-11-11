package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import dev.neosync.boox.library.LibraryPager;

import org.junit.Test;

public class LibraryPagerTest {
    @Test
    public void pageCountRoundsUp() {
        LibraryPager p = new LibraryPager(19, 6);
        assertEquals(4, p.pageCount());
        assertEquals(18, p.from(3));
        assertEquals(19, p.to(3));
    }

    @Test
    public void clampsPageWhenPageSizeGrows() {
        assertEquals(3, new LibraryPager(19, 6).clamp(3));
        assertEquals(1, new LibraryPager(19, 10).clamp(3));
    }

    @Test
    public void emptyHasOnePage() {
        LibraryPager p = new LibraryPager(0, 6);
        assertEquals(1, p.pageCount());
        assertEquals(0, p.from(0));
        assertEquals(0, p.to(0));
    }
}
