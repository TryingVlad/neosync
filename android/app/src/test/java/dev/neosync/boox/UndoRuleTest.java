package dev.neosync.boox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.neosync.boox.library.UndoRule;

import org.junit.Test;

public class UndoRuleTest {
    private static final long SAVED = 1_000_000_000L;   // Boox clock
    private static final long JUMP_REMOTE = 900_000_000L; // server clock, skewed

    @Test
    public void availableRightAfterJump() {
        assertTrue(UndoRule.available(SAVED, "50/300", "50/300", JUMP_REMOTE, JUMP_REMOTE,
                SAVED + 1000));
    }

    @Test
    public void expiresAfter24h() {
        assertTrue(UndoRule.available(SAVED, "50/300", "50/300", JUMP_REMOTE, JUMP_REMOTE,
                SAVED + 86399999L));
        assertFalse(UndoRule.available(SAVED, "50/300", "50/300", JUMP_REMOTE, JUMP_REMOTE,
                SAVED + 86400000L));
    }

    @Test
    public void goneWhenReadAfterJump() {
        assertFalse(UndoRule.available(SAVED, "50/300", "60/300", JUMP_REMOTE, JUMP_REMOTE,
                SAVED + 1000));
    }

    @Test
    public void goneWhenPiccoMovedAgain() {
        assertFalse(UndoRule.available(SAVED, "50/300", "50/300", JUMP_REMOTE, JUMP_REMOTE + 1,
                SAVED + 1000));
    }

    @Test
    public void serverClockAheadOfBooxDoesNotHideUndo() {
        // The server's clock runs ahead: the jump's own position is "after"
        // savedAtMs, but it is the position the jump applied.
        long ahead = SAVED + 60_000;
        assertTrue(UndoRule.available(SAVED, "50/300", "50/300", ahead, ahead, SAVED + 1000));
    }

    @Test
    public void noUndoWhenNothingWasWritten() {
        assertFalse(UndoRule.available(0, "50/300", "50/300", 0, 0, SAVED));
    }

    @Test
    public void noUndoWithoutCurrentProgress() {
        assertFalse(UndoRule.available(SAVED, "50/300", null, JUMP_REMOTE, JUMP_REMOTE,
                SAVED + 1000));
    }
}
