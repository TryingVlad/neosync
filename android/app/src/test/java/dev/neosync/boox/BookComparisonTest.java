package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import dev.neosync.boox.library.BookComparison;
import dev.neosync.boox.library.BookComparison.Action;
import dev.neosync.boox.library.BookComparison.Verdict;
import dev.neosync.boox.library.LibraryEntry;
import dev.neosync.boox.library.RemotePosition;

import org.junit.Test;

public class BookComparisonTest {
    private static LibraryEntry onBoox(double pct, long last, RemotePosition remote) {
        LibraryEntry e = new LibraryEntry();
        e.booxPath = "/Books/Dune.epub";
        e.piccoPath = "books/Dune.epub";
        e.isEpub = true;
        e.booxPct = pct;
        e.booxLastMs = last;
        e.remote = remote;
        return e;
    }

    private static RemotePosition remote(double pct, long ts) {
        return new RemotePosition("doc", pct, "Picco", "id", ts);
    }

    private static void check(BookComparison c, Verdict v, Action p, Action s) {
        assertEquals(v, c.verdict);
        assertEquals(p, c.primary);
        assertEquals(s, c.secondary);
    }

    @Test
    public void piccoOnlyHasNoActions() {
        LibraryEntry e = onBoox(0.5, 10, remote(0.9, 20));
        e.booxPath = null;
        check(BookComparison.of(e, true, true), Verdict.NOT_CHECKED, Action.NONE, Action.NONE);
    }

    @Test
    public void missingFileOffersSend() {
        LibraryEntry e = onBoox(0.5, 10, remote(0.9, 20));
        e.piccoPath = null;
        check(BookComparison.of(e, true, true),
                Verdict.OTHER_MORE_RECENT, Action.SEND_FILE, Action.NONE);
        e.isEpub = false;
        check(BookComparison.of(e, true, true),
                Verdict.OTHER_MORE_RECENT, Action.JUMP_TO_REMOTE, Action.SEND_PLACE);
        e.isEpub = true;
        check(BookComparison.of(e, true, false),
                Verdict.OTHER_MORE_RECENT, Action.JUMP_TO_REMOTE, Action.SEND_PLACE);
        e.piccoPath = null;
        check(BookComparison.of(e, false, true), Verdict.NOT_CHECKED, Action.SEND_FILE, Action.NONE);
        e.remote = null;
        check(BookComparison.of(e, true, true), Verdict.NOTHING_ONLINE, Action.SEND_FILE, Action.NONE);
    }

    @Test
    public void failedLiveCheckOffersNoAction() {
        check(BookComparison.of(onBoox(0.5, 10, remote(0.9, 20)), false, true),
                Verdict.NOT_CHECKED, Action.NONE, Action.NONE);
    }

    @Test
    public void nothingOnlineOffersSendPlace() {
        check(BookComparison.of(onBoox(0.5, 10, null), true, true),
                Verdict.NOTHING_ONLINE, Action.SEND_PLACE, Action.NONE);
    }

    @Test
    public void samePlaceWithinHalfPercent() {
        check(BookComparison.of(onBoox(0.500, 10, remote(0.504, 20)), true, true),
                Verdict.SAME, Action.NONE, Action.NONE);
        check(BookComparison.of(onBoox(0.500, 10, remote(0.506, 20)), true, true),
                Verdict.OTHER_MORE_RECENT, Action.JUMP_TO_REMOTE, Action.SEND_PLACE);
    }

    @Test
    public void remoteNewerOffersJump() {
        check(BookComparison.of(onBoox(0.2, 10, remote(0.6, 20)), true, true),
                Verdict.OTHER_MORE_RECENT, Action.JUMP_TO_REMOTE, Action.SEND_PLACE);
    }

    @Test
    public void hereNewerOffersSend() {
        check(BookComparison.of(onBoox(0.6, 30, remote(0.2, 20)), true, true),
                Verdict.HERE_MORE_RECENT, Action.SEND_PLACE, Action.JUMP_TO_REMOTE);
    }

    private static RemotePosition own(double pct, long ts) {
        return new RemotePosition("doc", pct, "NeoSync", "boox", ts);
    }

    @Test
    public void ownRecordAtThisPlaceWithNoOtherIsSamePlace() {
        check(BookComparison.of(onBoox(0.5, 10, null), true, true, own(0.503, 11)),
                Verdict.SAME, Action.NONE, Action.NONE);
    }

    @Test
    public void ownRecordAtThisPlaceDropsSendPlace() {
        // Read further here and already sent: no "Send this place", jump stays.
        check(BookComparison.of(onBoox(0.6, 30, remote(0.2, 20)), true, true, own(0.6, 31)),
                Verdict.HERE_MORE_RECENT, Action.NONE, Action.JUMP_TO_REMOTE);
        check(BookComparison.of(onBoox(0.2, 10, remote(0.6, 20)), true, true, own(0.2, 25)),
                Verdict.OTHER_MORE_RECENT, Action.JUMP_TO_REMOTE, Action.NONE);
    }

    @Test
    public void ownRecordElsewhereStillOffersSendPlace() {
        check(BookComparison.of(onBoox(0.6, 30, remote(0.2, 20)), true, true, own(0.4, 25)),
                Verdict.HERE_MORE_RECENT, Action.SEND_PLACE, Action.JUMP_TO_REMOTE);
        check(BookComparison.of(onBoox(0.5, 10, null), true, true, own(0.3, 11)),
                Verdict.NOTHING_ONLINE, Action.SEND_PLACE, Action.NONE);
    }

    @Test
    public void ownRecordIgnoredWhenCheckFailedOrNoPlaceHere() {
        check(BookComparison.of(onBoox(0.5, 10, null), false, true, own(0.5, 11)),
                Verdict.NOT_CHECKED, Action.NONE, Action.NONE);
        check(BookComparison.of(onBoox(-1, 0, null), true, true, own(0.0, 11)),
                Verdict.NOTHING_ONLINE, Action.SEND_PLACE, Action.NONE);
    }

    @Test
    public void missingFileStillOffersSendFileWithOwnRecord() {
        LibraryEntry e = onBoox(0.5, 10, null);
        e.piccoPath = null;
        check(BookComparison.of(e, true, true, own(0.5, 11)),
                Verdict.SAME, Action.SEND_FILE, Action.NONE);
    }
}
