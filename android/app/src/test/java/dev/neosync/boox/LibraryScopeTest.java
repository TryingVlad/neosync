package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.neosync.boox.library.LibraryScope;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class LibraryScopeTest {

    private static final String BOOKS = "/storage/emulated/0/Books";

    private static LibraryScope defaults() {
        return new LibraryScope(LibraryScope.DEFAULT_TYPES, Collections.emptySet());
    }

    @Test
    public void defaultsShowEpubOnly() {
        LibraryScope scope = defaults();
        assertTrue(scope.includes(BOOKS + "/a.epub"));
        assertFalse(scope.includes(BOOKS + "/a.pdf"));
        assertFalse(scope.includes(BOOKS + "/a.mobi"));
        assertFalse(scope.includes(BOOKS + "/a.md"));
    }

    @Test
    public void typeMatchIgnoresCase() {
        assertTrue(defaults().includes(BOOKS + "/BOOK.EPUB"));
    }

    @Test
    public void hiddenFolderHidesItsBooksAndSubfolders() {
        LibraryScope scope = new LibraryScope(LibraryScope.DEFAULT_TYPES,
                Collections.singleton(BOOKS + "/Old"));
        assertFalse(scope.includes(BOOKS + "/Old/a.epub"));
        assertFalse(scope.includes(BOOKS + "/Old/x/b.epub"));
        assertTrue(scope.includes(BOOKS + "/OldNot/c.epub"));
        assertTrue(scope.includes(BOOKS + "/d.epub"));
    }

    @Test
    public void bookFoldersAreDistinctSortedParents() {
        List<String> folders = LibraryScope.bookFolders(Arrays.asList(
                BOOKS + "/b/two.epub", BOOKS + "/a/one.pdf", BOOKS + "/b/three.epub",
                BOOKS + "/notes/n.md"));
        assertEquals(Arrays.asList(BOOKS + "/a", BOOKS + "/b"), folders);
    }

    @Test
    public void nonBookFilesNeverIncluded() {
        LibraryScope scope = new LibraryScope(new HashSet<>(Arrays.asList("epub", "md")),
                Collections.emptySet());
        assertFalse(scope.includes(BOOKS + "/note.md"));
        assertTrue(scope.includes(BOOKS + "/a.epub"));
    }
}
