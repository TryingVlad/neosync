package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.net.Uri;
import android.os.StrictMode;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.util.Locale;
import java.util.function.Supplier;

import dev.neosync.boox.R;
import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.epub.EpubCover;
import dev.neosync.boox.jobs.CleanPlan;
import dev.neosync.boox.jobs.Job;
import dev.neosync.boox.jobs.SendMissingJob;
import dev.neosync.boox.kosync.KoReaderDocumentHash;
import dev.neosync.boox.kosync.KoSyncClient;
import dev.neosync.boox.kosync.SyncActions;
import dev.neosync.boox.kosync.SyncSettings;
import dev.neosync.boox.library.BookComparison;
import dev.neosync.boox.library.BookMeta;
import dev.neosync.boox.library.LibraryEntry;
import dev.neosync.boox.library.RemotePosition;
import dev.neosync.boox.library.UndoRule;
import dev.neosync.boox.library.UndoStore;
import dev.neosync.boox.transfer.CoverFixer;
import dev.neosync.boox.transfer.PiccoClient;

/**
 * The sheet for one book (spec 5.2): both positions after one live check,
 * the one action that fits, rarer actions as links, and results inline.
 * Network and provider work runs off the UI thread.
 */
final class BookSheet {

    private final MainActivity a;
    private final Context app;
    /** The sheet's own copy: the live check and actions update it, not the list's entry. */
    private final LibraryEntry e;
    private final BottomSheetDialog dialog;
    private final BookMeta meta;
    private final Bitmap thumb;
    /** Worked out by the live check; null when the book can't be hashed. */
    private volatile String hash;

    private final ProgressBar barBoox;
    private final TextView pctBoox;
    private final TextView whenBoox;
    private final View rowBoox;
    private final TextView labelOther;
    private final ProgressBar barOther;
    private final TextView pctOther;
    private final TextView whenOther;
    private final TextView verdict;
    private final Button primary;
    private final TextView hint;
    private final TextView result;
    private final TextView linkInstead;
    private final TextView linkUndo;
    private final TextView linkSendAgain;
    private final TextView linkCover;
    private final TextView linkOpen;

    private boolean checking;
    private boolean liveOk;
    /** The server's record when this Boox wrote it (from the live check), else null. */
    private RemotePosition ownOnServer;
    /** The Onyx row's "page/total" now (for the undo rule); null for Picco-only books. */
    private String booxProgress;
    /** An action (jump, send, undo, cover check) is in flight. */
    private boolean working;
    /** The place was sent from here: don't offer to send it again. */
    private boolean placeSent;
    /** The single-book send this sheet started is running. */
    private boolean sendingFile;
    private SendMissingJob sendJob;
    /** Positions or the Picco's list changed: home reloads when the sheet closes. */
    private boolean changed;

    static void show(MainActivity a, LibraryEntry entry) {
        new BookSheet(a, entry).open();
    }

    private BookSheet(MainActivity a, LibraryEntry entry) {
        this.a = a;
        this.app = a.getApplicationContext();
        this.e = copy(entry);
        this.meta = a.readableMeta(entry);
        this.thumb = entry.booxPath != null ? a.thumbOf(entry.booxPath) : null;

        View v = LayoutInflater.from(a).inflate(R.layout.sheet_book, null, false);
        dialog = dialog(a, v);
        rowBoox = v.findViewById(R.id.sheet_row_boox);
        barBoox = v.findViewById(R.id.sheet_bar_boox);
        pctBoox = v.findViewById(R.id.sheet_pct_boox);
        whenBoox = v.findViewById(R.id.sheet_when_boox);
        labelOther = v.findViewById(R.id.sheet_label_other);
        barOther = v.findViewById(R.id.sheet_bar_other);
        pctOther = v.findViewById(R.id.sheet_pct_other);
        whenOther = v.findViewById(R.id.sheet_when_other);
        verdict = v.findViewById(R.id.sheet_verdict);
        primary = v.findViewById(R.id.sheet_primary);
        hint = v.findViewById(R.id.sheet_hint);
        result = v.findViewById(R.id.sheet_result);
        linkInstead = link(v, R.id.sheet_link_instead);
        linkUndo = link(v, R.id.sheet_link_undo);
        linkSendAgain = link(v, R.id.sheet_link_send_again);
        linkCover = link(v, R.id.sheet_link_cover);
        linkOpen = link(v, R.id.sheet_link_open);

        bindHeader(v);
        linkUndo.setOnClickListener(x -> undo());
        // "Again" replaces the Picco's copy (R22); the primary Send skips one already there.
        linkSendAgain.setOnClickListener(x -> sendFile(true));
        linkCover.setOnClickListener(x -> addCover());
        linkOpen.setOnClickListener(x -> openInReader());
    }

    /**
     * A bottom sheet the way e-ink wants it: open fully at once, no slide-in,
     * no dragging, a white scrim (so nothing under it reads as a second black
     * button). Content is inflated with the activity's own theme.
     */
    static BottomSheetDialog dialog(Activity a, View content) {
        BottomSheetDialog d = new BottomSheetDialog(a, R.style.NeoSync_BottomSheet);
        d.setContentView(content);
        BottomSheetBehavior<?> behavior = d.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        behavior.setDraggable(false);
        // Full width like the mockup (Material caps modal sheets at 640dp).
        behavior.setMaxWidth(a.getResources().getDisplayMetrics().widthPixels);
        Window w = d.getWindow();
        if (w != null) {
            w.setWindowAnimations(0);
        }
        View scrim = d.findViewById(com.google.android.material.R.id.touch_outside);
        if (scrim != null) {
            scrim.setBackgroundColor(a.getColor(R.color.scrim));
        }
        return d;
    }

    private void open() {
        a.bookSheetOpened(this, dialog);
        dialog.setOnDismissListener(d -> {
            a.bookSheetClosed(this, dialog);
            if (changed) {
                a.reload();
            }
        });
        // Binary matching can't hash a book that's only on the Picco: no check.
        checking = e.booxPath != null || (e.piccoPath != null
                && SyncSettings.MATCH_FILENAME.equals(a.settings.getMatchMethod()));
        render();
        dialog.show();
        if (checking) {
            liveCheck();
        }
    }

    // --- Header and live check ---------------------------------------------------

    private void bindHeader(View v) {
        ((TextView) v.findViewById(R.id.sheet_title)).setText(e.title);
        TextView author = v.findViewById(R.id.sheet_author);
        author.setText(e.author);
        author.setVisibility(e.author.isEmpty() ? View.GONE : View.VISIBLE);
        String where = a.getString(e.booxPath == null ? R.string.sheet_only_picco
                : e.piccoPath == null ? R.string.sheet_only_here : R.string.sheet_on_both);
        String path = e.booxPath != null ? e.booxPath : e.piccoPath;
        String type = fileType(path);
        File file = e.booxPath != null ? new File(e.booxPath) : null;
        ((TextView) v.findViewById(R.id.sheet_where)).setText(file != null && file.length() > 0
                ? a.getString(R.string.sheet_sub_line, where, type,
                        Formatter.formatShortFileSize(a, file.length()))
                : a.getString(R.string.sheet_sub_line_no_size, where, type));
        View cover = v.findViewById(R.id.sheet_cover);
        TextView coverText = v.findViewById(R.id.sheet_cover_text);
        ImageView coverImage = v.findViewById(R.id.sheet_cover_image);
        if (e.isEpub && meta != null && meta.wouldShowBlack) {
            cover.setBackgroundColor(a.getColor(R.color.black)); // how the Picco shows it
        } else if (thumb != null) {
            coverImage.setImageBitmap(thumb);
            coverImage.setVisibility(View.VISIBLE);
        } else {
            coverText.setText(e.title);
        }
        rowBoox.setVisibility(e.booxPath != null ? View.VISIBLE : View.GONE);
    }

    /** One position request for this book; records what another device saved. */
    private void liveCheck() {
        final String path = e.booxPath != null ? e.booxPath : e.piccoPath;
        new Thread(() -> {
            hash = e.booxPath != null ? a.documentHash(e.booxPath, new File(e.booxPath))
                    : KoReaderDocumentHash.fromFilename(e.piccoPath);
            KoSyncClient.Result r = hash != null
                    ? SyncActions.getProgress(a.buildClient(), path, hash) : null;
            boolean ok = r != null && (r.error == KoSyncClient.Error.OK
                    || r.error == KoSyncClient.Error.NOT_FOUND);
            RemotePosition live = null;
            RemotePosition own = null;
            if (r != null && r.error == KoSyncClient.Error.OK && r.progress != null) {
                live = RemotePosition.from(r.progress);
                // Under every spelling the list may key this book by.
                a.remoteStore.recordFromOthers(hash, SyncActions.alternateHashes(path, hash),
                        live, a.deviceId());
                if (live.isFromDevice(a.deviceId())) {
                    // The server holds this Boox's own place: the box keeps
                    // the other device's last known one, like the list does,
                    // and BookComparison is told the place is already sent.
                    own = live;
                    live = null;
                }
            }
            final boolean nothingOnline = r != null && r.error == KoSyncClient.Error.NOT_FOUND;
            final RemotePosition other = live;
            final RemotePosition ownRecord = own;
            final BooxState boox = readBoox();
            a.runOnUiThread(() -> {
                checking = false;
                liveOk = ok;
                ownOnServer = ownRecord;
                if (other != null) {
                    e.remote = other;
                } else if (nothingOnline) {
                    e.remote = null; // a stored record is stale: the server has none
                }
                apply(boox);
                render();
            });
        }, "NeoSync book sheet").start();
    }

    /** The Boox row as it is now (off the UI thread). */
    private static final class BooxState {
        double pct = -1;
        long lastMs;
        String progress;
    }

    private BooxState readBoox() {
        if (e.booxPath == null) {
            return null;
        }
        OnyxMetadataRepository.Book book = a.repository.queryBook(e.booxPath);
        if (book == null) {
            return null;
        }
        BooxState s = new BooxState();
        s.pct = book.progress != null && book.totalPages() > 0
                ? SyncActions.localPercentage(book) : -1;
        s.lastMs = book.lastAccessMs;
        s.progress = book.progress;
        return s;
    }

    private void apply(BooxState s) {
        if (s != null) {
            e.booxPct = s.pct;
            e.booxLastMs = s.lastMs;
            booxProgress = s.progress;
        }
    }

    // --- Rendering -------------------------------------------------------------

    private void render() {
        bindBar(barBoox, pctBoox, whenBoox, e.booxPct, e.booxLastMs);
        RemotePosition r = e.remote;
        labelOther.setText(r == null || r.isCrossPoint() ? a.getString(R.string.sheet_picco)
                : r.device);
        bindBar(barOther, pctOther, whenOther, r != null ? r.percentage : -1,
                r != null ? r.timestampMs : 0);

        BookComparison c = BookComparison.of(e, liveOk, a.hasPiccoList(), ownOnServer);
        if (checking) {
            verdict.setText(R.string.sheet_checking);
            verdict.setVisibility(View.VISIBLE);
        } else if (e.booxPath == null) {
            verdict.setVisibility(View.GONE); // only the Picco bar, nothing to compare
        } else {
            verdict.setText(verdictText(c.verdict));
            verdict.setVisibility(View.VISIBLE);
        }

        BookComparison.Action main = checking && c.primary != BookComparison.Action.SEND_FILE
                ? BookComparison.Action.NONE : c.primary;
        BookComparison.Action instead = checking ? BookComparison.Action.NONE : c.secondary;
        if (placeSent || e.booxPct < 0) {
            // Sent already, or no place here yet (never opened in Neo Reader).
            main = main == BookComparison.Action.SEND_PLACE ? BookComparison.Action.NONE : main;
            instead = instead == BookComparison.Action.SEND_PLACE
                    ? BookComparison.Action.NONE : instead;
        }
        boolean enabled = !working && !sendingFile;
        String pctBooxText = pct(e.booxPct);
        String pctRemoteText = r != null ? pct(r.percentage) : "";
        switch (main) {
            case JUMP_TO_REMOTE:
                primary.setText(a.getString(R.string.sheet_jump, labelOther.getText(),
                        pctRemoteText));
                primary.setOnClickListener(x -> jump());
                break;
            case SEND_PLACE:
                primary.setText(a.getString(R.string.sheet_send_place, pctBooxText));
                primary.setOnClickListener(x -> sendPlace());
                break;
            case SEND_FILE:
                primary.setText(R.string.sheet_send_file);
                primary.setOnClickListener(x -> sendFile(false));
                break;
            default:
                break;
        }
        primary.setVisibility(main == BookComparison.Action.NONE ? View.GONE : View.VISIBLE);
        primary.setEnabled(enabled);
        hint.setVisibility(main == BookComparison.Action.JUMP_TO_REMOTE ? View.VISIBLE : View.GONE);

        if (instead == BookComparison.Action.SEND_PLACE && main == BookComparison.Action.NONE) {
            // No black button (the other device is further along): the send
            // is a plain link that names the percentage.
            linkInstead.setText(a.getString(R.string.sheet_send_place, pctBooxText));
            linkInstead.setOnClickListener(x -> sendPlace());
        } else if (instead == BookComparison.Action.SEND_PLACE) {
            linkInstead.setText(R.string.sheet_keep_boox);
            linkInstead.setOnClickListener(x -> sendPlace());
        } else if (instead == BookComparison.Action.JUMP_TO_REMOTE) {
            linkInstead.setText(a.getString(R.string.sheet_use_picco, labelOther.getText()));
            linkInstead.setOnClickListener(x -> jump());
        }
        showLink(linkInstead, instead != BookComparison.Action.NONE, enabled);
        showLink(linkUndo, undoAvailable(), enabled);
        boolean local = e.booxPath != null;
        showLink(linkSendAgain, local && e.isEpub && e.piccoPath != null
                && a.piccoConfigured(), enabled);
        showLink(linkCover, local && e.isEpub && meta != null && meta.wouldShowBlack, enabled);
        showLink(linkOpen, local, true);
    }

    private String verdictText(BookComparison.Verdict v) {
        switch (v) {
            case SAME:
                return a.getString(R.string.sheet_same);
            case OTHER_MORE_RECENT:
                return a.getString(R.string.sheet_other_recent, labelOther.getText());
            case HERE_MORE_RECENT:
                return a.getString(R.string.sheet_here_recent);
            case NOTHING_ONLINE:
                return a.getString(R.string.sheet_nothing_online);
            default:
                return a.getString(R.string.sheet_not_checked);
        }
    }

    private boolean undoAvailable() {
        if (e.booxPath == null || checking) {
            return false;
        }
        UndoStore.Saved saved = new UndoStore(app).get(e.booxPath);
        if (saved == null) {
            return false;
        }
        long remoteMs = e.remote != null ? e.remote.timestampMs : 0;
        return UndoRule.available(saved.savedAtMs, saved.writtenProgress, booxProgress,
                saved.remoteMs, remoteMs, System.currentTimeMillis());
    }

    private void bindBar(ProgressBar bar, TextView pctView, TextView whenView, double pct,
                         long ms) {
        if (pct < 0) {
            bar.setVisibility(View.INVISIBLE);
            pctView.setText(R.string.bar_none);
            whenView.setText("");
        } else {
            bar.setVisibility(View.VISIBLE);
            bar.setProgress((int) Math.round(Math.min(1, pct) * 1000));
            pctView.setText(pct(pct));
            whenView.setText(ms > 0 ? when(ms) : "");
        }
    }

    private String pct(double pct) {
        return pct < 0 ? a.getString(R.string.bar_none)
                : a.getString(R.string.pct, (int) Math.round(Math.min(1, pct) * 100));
    }

    /** "today 13:37", "yesterday", or a short date. */
    private String when(long ms) {
        if (DateUtils.isToday(ms)) {
            return DateFormat.getTimeFormat(a).format(new java.util.Date(ms));
        }
        return DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_MONTH).toString();
    }

    private void showResult(CharSequence text) {
        result.setText(text);
        result.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    // --- Actions -----------------------------------------------------------------

    /** Runs work off the UI thread with the buttons disabled, then shows its sentence. */
    private void run(Supplier<String> work) {
        if (working || sendingFile) {
            return;
        }
        working = true;
        showResult(a.getString(R.string.sheet_working));
        render();
        new Thread(() -> {
            String sentence = work.get();
            BooxState boox = readBoox();
            a.runOnUiThread(() -> {
                working = false;
                apply(boox);
                showResult(sentence);
                render();
            });
        }, "NeoSync book action").start();
    }

    /** Writes the other device's place into Neo Reader's row (today's Fetch from Picco). */
    private void jump() {
        final RemotePosition r = e.remote;
        if (r == null || e.booxPath == null) {
            return;
        }
        run(() -> {
            OnyxMetadataRepository.Book book = a.repository.queryBook(e.booxPath);
            if (book != null && book.totalPages() <= 0) {
                return a.getString(R.string.sheet_not_opened); // no page count to jump with
            }
            String written = book == null ? null : SyncActions.applyRemote(app, a.repository,
                    a.stateStore, book, progressOf(r));
            if (written == null) {
                // Writing Neo Reader's row failed (no row for this path, or the
                // provider refused); an open book is the usual reason to ask about.
                return a.getString(R.string.sheet_book_open);
            }
            changed = true;
            a.settings.recordSyncSuccess(book.name != null ? book.name : book.path, book.path, r.percentage);
            return a.getString(R.string.sheet_jump_done);
        });
    }

    /** Sends this Boox's place to the server (today's Send to Picco). */
    private void sendPlace() {
        if (e.booxPath == null || hash == null) {
            return;
        }
        run(() -> {
            OnyxMetadataRepository.Book book = a.repository.queryBook(e.booxPath);
            if (book == null || book.currentPage() <= 0 || book.totalPages() <= 0) {
                return a.getString(R.string.sheet_not_opened);
            }
            KoSyncClient.Result r = SyncActions.uploadLocal(a.buildClient(), a.stateStore,
                    book, hash);
            if (r.error != KoSyncClient.Error.OK) {
                return a.getString(R.string.sheet_send_failed, KoSyncClient.errorString(r));
            }
            changed = true;
            placeSent = true;
            a.settings.recordSyncSuccess(book.name != null ? book.name : book.path, book.path,
                    SyncActions.localPercentage(book));
            return a.getString(R.string.sheet_place_sent);
        });
    }

    /** Puts back the place from before the latest jump and sends it (spec 7.5). */
    private void undo() {
        if (e.booxPath == null) {
            return;
        }
        run(() -> {
            OnyxMetadataRepository.Book book = a.repository.queryBook(e.booxPath);
            if (book == null) {
                return a.getString(R.string.sheet_undo_failed);
            }
            SyncActions.UndoResult r = SyncActions.undoJump(app, a.repository,
                    new UndoStore(app), a.stateStore, a.buildClient(), book, hash);
            if (r == SyncActions.UndoResult.RESTORED_SENT
                    || r == SyncActions.UndoResult.RESTORED_SEND_LATER) {
                changed = true;
                // Sent already (RESTORED_SENT) or by automatic sync later: no Send button.
                placeSent = true;
                OnyxMetadataRepository.Book fresh = a.repository.queryBook(e.booxPath);
                double backPct = fresh != null && fresh.totalPages() > 0
                        ? SyncActions.localPercentage(fresh) : -1;
                if (r == SyncActions.UndoResult.RESTORED_SENT && fresh != null && backPct >= 0) {
                    // The chip, Connections and the notification show the
                    // place sent, not the jump (R26).
                    a.settings.recordSyncSuccess(fresh.name != null ? fresh.name : fresh.path,
                            fresh.path, backPct);
                }
                String back = pct(backPct);
                return a.getString(r == SyncActions.UndoResult.RESTORED_SENT
                        ? R.string.sheet_undo_sent : R.string.sheet_undo_later, back);
            }
            return a.getString(r == SyncActions.UndoResult.WRITE_FAILED
                    ? R.string.sheet_undo_failed : R.string.sheet_undo_nothing);
        });
    }

    /** Copies the book file to the Picco (adding a cover first), as one small job. */
    private void sendFile(boolean force) {
        if (working || sendingFile || e.booxPath == null) {
            return;
        }
        if (a.isBusy() || JobSession.running()) {
            showResult(a.getString(R.string.sheet_busy));
            return;
        }
        final String address = a.settings.getPiccoAddress();
        if (address.isEmpty()) {
            showResult(a.getString(R.string.transfer_no_address));
            return;
        }
        a.setBusy(true);
        sendingFile = true;
        showResult(a.getString(R.string.transfer_checking));
        render();
        a.withPiccoFolder(address, folder -> {
            sendJob = SendMissingJob.oneBook(app, new PiccoClient(address), address,
                    folder, new File(e.booxPath), a.piccoLibrary,
                    ex -> MainActivity.piccoErrorMessage(app, ex, address), force);
            if (!a.startJob(sendJob)) {
                sendingFile = false;
                sendJob = null;
                showResult(a.getString(R.string.sheet_busy));
                render();
            }
        }, message -> {
            a.setBusy(false);
            sendingFile = false;
            showResult(message != null ? message : "");
            render();
        });
    }

    /** From MainActivity while a job runs: the single send's step, in words. */
    void jobProgress() {
        if (!sendingFile) {
            return;
        }
        if (Job.WAITING.equals(JobSession.state)) {
            showResult(a.getString(R.string.transfer_adding_cover));
        } else if (Job.SENDING.equals(JobSession.state)) {
            showResult(a.getString(R.string.transfer_sending));
        }
    }

    /** From MainActivity when a job ends; only the send this sheet started is ours. */
    void jobFinished(String resultSentence) {
        if (!sendingFile || sendJob == null) {
            return;
        }
        boolean delivered = sendJob.delivered();
        sendingFile = false;
        sendJob = null;
        changed = true;
        if (delivered && e.piccoPath == null) {
            e.piccoPath = new File(e.booxPath).getName();
        }
        showResult(resultSentence != null ? resultSentence : a.getString(R.string.job_stopped));
        render();
    }

    /** Opens the cover sheet for this book only (spec 5.6). */
    private void addCover() {
        if (working || sendingFile || e.booxPath == null) {
            return;
        }
        if (a.isBusy() || JobSession.running()) {
            showResult(a.getString(R.string.sheet_busy));
            return;
        }
        if (!a.ensureFilesAccess()) {
            return;
        }
        final File file = new File(e.booxPath);
        final String piccoPath = e.piccoPath;
        final String title = e.title;
        run(() -> {
            EpubCover.Kind kind = CoverFixer.check(file).kind;
            if (kind != EpubCover.Kind.TAG_IMAGE && kind != EpubCover.Kind.NEEDS_SNAPSHOT) {
                return a.getString(R.string.clean_none);
            }
            CleanPlan plan = new CleanPlan();
            plan.booxBooks.add(file);
            plan.booxTitles.add(title);
            plan.kindOf.put(file.getPath(), kind);
            if (kind == EpubCover.Kind.TAG_IMAGE) {
                plan.tagged++;
            } else {
                plan.pictured++;
            }
            if (piccoPath != null) {
                plan.piccoPathOf.put(file.getPath(), piccoPath);
            }
            a.runOnUiThread(() -> {
                if (MainActivity.gone(a) || a.isBusy() || JobSession.running()) {
                    return;
                }
                dialog.dismiss();
                CoverSheet.show(a, plan);
            });
            return "";
        });
    }

    /** Opens the file in Neo Reader. */
    private void openInReader() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        String path = e.booxPath;
        String mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(fileType(path).toLowerCase(Locale.ROOT));
        intent.setDataAndType(Uri.fromFile(new File(path)), mime != null ? mime : "*/*");
        intent.setPackage(SyncActions.NEOREADER_PACKAGE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Neo Reader opens books by file path (it lists file:// for VIEW);
        // a content:// copy would not be the book in its library. Android
        // refuses file:// in intents unless the VM policy allows it.
        StrictMode.VmPolicy old = StrictMode.getVmPolicy();
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        try {
            a.startActivity(intent);
            dialog.dismiss();
        } catch (ActivityNotFoundException ex) {
            showResult(a.getString(R.string.sheet_no_reader));
        } finally {
            StrictMode.setVmPolicy(old);
        }
    }

    // --- Helpers -----------------------------------------------------------------

    private static TextView link(View root, int id) {
        TextView t = root.findViewById(id);
        t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        return t;
    }

    private static void showLink(TextView link, boolean shown, boolean enabled) {
        link.setVisibility(shown ? View.VISIBLE : View.GONE);
        link.setEnabled(enabled);
        link.setTextColor(link.getContext().getColor(enabled ? R.color.black
                : R.color.ink_disabled));
    }

    private static KoSyncClient.Progress progressOf(RemotePosition r) {
        KoSyncClient.Progress p = new KoSyncClient.Progress();
        p.document = r.document;
        p.percentage = r.percentage;
        p.device = r.device;
        p.deviceId = r.deviceId;
        p.timestampSec = r.timestampMs / 1000L;
        return p;
    }

    private static String fileType(String path) {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : name;
    }

    private static LibraryEntry copy(LibraryEntry s) {
        LibraryEntry c = new LibraryEntry();
        c.key = s.key;
        c.booxPath = s.booxPath;
        c.piccoPath = s.piccoPath;
        c.isEpub = s.isEpub;
        c.booxPct = s.booxPct;
        c.piccoPct = s.piccoPct;
        c.booxLastMs = s.booxLastMs;
        c.remote = s.remote;
        c.syncStatus = s.syncStatus;
        c.status = s.status;
        c.activityMs = s.activityMs;
        c.title = s.title;
        c.author = s.author;
        return c;
    }
}
