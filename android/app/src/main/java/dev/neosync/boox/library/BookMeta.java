package dev.neosync.boox.library;

/** What the library list shows about a book. */
public final class BookMeta {
    public final String title;
    /** May be empty. */
    public final String author;
    /** A cover picture is available for a thumbnail. */
    public final boolean hasCover;
    /** The Picco would draw a black tile for this book. */
    public final boolean wouldShowBlack;

    public BookMeta(String title, String author, boolean hasCover, boolean wouldShowBlack) {
        this.title = title;
        this.author = author;
        this.hasCover = hasCover;
        this.wouldShowBlack = wouldShowBlack;
    }
}
