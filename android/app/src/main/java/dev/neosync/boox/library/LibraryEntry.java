package dev.neosync.boox.library;

import dev.neosync.boox.transfer.BookSyncStatus;

/** One book in the library list, merged across the Boox and the Picco. */
public final class LibraryEntry {
    /** File name, NFC-normalised. */
    public String key;
    /** Null when the book is not on this tablet. */
    public String booxPath;
    /** Null when the book is not on the Picco. */
    public String piccoPath;
    public boolean isEpub;
    /** -1 when unknown. */
    public double booxPct = -1;
    /** -1 when unknown. */
    public double piccoPct = -1;
    public long booxLastMs;
    /** May be null. */
    public RemotePosition remote;
    public BookSyncStatus syncStatus = BookSyncStatus.NOT_HERE;
    public RowStatus status;
    public long activityMs;
    public String title = "";
    public String author = "";
}
