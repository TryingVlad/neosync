package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import dev.neosync.boox.contentprovider.OnyxMetadataRepository.Book;
import dev.neosync.boox.kosync.LibrarySweep;

public class LibrarySweepTest {

    private static Book book(String path, String progress) {
        Book b = new Book();
        b.path = path;
        b.progress = progress;
        return b;
    }

    private static List<String> paths(List<Book> books) {
        List<String> out = new ArrayList<>();
        for (Book b : books) {
            out.add(b.path);
        }
        return out;
    }

    private final List<Book> library = Arrays.asList(
            book("/b/latest.epub", "10/100"),
            book("/b/a.epub", "1/200"),
            book("/b/b.epub", "5/50"),
            book("/b/c.epub", null),
            book("/b/d.epub", "2/20"));

    @Test
    public void skipsHandledAndUnparseableBooks() {
        List<Book> picked = LibrarySweep.pick(library, null,
                new HashSet<>(Collections.singletonList("/b/latest.epub")),
                new HashMap<>(), 1000, 100, 10);
        assertEquals(Arrays.asList("/b/a.epub", "/b/b.epub", "/b/d.epub"), paths(picked));
    }

    @Test
    public void onlyBooksOnThePiccoWhenItsListIsKnown() {
        List<Book> picked = LibrarySweep.pick(library, Arrays.asList("Fiction/b.epub", "d.epub"),
                new HashSet<>(), new HashMap<>(), 1000, 100, 10);
        assertEquals(Arrays.asList("/b/b.epub", "/b/d.epub"), paths(picked));
    }

    @Test
    public void neverCheckedFirstThenOldestAndRecentOnesWait() {
        Map<String, Long> checked = new HashMap<>();
        checked.put("/b/latest.epub", 100L);
        checked.put("/b/a.epub", 950L); // checked 50 ms ago: too recent
        checked.put("/b/b.epub", 500L);
        List<Book> picked = LibrarySweep.pick(library, null, new HashSet<>(), checked,
                1000, 100, 2);
        assertEquals(Arrays.asList("/b/d.epub", "/b/latest.epub"), paths(picked));
    }
}
