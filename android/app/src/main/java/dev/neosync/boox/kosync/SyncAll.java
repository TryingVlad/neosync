package dev.neosync.boox.kosync;

import android.content.Context;

import java.util.List;

import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.library.RemotePosition;
import dev.neosync.boox.library.RemotePositionStore;
import dev.neosync.boox.library.SyncProblem;

/**
 * "Sync all positions": brings every given book up to date in whichever
 * direction is right, one book at a time. KoSyncClient spaces the requests
 * out for the rate-limited server; when the server still asks to slow down,
 * the run stops and reports how far it got instead of hammering it.
 */
public class SyncAll {

    public enum Action { FETCH, SEND, NOTHING }

    public interface Listener {
        /** Called before each book; index is 0-based. */
        void onBook(int index, int count, OnyxMetadataRepository.Book book);

        /** Called after each book the run didn't stop on. */
        default void onBookDone(int index, int count, OnyxMetadataRepository.Book book,
                                Outcome outcome) {
        }

        void onFinished(Summary summary);
    }

    public enum Outcome { FETCHED, SENT, UNCHANGED, FAILED }

    public static class Summary {
        public int fetched;
        public int sent;
        public int unchanged;
        public int failed;
        /** Set when the run stopped early (rate limit, or no connection at all). */
        public String stoppedReason;
        public KoSyncClient.Error stoppedError;
        public int count;
    }

    /**
     * Which way one book should go.
     *
     * The server keeps one position per book: the latest one sent by any
     * device. So a position there from another device is by definition newer
     * than this tablet's last upload, and wins unless this tablet was read
     * further since its last sync too (then the newer timestamp decides).
     *
     * @param remoteFromOther the server has a position written by another device
     * @param samePosition    remote and local positions are the same place
     * @param localChanged    read further here since the last push/pull
     *                        (or never synced) and a local position exists
     */
    public static Action decide(boolean remoteFromOther, boolean samePosition,
                                boolean localChanged, long remoteMs, long localMs) {
        if (samePosition) {
            return Action.NOTHING;
        }
        if (remoteFromOther && localChanged) {
            return remoteMs > localMs ? Action.FETCH : Action.SEND;
        }
        if (remoteFromOther) {
            return Action.FETCH;
        }
        return localChanged ? Action.SEND : Action.NOTHING;
    }

    private final Context context;
    private final SyncSettings settings;
    private final OnyxMetadataRepository repository;
    private final SyncStateStore stateStore;
    private final RemotePositionStore remoteStore;
    private final KoSyncClient client;
    private final String deviceId;

    public SyncAll(Context context, SyncSettings settings, OnyxMetadataRepository repository,
                   SyncStateStore stateStore, KoSyncClient client, String deviceId) {
        this.context = context;
        this.settings = settings;
        this.repository = repository;
        this.stateStore = stateStore;
        this.remoteStore = new RemotePositionStore(context);
        this.client = client;
        this.deviceId = deviceId;
    }

    /** Runs on the calling (background) thread. */
    public void run(List<OnyxMetadataRepository.Book> books, Listener listener) {
        Summary summary = new Summary();
        summary.count = books.size();
        for (int i = 0; i < books.size(); i++) {
            // Asked to stop (JobRunner.stop): the flag stays set for the caller.
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            OnyxMetadataRepository.Book book = books.get(i);
            listener.onBook(i, books.size(), book);
            int fetched = summary.fetched;
            int sent = summary.sent;
            int unchanged = summary.unchanged;
            KoSyncClient.Result stop = syncOne(book, summary);
            if (stop != null) {
                summary.stoppedReason = KoSyncClient.errorString(stop);
                summary.stoppedError = stop.error;
                settings.recordSyncError(summary.stoppedReason,
                        SyncProblem.classify(stop.error, SyncProblem.wifiHasInternet(context)));
                break;
            }
            listener.onBookDone(i, books.size(), book,
                    summary.fetched > fetched ? Outcome.FETCHED
                            : summary.sent > sent ? Outcome.SENT
                            : summary.unchanged > unchanged ? Outcome.UNCHANGED
                            : Outcome.FAILED);
        }
        listener.onFinished(summary);
    }

    /**
     * Returns a result only when the whole run must stop: the server asked to
     * slow down, or it can't be reached at all — every remaining book would
     * fail the same way, each after a full network timeout.
     */
    private KoSyncClient.Result syncOne(OnyxMetadataRepository.Book book, Summary summary) {
        int total = book.totalPages();
        String hash = SyncActions.documentHash(settings, book.path);
        if (hash == null) {
            summary.failed++;
            return null;
        }
        KoSyncClient.Result remote = SyncActions.getProgress(client, book.path, hash);
        if (remote.error == KoSyncClient.Error.RATE_LIMITED
                || remote.error == KoSyncClient.Error.NETWORK_ERROR) {
            return remote;
        }
        if (remote.error != KoSyncClient.Error.OK && remote.error != KoSyncClient.Error.NOT_FOUND) {
            summary.failed++;
            return null;
        }
        settings.clearSyncError();
        KoSyncClient.Progress progress =
                remote.error == KoSyncClient.Error.OK ? remote.progress : null;
        if (progress != null) {
            remoteStore.recordFromOthers(hash, null, RemotePosition.from(progress), deviceId);
        }

        SyncStateStore.BookState state = stateStore.get(book.path);
        boolean localChanged = book.currentPage() > 0 && total > 0
                && !equalsSafe(book.progress, state.lastPushedProgress)
                && !equalsSafe(book.progress, state.lastWrittenProgress);
        boolean remoteFromOther = progress != null && total > 0
                && !deviceId.equals(progress.deviceId);
        double localPct = SyncActions.localPercentage(book);
        boolean samePosition = progress != null && total > 0
                && Math.abs(progress.percentage - localPct) < 0.5 / total;
        long remoteMs = progress != null ? progress.timestampSec * 1000L : 0;

        switch (decide(remoteFromOther, samePosition, localChanged, remoteMs, book.lastAccessMs)) {
            case FETCH:
                if (SyncActions.applyRemote(context, repository, stateStore, book, progress) != null) {
                    summary.fetched++;
                    settings.recordSyncSuccess(book.name != null ? book.name : book.path, book.path,
                            progress.percentage);
                } else {
                    summary.failed++;
                }
                return null;
            case SEND:
                KoSyncClient.Result sent = SyncActions.uploadLocal(client, stateStore, book, hash);
                if (sent.error == KoSyncClient.Error.RATE_LIMITED
                        || sent.error == KoSyncClient.Error.NETWORK_ERROR) {
                    return sent;
                }
                if (sent.error == KoSyncClient.Error.OK) {
                    summary.sent++;
                    settings.recordSyncSuccess(book.name != null ? book.name : book.path, book.path, localPct);
                } else {
                    summary.failed++;
                }
                return null;
            default:
                summary.unchanged++;
                return null;
        }
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
