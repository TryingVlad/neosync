package dev.neosync.boox.activity;

/** Which line each item of a left-to-right row goes on when the row wraps. Pure. */
public final class WrapLines {

    private WrapLines() {
    }

    /**
     * @param widths each item's width with its margins (0 for a hidden item)
     * @param max    the row's room
     * @return the line (0, 1, …) of each item; an item too wide on its own
     *         still gets a line of its own
     */
    public static int[] lineOf(int[] widths, int max) {
        int[] lines = new int[widths.length];
        int line = 0;
        int x = 0;
        for (int i = 0; i < widths.length; i++) {
            if (x > 0 && x + widths[i] > max) {
                line++;
                x = 0;
            }
            lines[i] = line;
            x += widths[i];
        }
        return lines;
    }
}
