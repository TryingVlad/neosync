package dev.neosync.boox.kosync;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.neosync.boox.contentprovider.OnyxMetadataRepository;

/**
 * Picks which closed books to ask the server about besides the latest one.
 *
 * Without this, a book read on another device (the Picco) only got checked
 * once it was opened here, and by then NeoReader has already loaded its old
 * position. Checking the other books in the background puts the newer
 * position in place before the book is opened.
 *
 * The server is shared and rate-limited, so each pass checks a few books,
 * the least recently checked first, and a book is not asked about again
 * for a while.
 */
public final class LibrarySweep {

    private LibrarySweep() {
    }

    /**
     * @param books        books with a position here, most recently read first
     * @param onPicco      file names (or Picco paths) on the Picco, or null when
     *                     the Picco's list is unknown (then every book is a candidate)
     * @param skip         paths already handled in this scan
     * @param lastCheckMs  when each path was last asked about
     * @param minAgeMs     do not ask about a book again sooner than this
     * @param max          books per pass
     */
    public static List<OnyxMetadataRepository.Book> pick(
            List<OnyxMetadataRepository.Book> books, Collection<String> onPicco,
            Set<String> skip, Map<String, Long> lastCheckMs, long nowMs, long minAgeMs, int max) {
        Set<String> piccoNames = null;
        if (onPicco != null) {
            piccoNames = new HashSet<>();
            for (String path : onPicco) {
                piccoNames.add(fileName(path));
            }
        }
        List<OnyxMetadataRepository.Book> candidates = new ArrayList<>();
        for (OnyxMetadataRepository.Book book : books) {
            if (book.path == null || skip.contains(book.path) || book.totalPages() <= 0) {
                continue;
            }
            if (piccoNames != null && !piccoNames.contains(fileName(book.path))) {
                continue;
            }
            Long last = lastCheckMs.get(book.path);
            if (last != null && nowMs - last < minAgeMs) {
                continue;
            }
            candidates.add(book);
        }
        // Never-checked books first, then the longest ago; the sort is
        // stable, so ties keep the most-recently-read order.
        candidates.sort((a, b) -> Long.compare(
                lastCheckMs.getOrDefault(a.path, Long.MIN_VALUE),
                lastCheckMs.getOrDefault(b.path, Long.MIN_VALUE)));
        return candidates.size() > max ? new ArrayList<>(candidates.subList(0, max)) : candidates;
    }

    static String fileName(String path) {
        return path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
    }
}
