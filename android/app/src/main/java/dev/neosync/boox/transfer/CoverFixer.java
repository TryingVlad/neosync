package dev.neosync.boox.transfer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;

import dev.neosync.boox.epub.EpubCover;
import dev.neosync.boox.epub.FirstPageSnapshot;

/**
 * Gives an EPUB a cover the Picco can show. On this Boox the book is fixed in
 * place (same path, chapters untouched, so Neo Reader keeps its notes and
 * position) after the original is copied to the backup folder.
 */
public class CoverFixer {

    private static final String TAG = "CoverFixer";

    public enum Outcome { ALREADY_HAS_COVER, TAGGED, ADDED_PAGE, NOT_AN_EPUB, FAILED }

    private final Context context;

    public CoverFixer(Context context) {
        this.context = context;
    }

    /** Hidden from the Boox library by the leading dot. */
    public static File backupDir() {
        return new File(Environment.getExternalStorageDirectory(), "Books/.neosync-backup");
    }

    public static boolean isEpub(File file) {
        return file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".epub");
    }

    /**
     * EPUBs on the Picco (paths as PiccoClient.listBooks returns them) whose
     * file name matches no book in the Boox library. Goes by the library's
     * paths, not by readable files.
     */
    public static java.util.List<String> piccoOnlyEpubs(java.util.List<String> onPicco,
                                                       java.util.Collection<String> booxPaths) {
        java.util.Set<String> booxNames = new java.util.HashSet<>();
        for (String path : booxPaths) {
            booxNames.add(path.substring(path.lastIndexOf('/') + 1));
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String piccoPath : onPicco) {
            String name = piccoPath.substring(piccoPath.lastIndexOf('/') + 1);
            if (isEpub(new File(name)) && !booxNames.contains(name)) {
                out.add(piccoPath);
            }
        }
        return out;
    }

    /** Cheap check, no rendering. */
    public static EpubCover.Check check(File file) {
        return isEpub(file) ? EpubCover.check(file)
                : new EpubCover.Check(EpubCover.Kind.UNREADABLE, null, null);
    }

    /**
     * Fixes a book on this Boox in place, keeping a backup of the original.
     * Blocks (renders on the main thread); call from a background thread.
     */
    public Outcome fixInPlace(File book) {
        EpubCover.Check check = check(book);
        if (check.kind == EpubCover.Kind.HAS_COVER) return Outcome.ALREADY_HAS_COVER;
        if (check.kind == EpubCover.Kind.UNREADABLE) return Outcome.NOT_AN_EPUB;
        File temp = new File(book.getParentFile(), "." + book.getName() + ".neosync-tmp");
        try {
            backup(book);
            Outcome outcome = write(book, temp, check);
            // Only a book the Picco will now read replaces the original.
            if (EpubCover.check(temp).kind != EpubCover.Kind.HAS_COVER) {
                throw new IOException("fixed copy still has no cover");
            }
            if (!temp.renameTo(book)) {
                EpubCover.copy(temp, book);
            }
            return outcome;
        } catch (Exception e) {
            Log.w(TAG, "Could not fix " + book.getName() + ": " + e);
            return Outcome.FAILED;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    /** Fixes src into dst (for a book that is only on the Picco). */
    public Outcome fixCopy(File src, File dst) {
        EpubCover.Check check = check(src);
        if (check.kind == EpubCover.Kind.HAS_COVER) return Outcome.ALREADY_HAS_COVER;
        if (check.kind == EpubCover.Kind.UNREADABLE) return Outcome.NOT_AN_EPUB;
        try {
            Outcome outcome = write(src, dst, check);
            return EpubCover.check(dst).kind == EpubCover.Kind.HAS_COVER ? outcome : Outcome.FAILED;
        } catch (Exception e) {
            Log.w(TAG, "Could not fix " + src.getName() + ": " + e);
            return Outcome.FAILED;
        }
    }

    private Outcome write(File src, File dst, EpubCover.Check check) throws IOException {
        if (check.kind == EpubCover.Kind.TAG_IMAGE) {
            EpubCover.tagExisting(src, dst, check);
            return Outcome.TAGGED;
        }
        byte[] jpeg = FirstPageSnapshot.render(context, src, check.firstPageEntry);
        EpubCover.addImage(src, dst, jpeg);
        return Outcome.ADDED_PAGE;
    }

    /**
     * Copies the original into the backup folder. Never replaces an existing
     * backup: a same-named book from another folder gets "name (2).epub".
     */
    private static void backup(File book) throws IOException {
        File dir = backupDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no backup folder");
        String name = book.getName();
        String stem = name.substring(0, name.length() - ".epub".length());
        File target = new File(dir, name);
        for (int n = 2; target.exists(); n++) {
            if (target.length() == book.length()) return; // already backed up
            target = new File(dir, stem + " (" + n + ").epub");
        }
        EpubCover.copy(book, target);
    }
}
