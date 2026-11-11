package dev.neosync.boox.library;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which of the Boox's books the home list shows (spec 5.8): the listed file
 * types, minus the folders the user unticked. It only narrows the list; sync,
 * sending and cover fixing don't use it.
 */
public final class LibraryScope {

    /**
     * The types listed: EPUB only. The Picco opens EPUB, TXT, XTC and BMP; of
     * those Neo Reader opens EPUB and TXT, and NeoSync syncs and sends EPUB.
     */
    public static final Set<String> LISTED_TYPES =
            Collections.unmodifiableSet(new HashSet<>(Collections.singletonList("epub")));

    private final Set<String> types;
    private final Set<String> hiddenFolders;

    public LibraryScope(Set<String> types, Set<String> hiddenFolders) {
        this.types = new HashSet<>(types);
        this.hiddenFolders = new HashSet<>(hiddenFolders);
    }

    /** True for a book file of a chosen type that isn't inside a hidden folder. */
    public boolean includes(String path) {
        if (!LibraryModel.isBookFile(path)) {
            return false;
        }
        if (!types.contains(typeOf(path))) {
            return false;
        }
        String parent = parentOf(path);
        for (String hidden : hiddenFolders) {
            if (parent.equals(hidden) || parent.startsWith(hidden + "/")) {
                return false;
            }
        }
        return true;
    }

    /** The distinct folders holding book files, sorted. */
    public static List<String> bookFolders(Collection<String> paths) {
        Set<String> folders = new TreeSet<>();
        for (String path : paths) {
            if (LibraryModel.isBookFile(path)) {
                folders.add(parentOf(path));
            }
        }
        return new ArrayList<>(folders);
    }

    /** The distinct folders holding book files of the given types, sorted. */
    public static List<String> bookFolders(Collection<String> paths, Set<String> types) {
        List<String> ofTypes = new ArrayList<>();
        for (String path : paths) {
            if (types.contains(typeOf(path))) {
                ofTypes.add(path);
            }
        }
        return bookFolders(ofTypes);
    }

    /** The file's extension, lower case. */
    private static String typeOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "";
    }
}
