package dev.neosync.boox.activity;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * A row of links that moves the ones that don't fit onto the next line
 * instead of clipping them (narrow screens, R33). Children keep their margins.
 */
public class WrapRow extends ViewGroup {

    /** Each child's width with margins, and its line, from the last measure. */
    private int[] widths = new int[0];
    private int[] lines = new int[0];

    public WrapRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int room = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            room = Integer.MAX_VALUE;
        }
        int n = getChildCount();
        if (widths.length != n) {
            widths = new int[n];
        }
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            widths[i] = 0;
            if (c.getVisibility() == GONE) {
                continue;
            }
            measureChildWithMargins(c, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) c.getLayoutParams();
            widths[i] = c.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
        }
        lines = WrapLines.lineOf(widths, room);
        int height = 0;
        int widest = 0;
        int lineHeight = 0;
        int lineWidth = 0;
        int line = 0;
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) {
                continue;
            }
            if (lines[i] != line) {
                height += lineHeight;
                widest = Math.max(widest, lineWidth);
                lineHeight = 0;
                lineWidth = 0;
                line = lines[i];
            }
            MarginLayoutParams lp = (MarginLayoutParams) c.getLayoutParams();
            lineHeight = Math.max(lineHeight, c.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
            lineWidth += widths[i];
        }
        height += lineHeight;
        widest = Math.max(widest, lineWidth);
        setMeasuredDimension(
                resolveSize(widest + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                resolveSize(height + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = Math.min(getChildCount(), lines.length);
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;
        int line = 0;
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) {
                continue;
            }
            MarginLayoutParams lp = (MarginLayoutParams) c.getLayoutParams();
            if (lines[i] != line) {
                x = getPaddingLeft();
                y += lineHeight;
                lineHeight = 0;
                line = lines[i];
            }
            int left = x + lp.leftMargin;
            int top = y + lp.topMargin;
            c.layout(left, top, left + c.getMeasuredWidth(), top + c.getMeasuredHeight());
            x += widths[i];
            lineHeight = Math.max(lineHeight, c.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
        }
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams ? new MarginLayoutParams((MarginLayoutParams) p)
                : new MarginLayoutParams(p);
    }
}
