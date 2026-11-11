package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import dev.neosync.boox.library.Suggestion;
import dev.neosync.boox.library.Suggestion.Kind;
import dev.neosync.boox.library.SuggestionPicker;
import dev.neosync.boox.library.SyncProblem;

public class SuggestionPickerTest {

    private static SuggestionPicker.Input base() {
        SuggestionPicker.Input in = new SuggestionPicker.Input();
        in.autoSyncOn = true;
        in.problem = SyncProblem.Type.NONE;
        in.piccoConfigured = true;
        in.piccoReachable = true;
        return in;
    }

    private static Set<String> set(String... s) {
        Set<String> r = new HashSet<>();
        Collections.addAll(r, s);
        return r;
    }

    @Test
    public void nothingToSuggest() {
        assertNull(SuggestionPicker.pick(base()));
    }

    @Test
    public void jobBeatsEverything() {
        SuggestionPicker.Input in = base();
        in.jobRunning = true;
        in.autoSyncOn = false;
        in.problem = SyncProblem.Type.AUTH;
        in.problemIsAlarm = true;
        in.piccoConfigured = false;
        in.missingOnPicco = 2;
        in.blackCovers = 1;
        in.updateVersion = "1.2";
        Suggestion s = SuggestionPicker.pick(in);
        assertEquals(Kind.JOB, s.kind);
        assertEquals(0, s.count);
    }

    @Test
    public void pausedBeatsProblems() {
        SuggestionPicker.Input in = base();
        in.autoSyncOn = false;
        in.problem = SyncProblem.Type.AUTH;
        in.problemIsAlarm = true;
        assertEquals(Kind.PROBLEM_PAUSED, SuggestionPicker.pick(in).kind);
    }

    @Test
    public void freshProblemNotShown() {
        SuggestionPicker.Input in = base();
        in.problem = SyncProblem.Type.AUTH;
        in.problemIsAlarm = false;
        in.blackCovers = 2;
        assertEquals(Kind.FIX_COVERS, SuggestionPicker.pick(in).kind);
    }

    @Test
    public void noInternetProblem() {
        SuggestionPicker.Input in = base();
        in.problem = SyncProblem.Type.NO_INTERNET;
        in.problemIsAlarm = true;
        assertEquals(Kind.PROBLEM_NO_INTERNET, SuggestionPicker.pick(in).kind);
    }

    @Test
    public void authAndServerProblems() {
        SuggestionPicker.Input in = base();
        in.problemIsAlarm = true;
        in.problem = SyncProblem.Type.AUTH;
        assertEquals(Kind.PROBLEM_AUTH, SuggestionPicker.pick(in).kind);
        for (SyncProblem.Type t : new SyncProblem.Type[] {SyncProblem.Type.SERVER_UNREACHABLE,
                SyncProblem.Type.RATE_LIMITED, SyncProblem.Type.OTHER}) {
            in.problem = t;
            assertEquals(Kind.PROBLEM_SERVER, SuggestionPicker.pick(in).kind);
        }
    }

    @Test
    public void nullProblemAndNullDismissedAreSafe() {
        SuggestionPicker.Input in = base();
        in.problem = null;
        in.problemIsAlarm = true;
        in.dismissed = null;
        in.blackCovers = 1;
        assertEquals(Kind.FIX_COVERS, SuggestionPicker.pick(in).kind);
    }

    @Test
    public void setupWhenNoAddress() {
        SuggestionPicker.Input in = base();
        in.piccoConfigured = false;
        in.piccoReachable = false;
        assertEquals(Kind.SETUP_PICCO, SuggestionPicker.pick(in).kind);
    }

    @Test
    public void unreachablePiccoWithCacheDoesNotAskForSetup() {
        SuggestionPicker.Input in = base();
        in.piccoReachable = false;
        in.missingOnPicco = 3;
        assertNull(SuggestionPicker.pick(in));
    }

    @Test
    public void sendMissingOnlyWhenReachable() {
        SuggestionPicker.Input in = base();
        in.missingOnPicco = 3;
        in.missingNames = "A, B, C";
        Suggestion s = SuggestionPicker.pick(in);
        assertEquals(Kind.SEND_MISSING, s.kind);
        assertEquals(3, s.count);
        assertEquals("A, B, C", s.detail);
        in.piccoReachable = false;
        assertNull(SuggestionPicker.pick(in));
    }

    @Test
    public void dismissedSendMissingReturnsWhenCountChanges() {
        SuggestionPicker.Input in = base();
        in.missingOnPicco = 3;
        in.dismissed = set("SEND_MISSING:3");
        assertNull(SuggestionPicker.pick(in));
        in.missingOnPicco = 4;
        Suggestion s = SuggestionPicker.pick(in);
        assertNotNull(s);
        assertEquals("SEND_MISSING:4", s.dismissKey());
    }

    @Test
    public void dismissedSetupFallsThrough() {
        SuggestionPicker.Input in = base();
        in.piccoConfigured = false;
        in.blackCovers = 1;
        in.dismissed = set("SETUP_PICCO:0");
        assertEquals(Kind.FIX_COVERS, SuggestionPicker.pick(in).kind);
    }

    @Test
    public void problemsCannotBeDismissed() {
        SuggestionPicker.Input in = base();
        in.problem = SyncProblem.Type.AUTH;
        in.problemIsAlarm = true;
        in.dismissed = set("PROBLEM_AUTH:0", "PROBLEM_PAUSED:0", "JOB:0");
        assertEquals(Kind.PROBLEM_AUTH, SuggestionPicker.pick(in).kind);
        in.autoSyncOn = false;
        assertEquals(Kind.PROBLEM_PAUSED, SuggestionPicker.pick(in).kind);
        in.jobRunning = true;
        assertEquals(Kind.JOB, SuggestionPicker.pick(in).kind);
    }

    @Test
    public void updateLast() {
        SuggestionPicker.Input in = base();
        in.updateVersion = "1.5.0";
        Suggestion s = SuggestionPicker.pick(in);
        assertEquals(Kind.UPDATE, s.kind);
        assertEquals("1.5.0", s.detail);
        assertEquals("UPDATE:1.5.0", s.dismissKey());
        in.dismissed = set("UPDATE:1.5.0");
        assertNull(SuggestionPicker.pick(in));
        in.updateVersion = "1.6.0";
        assertEquals(Kind.UPDATE, SuggestionPicker.pick(in).kind);
        in.blackCovers = 1;
        assertEquals(Kind.FIX_COVERS, SuggestionPicker.pick(in).kind);
    }
}
