package dev.neosync.boox.library;

/** Page arithmetic for the paged library list. */
public final class LibraryPager {
    private final int total;
    private final int pageSize;

    public LibraryPager(int total, int pageSize) {
        this.total = Math.max(0, total);
        this.pageSize = Math.max(1, pageSize);
    }

    /** At least 1, so an empty list still has a page to show. */
    public int pageCount() {
        return Math.max(1, (total + pageSize - 1) / pageSize);
    }

    public int clamp(int page) {
        return Math.max(0, Math.min(page, pageCount() - 1));
    }

    public int from(int page) {
        return Math.min(total, clamp(page) * pageSize);
    }

    /** Exclusive. */
    public int to(int page) {
        return Math.min(total, from(page) + pageSize);
    }
}
