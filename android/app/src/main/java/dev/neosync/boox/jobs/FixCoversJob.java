package dev.neosync.boox.jobs;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.neosync.boox.R;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.SyncAll;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.kosync.SyncStateStore;
import dev.neosync.boox.library.LibraryModel;
import dev.neosync.boox.transfer.CoverFixer;
import dev.neosync.boox.transfer.PiccoClient;

/**
 * "Fix missing covers" after the user confirmed a CleanPlan: saves every
 * position of a book that is also on the Picco to the server first (the
 * Picco forgets its own position for a replaced book), then fixes each book
 * and replaces it on the Picco. Stops when the Picco is gone.
 *
 * Progress: "waiting" while a book's position is saved first, then
 * "sending" while its cover is added and it's replaced, then "sent" or
 * "failed".
 */
public final class FixCoversJob implements Job {

    private final Context context;
    private final SyncSettings settings;
    private final OnyxMetadataRepository repository;
    private final SyncStateStore stateStore;
    private final KoSyncClient syncClient;
    private final String deviceId;
    private final PiccoClient client;
    private final CleanPlan plan;
    private final boolean includePiccoOnly;

    public FixCoversJob(Context context, SyncSettings settings, OnyxMetadataRepository repository,
                        SyncStateStore stateStore, KoSyncClient syncClient, String deviceId,
                        PiccoClient client, CleanPlan plan, boolean includePiccoOnly) {
        this.context = context;
        this.settings = settings;
        this.repository = repository;
        this.stateStore = stateStore;
        this.syncClient = syncClient;
        this.deviceId = deviceId;
        this.client = client;
        this.plan = plan;
        this.includePiccoOnly = includePiccoOnly;
    }

    @Override
    public String title(Context c) {
        return c.getString(R.string.job_fix_covers_title);
    }

    @Override
    public void run(Listener l) {
        // 1. The Picco forgets its own position for a replaced book, so
        //    every position goes to the server before anything changes.
        List<OnyxMetadataRepository.Book> toSync = new ArrayList<>();
        for (OnyxMetadataRepository.Book book : repository.queryBooksWithProgress()) {
            if (plan.piccoPathOf.containsKey(new File(book.path).getPath())) {
                toSync.add(book);
            }
        }
        if (!toSync.isEmpty()) {
            SyncAll.Summary result = SyncAllJob.run(context, settings, repository, stateStore,
                    syncClient, deviceId, toSync, new Listener() {
                        @Override
                        public void progress(int done, int total, String key, String state) {
                            // Its cover comes after every position is saved.
                            if (state == null) {
                                l.progress(done, total, key, WAITING);
                            }
                        }

                        @Override
                        public void finished(String resultSentence) {
                        }
                    });
            if (result != null && result.stoppedReason != null) {
                l.finished(context.getString(R.string.clean_sync_failed,
                        SyncAllJob.stopReason(context, result)));
                return;
            }
            // Stopped while saving positions: SyncAll left the interrupt set,
            // so the loops below change nothing.
        }

        // 2. Fix, then replace on the Picco.
        CoverFixer fixer = new CoverFixer(context);
        int total = plan.booxBooks.size() + (includePiccoOnly ? plan.piccoOnly.size() : 0);
        int done = 0;
        int fixed = 0;
        int replaced = 0;
        int failed = 0;
        String lost = null;
        boolean stopped = false;
        for (File file : plan.booxBooks) {
            if (Thread.interrupted()) {
                stopped = true;
                break;
            }
            String key = LibraryModel.keyOf(file.getPath());
            l.progress(done, total, key, SENDING);
            done++;
            CoverFixer.Outcome outcome = fixer.fixInPlace(file);
            if (outcome == CoverFixer.Outcome.FAILED || outcome == CoverFixer.Outcome.NOT_AN_EPUB) {
                failed++;
                l.progress(done, total, key, FAILED);
                continue;
            }
            fixed++;
            String piccoPath = plan.piccoPathOf.get(file.getPath());
            if (piccoPath == null) {
                l.progress(done, total, key, SENT);
                continue;
            }
            try {
                if (client.replace(file, piccoPath).status == PiccoClient.Status.SENT) {
                    replaced++;
                    l.progress(done, total, key, SENT);
                } else {
                    failed++;
                    l.progress(done, total, key, FAILED);
                }
            } catch (IOException e) {
                lost = context.getString(R.string.clean_lost_picco, done - 1, total);
                l.progress(done - 1, total, key, FAILED);
                break;
            }
        }
        if (lost == null && !stopped && includePiccoOnly) {
            for (int i = 0; i < plan.piccoOnly.size(); i++) {
                if (Thread.interrupted()) {
                    break;
                }
                String piccoPath = plan.piccoOnly.get(i);
                String key = LibraryModel.keyOf(piccoPath);
                l.progress(done, total, key, SENDING);
                done++;
                File src = plan.downloaded.get(piccoPath);
                File fixedCopy = new File(src.getParentFile(), "fixed-" + i + ".epub");
                CoverFixer.Outcome outcome = fixer.fixCopy(src, fixedCopy);
                if (outcome != CoverFixer.Outcome.TAGGED && outcome != CoverFixer.Outcome.ADDED_PAGE) {
                    failed++;
                    l.progress(done, total, key, FAILED);
                    continue;
                }
                try {
                    if (client.replace(fixedCopy, piccoPath).status == PiccoClient.Status.SENT) {
                        fixed++;
                        replaced++;
                        l.progress(done, total, key, SENT);
                    } else {
                        failed++;
                        l.progress(done, total, key, FAILED);
                    }
                } catch (IOException e) {
                    lost = context.getString(R.string.clean_lost_picco, done - 1, total);
                    l.progress(done - 1, total, key, FAILED);
                    break;
                }
            }
        }
        CleanPlan.workDir(context);

        StringBuilder summary = new StringBuilder(context.getString(R.string.clean_done, fixed));
        if (replaced > 0) {
            summary.append(' ').append(context.getString(R.string.clean_done_picco, replaced));
        }
        if (failed > 0) {
            summary.append(' ').append(context.getString(R.string.clean_failed_part, failed));
        }
        if (lost != null) {
            summary.append("\n").append(lost);
        }
        l.finished(summary.toString());
    }
}
