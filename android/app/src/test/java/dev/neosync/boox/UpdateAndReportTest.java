package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

import dev.neosync.boox.update.ProblemReport;
import dev.neosync.boox.update.UpdateChecker;

public class UpdateAndReportTest {

    @Test
    public void versionCompare() {
        assertTrue(UpdateChecker.isNewer("7.1.0", "7.0.2"));
        assertTrue(UpdateChecker.isNewer("v7.0.10", "7.0.9"));
        assertTrue(UpdateChecker.isNewer("8", "7.9.9"));
        assertFalse(UpdateChecker.isNewer("7.0.2", "7.0.2"));
        assertFalse(UpdateChecker.isNewer("7.1", "7.1.0"));
        assertFalse(UpdateChecker.isNewer("7.0.1", "7.0.2"));
        assertFalse(UpdateChecker.isNewer("7.0.2-beta", "7.0.2"));
    }

    @Test
    public void redactsKnownBooksAndAccount() {
        String path = "/storage/emulated/0/Books/The Last Sun (K.D. Edwards).epub";
        String log = "SyncService: Pushed 8/444 (0123456789abcdef0123456789abcdef) for " + path + "\n"
                + "PiccoClient: upload The Last Sun (K.D. Edwards).epub -> 200\n"
                + "OnyxStatsRepo: Recorded 1 row(s) for The Last Sun (K.D. Edwards)\n"
                + "KoSyncClient: auth -> 401 for reader42\n";
        String out = ProblemReport.redact(log, Arrays.asList(
                path, "The Last Sun (K.D. Edwards).epub", "The Last Sun (K.D. Edwards)", "reader42"));
        assertEquals("SyncService: Pushed 8/444 (<hash>) for <path>\n"
                + "PiccoClient: upload <hidden> -> 200\n"
                + "OnyxStatsRepo: Recorded 1 row(s) for <hidden>\n"
                + "KoSyncClient: auth -> 401 for <hidden>\n", out);
    }

    @Test
    public void redactsUnknownPathsEmailsAndBookFiles() {
        String out = ProblemReport.redact(
                "Cannot read book file: /storage/emulated/0/x/Other.epub\n"
                        + "mail me@example.com about Notes.pdf\n",
                Arrays.asList());
        assertEquals("Cannot read book file: <path>\nmail <email> about <book>\n", out);
    }

    @Test
    public void tailKeepsNewestWholeLinesWithinLimit() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            log.append("line ").append(i).append('\n');
        }
        String out = ProblemReport.tail(log.toString(), 200);
        assertTrue(out.length() <= 200);
        assertTrue(out.startsWith("(older log lines left out)\n"));
        assertTrue(out.endsWith("line 999\n"));
        assertFalse(out.contains("line 0\n"));
        assertEquals("short\n", ProblemReport.tail("short\n", 200));
    }
}
