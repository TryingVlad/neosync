package dev.neosync.boox.jobs;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import dev.neosync.boox.R;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.SyncAll;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.kosync.SyncStateStore;
import dev.neosync.boox.library.LibraryModel;
import dev.neosync.boox.library.SyncProblem;
import dev.neosync.boox.transfer.PiccoLibraryStore;

/**
 * "Sync all positions": brings the position of every book that is both on
 * this Boox and on the Picco up to date, newest position winning (SyncAll).
 * Without a saved Picco list, every book opened on this Boox is synced. A
 * rate limit or a lost connection stops the run (SyncAll decides).
 */
public final class SyncAllJob implements Job {

    private final Context context;
    private final SyncSettings settings;
    private final OnyxMetadataRepository repository;
    private final SyncStateStore stateStore;
    private final KoSyncClient client;
    private final String deviceId;
    private final PiccoLibraryStore piccoLibrary;
    private final String address;

    public SyncAllJob(Context context, SyncSettings settings, OnyxMetadataRepository repository,
                      SyncStateStore stateStore, KoSyncClient client, String deviceId,
                      PiccoLibraryStore piccoLibrary, String address) {
        this.context = context;
        this.settings = settings;
        this.repository = repository;
        this.stateStore = stateStore;
        this.client = client;
        this.deviceId = deviceId;
        this.piccoLibrary = piccoLibrary;
        this.address = address;
    }

    @Override
    public String title(Context c) {
        return c.getString(R.string.job_sync_all_title);
    }

    @Override
    public void run(Listener l) {
        List<OnyxMetadataRepository.Book> books = repository.queryBooksWithProgress();
        if (piccoLibrary.hasList(address)) {
            List<String> onPicco = piccoLibrary.getBooks(address);
            java.util.Set<String> piccoNames = new java.util.HashSet<>();
            for (String path : onPicco) {
                piccoNames.add(fileName(path));
            }
            List<OnyxMetadataRepository.Book> both = new ArrayList<>();
            for (OnyxMetadataRepository.Book book : books) {
                if (piccoNames.contains(fileName(book.path))) {
                    both.add(book);
                }
            }
            books = both;
        }
        if (books.isEmpty()) {
            l.finished(context.getString(R.string.sync_all_none));
            return;
        }
        SyncAll.Summary summary = run(context, settings, repository, stateStore, client,
                deviceId, books, l);
        boolean stopped = Thread.interrupted();
        int done = summary.fetched + summary.sent + summary.unchanged + summary.failed;
        String text;
        if (summary.stoppedReason != null) {
            String reason = stopReason(context, summary);
            text = done == 0 ? reason : context.getString(
                    R.string.sync_all_stopped, done, summary.count, reason);
        } else if (stopped && done < summary.count) {
            text = context.getString(R.string.sync_all_stopped, done, summary.count, "").trim();
        } else {
            text = context.getString(R.string.sync_all_done, summary.fetched,
                    summary.sent, summary.unchanged);
            if (summary.failed > 0) {
                text += " " + context.getString(R.string.sync_all_failed_part, summary.failed);
            }
        }
        l.finished(text);
    }

    /**
     * Runs SyncAll over books on this thread, reporting each book to l
     * (null while on it, then fetched / sent_place / failed; nothing when unchanged). Also used by
     * FixCoversJob to save positions first. Leaves an interrupt set.
     */
    static SyncAll.Summary run(Context context, SyncSettings settings,
                               OnyxMetadataRepository repository, SyncStateStore stateStore,
                               KoSyncClient client, String deviceId,
                               List<OnyxMetadataRepository.Book> books, Listener l) {
        SyncAll.Summary[] result = new SyncAll.Summary[1];
        new SyncAll(context, settings, repository, stateStore, client, deviceId)
                .run(books, new SyncAll.Listener() {
                    @Override
                    public void onBook(int index, int count, OnyxMetadataRepository.Book book) {
                        l.progress(index, count, LibraryModel.keyOf(book.path), null);
                    }

                    @Override
                    public void onBookDone(int index, int count, OnyxMetadataRepository.Book book,
                                           SyncAll.Outcome outcome) {
                        if (outcome == SyncAll.Outcome.UNCHANGED) {
                            return; // the row already shows the right place
                        }
                        String state = outcome == SyncAll.Outcome.FETCHED ? FETCHED
                                : outcome == SyncAll.Outcome.SENT ? SENT_PLACE
                                : FAILED;
                        l.progress(index + 1, count, LibraryModel.keyOf(book.path), state);
                    }

                    @Override
                    public void onFinished(SyncAll.Summary summary) {
                        result[0] = summary;
                    }
                });
        return result[0];
    }

    private static String fileName(String path) {
        return path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
    }

    /** Why a "Sync all" run stopped, in words for the user. */
    static String stopReason(Context context, SyncAll.Summary summary) {
        return summary.stoppedError == KoSyncClient.Error.NETWORK_ERROR
                ? context.getString(SyncProblem.wifiHasInternet(context)
                        ? R.string.sync_all_server_unreachable : R.string.sync_all_no_internet)
                : summary.stoppedReason;
    }
}
