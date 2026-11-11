package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.neosync.boox.library.LibraryEntry;
import dev.neosync.boox.library.LibraryModel;
import dev.neosync.boox.library.LibraryModel.BooxBook;
import dev.neosync.boox.library.LibraryModel.Filter;
import dev.neosync.boox.library.RemotePosition;
import dev.neosync.boox.transfer.BookSyncStatus;

import org.junit.Test;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LibraryModelTest {
    private static BooxBook book(String path, double pct, long last, BookSyncStatus s) {
        return new BooxBook(path, pct, last, s, path.substring(path.lastIndexOf('/') + 1), "");
    }

    private static List<LibraryEntry> merge(List<BooxBook> b, List<String> p,
                                            Map<String, RemotePosition> r) {
        return LibraryModel.merge(b, p, r, true, false);
    }

    private static final Map<String, RemotePosition> NONE = Collections.emptyMap();

    @Test
    public void mergesSameFileNameAcrossDevices() {
        List<LibraryEntry> l = merge(
                Arrays.asList(book("/Books/a/Dune.epub", 0.2, 5, BookSyncStatus.NEVER_SYNCED)),
                Arrays.asList("books/Dune.epub"), NONE);
        assertEquals(1, l.size());
        assertEquals("/Books/a/Dune.epub", l.get(0).booxPath);
        assertEquals("books/Dune.epub", l.get(0).piccoPath);
    }

    @Test
    public void accentSpellingsMergeIntoOneRow() {
        String nfc = Normalizer.normalize("Société.epub", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("Société.epub", Normalizer.Form.NFD);
        List<LibraryEntry> l = merge(
                Arrays.asList(book("/B/" + nfc, 0, 0, BookSyncStatus.NOT_STARTED)),
                Arrays.asList("books/" + nfd), NONE);
        assertEquals(1, l.size());
        assertEquals(nfc, l.get(0).key);
    }

    @Test
    public void piccoOnlyAndBooxOnlyKept() {
        List<LibraryEntry> l = merge(
                Arrays.asList(book("/B/x.epub", 0, 0, BookSyncStatus.NOT_STARTED)),
                Arrays.asList("books/Some Book (Dom.com).epub"), NONE);
        assertEquals(2, l.size());
        for (LibraryEntry e : l) {
            if (e.booxPath == null) {
                assertEquals("", e.author);
                assertEquals(-1.0, e.piccoPct, 0);
                assertEquals(dev.neosync.boox.library.RowStatus.ONLY_ON_PICCO, e.status);
            } else {
                assertEquals(null, e.piccoPath);
                assertEquals(dev.neosync.boox.library.RowStatus.NOT_ON_PICCO, e.status);
            }
        }
    }

    @Test
    public void duplicateOnOneSideKeepsLatestActivity() {
        List<LibraryEntry> l = merge(Arrays.asList(
                book("/B/old/a.epub", 0.1, 5, BookSyncStatus.NEVER_SYNCED),
                book("/B/new/a.epub", 0.5, 9, BookSyncStatus.NEVER_SYNCED)),
                new ArrayList<String>(), NONE);
        assertEquals(1, l.size());
        assertEquals("/B/new/a.epub", l.get(0).booxPath);
    }

    @Test
    public void syncedUsesBooxPctForPicco() {
        Map<String, RemotePosition> r = new HashMap<>();
        r.put("a.epub", new RemotePosition("d", 0.9, "CrossPoint", "i", 1));
        LibraryEntry e = merge(
                Arrays.asList(book("/B/a.epub", 0.4, 5, BookSyncStatus.SYNCED)),
                Arrays.asList("a.epub"), r).get(0);
        assertEquals(0.4, e.piccoPct, 0);
    }

    @Test
    public void remoteUsedWhenNotSynced() {
        Map<String, RemotePosition> r = new HashMap<>();
        r.put("a.epub", new RemotePosition("d", 0.9, "CrossPoint", "i", 1));
        LibraryEntry e = merge(
                Arrays.asList(book("/B/a.epub", 0.4, 5, BookSyncStatus.CHANGED_HERE)),
                Arrays.asList("a.epub"), r).get(0);
        assertEquals(0.9, e.piccoPct, 0);
        assertEquals(5, e.activityMs);
    }

    @Test
    public void sortedByActivityNewestFirst() {
        Map<String, RemotePosition> r = new HashMap<>();
        r.put("c.epub", new RemotePosition("d", 0.9, "CrossPoint", "i", 50));
        List<LibraryEntry> l = merge(Arrays.asList(
                book("/B/a.epub", 0, 10, BookSyncStatus.NEVER_SYNCED),
                book("/B/b.epub", 0, 30, BookSyncStatus.NEVER_SYNCED)),
                Arrays.asList("c.epub"), r);
        assertEquals("c.epub", l.get(0).key);
        assertEquals("b.epub", l.get(1).key);
        assertEquals("a.epub", l.get(2).key);
    }

    private List<LibraryEntry> sample() {
        return merge(Arrays.asList(
                book("/B/reading.epub", 0.5, 3, BookSyncStatus.NEVER_SYNCED),
                book("/B/done.epub", 1.0, 2, BookSyncStatus.NEVER_SYNCED),
                book("/B/doc.pdf", 0.5, 1, BookSyncStatus.NEVER_SYNCED),
                new BooxBook("/B/irish.epub", 0, 0, BookSyncStatus.NOT_STARTED, "Irish",
                        "Máire Roche")),
                Arrays.asList("p.epub", "done.epub"), NONE);
    }

    @Test
    public void filterReading() {
        List<LibraryEntry> l = LibraryModel.filter(sample(), Filter.READING, "");
        List<String> keys = new ArrayList<>();
        for (LibraryEntry e : l) {
            keys.add(e.key);
        }
        assertEquals(Arrays.asList("reading.epub", "doc.pdf"), keys);
    }

    @Test
    public void filterOnlyHereExcludesPdf() {
        List<LibraryEntry> l = LibraryModel.filter(sample(), Filter.ONLY_HERE, "");
        List<String> keys = new ArrayList<>();
        for (LibraryEntry e : l) {
            keys.add(e.key);
        }
        assertEquals(Arrays.asList("reading.epub", "irish.epub"), keys);
    }

    @Test
    public void searchMatchesAuthorIgnoringAccents() {
        List<LibraryEntry> l = LibraryModel.filter(sample(), Filter.ALL, "maire");
        assertEquals(1, l.size());
        assertEquals("irish.epub", l.get(0).key);
    }

    @Test
    public void countMatchesFilter() {
        List<LibraryEntry> all = sample();
        for (Filter f : Filter.values()) {
            assertEquals(LibraryModel.filter(all, f, "").size(), LibraryModel.count(all, f));
        }
        assertEquals(1, LibraryModel.count(all, Filter.ONLY_ON_PICCO));
    }

    @Test
    public void bookFilesAreKnownBookFormatsOnly() {
        for (String name : Arrays.asList("Dune.epub", "a.PDF", "b.mobi", "c.azw", "d.AZW3",
                "e.fb2", "f.cbz", "g.cbr", "h.djvu", "Fiction/Le Banquet.epub")) {
            assertTrue(name, LibraryModel.isBookFile(name));
        }
        for (String name : Arrays.asList("0079c1ad5a7840c9b8f93975c4617cf0.md", "notes.txt",
                "cover.jpg", "epub", ".epub", "book.epub.part", "", "noext")) {
            assertFalse(name, LibraryModel.isBookFile(name));
        }
    }
}
