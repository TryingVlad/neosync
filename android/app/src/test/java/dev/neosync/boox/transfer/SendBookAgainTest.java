package dev.neosync.boox.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.neosync.boox.jobs.Job;
import dev.neosync.boox.jobs.SendMissingJob;

/** "Send book file again": SendMissingJob.oneBook with force replaces the Picco's copy. */
public class SendBookAgainTest {

    /** Records what the job asked the Picco to do. */
    private static final class FakePicco extends PiccoClient {
        final List<String> onPicco;
        final List<String> calls = new ArrayList<>();

        FakePicco(String... onPicco) {
            super("192.168.1.2");
            this.onPicco = Arrays.asList(onPicco);
        }

        @Override
        public boolean hasBook(String fileName) {
            for (String p : onPicco) {
                if (p.endsWith("/" + fileName) || p.equals(fileName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<String> listBooks() {
            return onPicco;
        }

        @Override
        public UploadResult upload(File file, String folder) {
            calls.add("upload " + folder + " " + file.getName());
            return new UploadResult(Status.SENT, 200, "");
        }

        @Override
        public UploadResult replace(File file, String piccoPath) {
            calls.add("replace " + piccoPath);
            return new UploadResult(Status.SENT, 200, "");
        }
    }

    private static final Context CONTEXT = new ContextWrapper(null) {
        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return null;
        }
    };

    private static final PiccoLibraryStore STORE = new PiccoLibraryStore(CONTEXT) {
        @Override
        public void addUploaded(String address, String fileName) {
        }
    };

    private static File book() throws IOException {
        File f = File.createTempFile("send", ".tmp");
        File named = new File(f.getParentFile(), "Dune-" + System.nanoTime() + ".epub");
        try (FileOutputStream out = new FileOutputStream(named)) {
            out.write("not really an epub".getBytes());
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        named.deleteOnExit();
        return named;
    }

    private static SendMissingJob run(FakePicco picco, File file, boolean force) {
        SendMissingJob job = SendMissingJob.oneBook(CONTEXT, picco, "192.168.1.2", "/books",
                file, STORE, e -> "failed", force);
        job.run(new Job.Listener() {
            @Override
            public void progress(int done, int total, String key, String state) {
            }

            @Override
            public void finished(String resultSentence) {
            }
        });
        return job;
    }

    @Test
    public void withoutForceABookOnThePiccoIsSkipped() throws IOException {
        File file = book();
        FakePicco picco = new FakePicco("books/" + file.getName());
        SendMissingJob job = run(picco, file, false);
        assertTrue(picco.calls.isEmpty());
        assertTrue(job.delivered());
    }

    @Test
    public void forceReplacesThePiccoCopyWhereItIs() throws IOException {
        File file = book();
        FakePicco picco = new FakePicco("Fiction/" + file.getName());
        SendMissingJob job = run(picco, file, true);
        assertEquals(Arrays.asList("replace Fiction/" + file.getName()), picco.calls);
        assertTrue(job.delivered());
    }

    @Test
    public void forceWithoutACopySendsToTheFolder() throws IOException {
        File file = book();
        FakePicco picco = new FakePicco("books/Other.epub");
        SendMissingJob job = run(picco, file, true);
        assertEquals(Arrays.asList("upload /books " + file.getName()), picco.calls);
        assertTrue(job.delivered());
    }
}
