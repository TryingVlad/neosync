package dev.neosync.boox;

import static org.junit.Assert.assertArrayEquals;

import dev.neosync.boox.activity.WrapLines;

import org.junit.Test;

public class WrapLinesTest {

    @Test
    public void everythingOnOneLineWhenItFits() {
        assertArrayEquals(new int[]{0, 0, 0}, WrapLines.lineOf(new int[]{100, 80, 90}, 300));
    }

    @Test
    public void linkThatDoesNotFitStartsTheNextLine() {
        // The book sheet's three links at 360dp: 312dp of room.
        assertArrayEquals(new int[]{0, 1, 1}, WrapLines.lineOf(new int[]{186, 140, 158}, 312));
        assertArrayEquals(new int[]{0, 1, 2}, WrapLines.lineOf(new int[]{200, 200, 200}, 312));
    }

    @Test
    public void tooWideAloneStillGetsItsOwnLine() {
        assertArrayEquals(new int[]{0, 1, 2}, WrapLines.lineOf(new int[]{50, 400, 50}, 312));
    }

    @Test
    public void goneChildrenTakeNoRoom() {
        assertArrayEquals(new int[]{0, 0, 0}, WrapLines.lineOf(new int[]{200, 0, 100}, 300));
    }

    @Test
    public void nothingToLayOut() {
        assertArrayEquals(new int[0], WrapLines.lineOf(new int[0], 300));
    }
}
