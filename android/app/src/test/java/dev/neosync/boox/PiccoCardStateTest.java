package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dev.neosync.boox.library.PiccoCardState;
import dev.neosync.boox.library.PiccoCardState.State;

public class PiccoCardStateTest {

    @Test
    public void noAddressIsNotSetUp() {
        State s = PiccoCardState.of(false, null, false, false, false);
        assertEquals(State.NOT_SET_UP, s);
        assertTrue(PiccoCardState.showsSteps(s));
        assertFalse(PiccoCardState.isConnected(s));
    }

    @Test
    public void answeredAddressIsConnected() {
        State s = PiccoCardState.of(true, Boolean.TRUE, false, false, false);
        assertEquals(State.CONNECTED, s);
        assertFalse(PiccoCardState.showsSteps(s));
        assertTrue(PiccoCardState.isConnected(s));
    }

    @Test
    public void unknownAddressBeingProbedIsChecking() {
        State s = PiccoCardState.of(true, null, true, false, false);
        assertEquals(State.CHECKING, s);
        assertFalse(PiccoCardState.showsSteps(s));
    }

    @Test
    public void lastAnswerWinsOverARunningProbe() {
        assertEquals(State.UNREACHABLE, PiccoCardState.of(true, Boolean.FALSE, true, false, false));
        assertEquals(State.CONNECTED, PiccoCardState.of(true, Boolean.TRUE, true, false, false));
    }

    @Test
    public void unansweredAddressIsUnreachable() {
        assertEquals(State.UNREACHABLE, PiccoCardState.of(true, Boolean.FALSE, false, false, false));
        assertEquals(State.UNREACHABLE, PiccoCardState.of(true, null, false, false, false));
    }

    @Test
    public void failedSearchSaysNotFound() {
        assertEquals(State.NOT_FOUND, PiccoCardState.of(true, Boolean.FALSE, false, false, true));
        assertEquals(State.NOT_FOUND, PiccoCardState.of(false, null, false, false, true));
        // A later probe that answers wins over the old search result.
        assertEquals(State.CONNECTED, PiccoCardState.of(true, Boolean.TRUE, false, false, true));
    }

    @Test
    public void searchingShowsStepsNotCheckingOrConnected() {
        // The stuck-search inputs: back on the screen mid-search, with a
        // probe flagged and an earlier good answer. The search is authoritative.
        State s = PiccoCardState.of(true, Boolean.TRUE, true, true, false);
        assertEquals(State.LOOKING, s);
        assertTrue(PiccoCardState.showsSteps(s));
        assertFalse(PiccoCardState.isConnected(s));
        assertEquals(State.LOOKING, PiccoCardState.of(true, null, true, true, false));
        assertEquals(State.LOOKING, PiccoCardState.of(false, null, false, true, true));
    }
}
