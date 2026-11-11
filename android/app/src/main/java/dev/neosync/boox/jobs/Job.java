package dev.neosync.boox.jobs;

import android.content.Context;

/**
 * A long piece of work over many books (send missing books, sync all
 * positions, fix covers), run one at a time by JobRunner on a background
 * thread. A job checks Thread.interrupted() between books and, when asked to
 * stop, finishes with what it got done so far.
 */
public interface Job {

    /** Row states a job reports for the book it is on. */
    String SENT = "sent";
    String SENDING = "sending";
    String WAITING = "waiting";
    String FETCHED = "fetched";
    String SENT_PLACE = "sent_place";
    String FAILED = "failed";

    /** For the progress card, e.g. "Sending books to the Picco". */
    String title(Context c);

    /** Runs on JobRunner's thread; calls l.finished once at the end. */
    void run(Listener l) throws InterruptedException;

    interface Listener {
        /**
         * @param done       books finished so far
         * @param currentKey the book's library key (LibraryModel.keyOf)
         * @param rowState   one of the constants above, or null when the job
         *                   is on this book but its row keeps its state
         */
        void progress(int done, int total, String currentKey, String rowState);

        /**
         * The result sentence; null only when the job ended without giving
         * one (stopped before it could say how far it got).
         */
        void finished(String resultSentence);
    }
}
