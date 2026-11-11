package dev.neosync.boox.jobs;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import dev.neosync.boox.R;
import dev.neosync.boox.library.LibraryModel;
import dev.neosync.boox.transfer.CoverFixer;
import dev.neosync.boox.transfer.PiccoClient;
import dev.neosync.boox.transfer.PiccoLibraryStore;

/**
 * Copies books from this Boox to the Picco's folder, giving each a cover the
 * Picco can show first. Stops when the Picco is gone (File Transfer closed,
 * Wi-Fi dropped): the remaining books would all fail the same way.
 *
 * oneBook() is the single-book send: it first checks whether the Picco
 * already has the file and answers with the single-book sentences.
 */
public final class SendMissingJob implements Job {

    private static final String TAG = "SendMissingJob";

    private final Context context;
    private final PiccoClient client;
    private final String address;
    private final String folder;
    private final List<File> files;
    private final PiccoLibraryStore piccoLibrary;
    /** Non-null for oneBook(): the message when the Picco can't be talked to. */
    private final Function<Exception, String> errorMessage;
    private volatile boolean delivered;

    /** Sends every file, in order (the list "Send missing books" confirmed). */
    public SendMissingJob(Context context, PiccoClient client, String address, String folder,
                          List<File> files, PiccoLibraryStore piccoLibrary) {
        this(context, client, address, folder, files, piccoLibrary, null);
    }

    private SendMissingJob(Context context, PiccoClient client, String address, String folder,
                           List<File> files, PiccoLibraryStore piccoLibrary,
                           Function<Exception, String> errorMessage) {
        this.context = context;
        this.client = client;
        this.address = address;
        this.folder = folder;
        this.files = files;
        this.piccoLibrary = piccoLibrary;
        this.errorMessage = errorMessage;
    }

    /**
     * Sends one book unless the Picco has it already. Reports "waiting" while
     * a cover is added and "sending" during the upload.
     *
     * @param errorMessage the sentence for a failure talking to the Picco
     */
    public static SendMissingJob oneBook(Context context, PiccoClient client, String address,
                                         String folder, File file,
                                         PiccoLibraryStore piccoLibrary,
                                         Function<Exception, String> errorMessage) {
        return new SendMissingJob(context, client, address, folder,
                Collections.singletonList(file), piccoLibrary, errorMessage);
    }

    /** After finished: true when the book (oneBook) or every book is on the Picco. */
    public boolean delivered() {
        return delivered;
    }

    @Override
    public String title(Context c) {
        return c.getString(R.string.job_send_missing_title);
    }

    @Override
    public void run(Listener l) {
        if (errorMessage != null) {
            runOne(l);
        } else {
            runAll(l);
        }
    }

    private void runAll(Listener l) {
        int sent = 0;
        String failure = null;
        for (int i = 0; i < files.size(); i++) {
            if (Thread.interrupted()) {
                break;
            }
            File file = files.get(i);
            String key = LibraryModel.keyOf(file.getPath());
            l.progress(i, files.size(), key, SENDING);
            if (CoverFixer.needsCover(file)) {
                new CoverFixer(context).fixInPlace(file);
            }
            try {
                PiccoClient.UploadResult result = client.upload(file, folder);
                if (result.status != PiccoClient.Status.SERVER_ERROR) {
                    sent++;
                    piccoLibrary.addUploaded(address, file.getName());
                    l.progress(i + 1, files.size(), key, SENT);
                } else {
                    l.progress(i + 1, files.size(), key, FAILED);
                }
            } catch (IOException e) {
                Log.w(TAG, "Bulk upload stopped: " + e);
                failure = context.getString(R.string.send_missing_lost, i, files.size());
                l.progress(i, files.size(), key, FAILED);
                break;
            }
        }
        delivered = failure == null && sent == files.size();
        l.finished(failure != null ? failure
                : context.getString(R.string.send_missing_done, sent, files.size()));
    }

    private void runOne(Listener l) {
        File file = files.get(0);
        String key = LibraryModel.keyOf(file.getPath());
        if (!file.canRead()) {
            l.finished(context.getString(R.string.transfer_no_file));
            return;
        }
        try {
            if (client.hasBook(file.getName())) {
                delivered = true;
                l.finished(context.getString(R.string.transfer_already));
                return;
            }
            if (CoverFixer.needsCover(file)) {
                l.progress(0, 1, key, WAITING);
                new CoverFixer(context).fixInPlace(file);
            }
            l.progress(0, 1, key, SENDING);
            PiccoClient.UploadResult result = client.upload(file, folder);
            final int message;
            switch (result.status) {
                case SENT:
                    message = R.string.transfer_sent;
                    break;
                case ALREADY_THERE:
                    message = R.string.transfer_already;
                    break;
                default:
                    l.progress(1, 1, key, FAILED);
                    l.finished(context.getString(R.string.transfer_server_error, result.httpCode));
                    return;
            }
            piccoLibrary.addUploaded(address, file.getName());
            delivered = true;
            l.progress(1, 1, key, SENT);
            l.finished(context.getString(message));
        } catch (IOException e) {
            Log.w(TAG, "Picco transfer failed: " + e);
            l.progress(0, 1, key, FAILED);
            l.finished(errorMessage.apply(e));
        } catch (Exception e) {
            // Not a connection problem: the Picco answered, something else broke.
            Log.e(TAG, "Picco transfer error", e);
            l.progress(0, 1, key, FAILED);
            l.finished(errorMessage.apply(e));
        }
    }
}
