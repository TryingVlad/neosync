package dev.neosync.boox.kosync;

import android.app.ActivityManager;
import android.content.Context;

import dev.neosync.boox.contentprovider.OnyxMetadataRepository;

import dev.neosync.boox.epub.EpubSpine;

import java.io.File;

/**
 * Sync operations shared by the background service (automatic sync) and the
 * settings UI (manual "Apply remote" / "Upload local", which deliberately
 * skip the newest-timestamp check — the user decides).
 */
public final class SyncActions {

    /** NeoReader's package — the app whose Metadata rows we sync. */
    public static final String NEOREADER_PACKAGE = "com.onyx.kreader";

    private SyncActions() {
    }

    /** Document hash for the configured match method, or null if unhashable. */
    public static String documentHash(SyncSettings settings, String path) {
        if (SyncSettings.MATCH_FILENAME.equals(settings.getMatchMethod())) {
            return KoReaderDocumentHash.fromFilename(path);
        }
        return KoReaderDocumentHash.fromContent(new File(path));
    }

    /**
     * Writes remote progress into the Onyx provider: progress column (library
     * shelf) plus NeoReader's internal position when a calibration pair is
     * available. Records what was written for echo suppression.
     *
     * @return the written "page/total" string, or null on failure
     */
    public static String applyRemote(Context context,
                                     OnyxMetadataRepository repository, SyncStateStore stateStore,
                                     OnyxMetadataRepository.Book book,
                                     KoSyncClient.Progress remote) {
        int total = book.totalPages();
        if (total <= 0) {
            return null;
        }
        double pct = Math.max(0.0, Math.min(1.0, remote.percentage));
        int page = Math.max(1, Math.min(total, (int) Math.round(pct * total)));
        long remoteMs = remote.timestampSec * 1000L;

        SyncStateStore.BookState state = stateStore.get(book.path);
        long positionV2 = -1;
        if (state.calibPosition >= 0 && state.calibPercentage > 0.005) {
            positionV2 = Math.max(0,
                    Math.round(state.calibPosition * (pct / state.calibPercentage)));
        }

        // NeoReader only reads the position back from Metadata on a COLD
        // start; a cached background process restores its private store and
        // overwrites our row. Kill it (background only — a foreground reading
        // session is never touched) BEFORE writing, so it can't flush stale
        // state over the top and the next open picks up the synced position.
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            am.killBackgroundProcesses(NEOREADER_PACKAGE);
        }

        if (!repository.writeProgress(book, page, total, remoteMs, positionV2)) {
            return null;
        }
        // Firmware 4.x: the reader restores from a chapter-based position.
        // Map the remote percentage through the EPUB's spine byte table (the
        // coordinate system CrossPoint's percentage is defined in).
        EpubSpine spine = spineFor(book);
        if (spine != null && book.chapterPosition() != null) {
            EpubSpine.Item item = spine.itemAt(pct);
            long charOffset = spine.byteToChar(item, spine.globalOffset(pct) - item.start);
            repository.writeChapterPosition(book.path, book.extraAttributes,
                    OnyxMetadataRepository.chapterPosition(item.index, item.id,
                            item.start, item.end, charOffset));
        }
        String written = page + "/" + total;
        state.lastWrittenProgress = written;
        state.lastWrittenAccessMs = remoteMs;
        stateStore.put(book.path, state);

        // Reading done on the other device should show up in the Boox reading
        // statistics too (estimated durations; local NeoReader reading is
        // recorded by Onyx natively). Best-effort — never fails the sync.
        int localPage = book.currentPage();
        if (page > localPage) {
            try {
                new dev.neosync.boox.contentprovider.OnyxStatisticsRepository(context)
                        .recordRemoteReading(book, localPage, page, total,
                                book.lastAccessMs, remoteMs,
                                remote.device != null && !remote.device.isEmpty()
                                        ? remote.device : "remote device");
            } catch (Exception ignored) {
            }
        }
        return written;
    }

    /** The EPUB's spine table, or null for non-EPUBs and unreadable files. */
    static EpubSpine spineFor(OnyxMetadataRepository.Book book) {
        if (book.path == null || !book.path.toLowerCase(java.util.Locale.ROOT).endsWith(".epub")) {
            return null;
        }
        return EpubSpine.read(new File(book.path));
    }

    /**
     * The book's local position as a fraction 0..1. For EPUBs on firmware 4.x
     * this is byte-based (chapter start + offset within the chapter, over the
     * total spine bytes), which is what CrossPoint's percentage means. Falls
     * back to page/totalPages, which can be several percent off because pages
     * and bytes don't grow at the same rate.
     */
    public static double localPercentage(OnyxMetadataRepository.Book book) {
        long[] pos = book.chapterPosition();
        if (pos != null) {
            EpubSpine spine = spineFor(book);
            int index = (int) pos[0];
            if (spine != null && index < spine.items.size()
                    && spine.items.get(index).start == pos[2]) {
                EpubSpine.Item item = spine.items.get(index);
                return spine.percentage(index, spine.charToByte(item, pos[1]));
            }
        }
        int total = book.totalPages();
        return total > 0 ? Math.min(1.0, (double) book.currentPage() / total) : 0.0;
    }

    /**
     * Pushes the book's current local progress to the server and records it
     * for push dedup.
     */
    public static KoSyncClient.Result uploadLocal(KoSyncClient client, SyncStateStore stateStore,
                                                  OnyxMetadataRepository.Book book, String hash) {
        int page = book.currentPage();
        int total = book.totalPages();
        double percentage = localPercentage(book);
        KoSyncClient.Result result = client.updateProgress(hash, String.valueOf(page), percentage);
        if (result.error == KoSyncClient.Error.OK) {
            SyncStateStore.BookState state = stateStore.get(book.path);
            state.lastPushedProgress = book.progress;
            stateStore.put(book.path, state);
        }
        return result;
    }
}
