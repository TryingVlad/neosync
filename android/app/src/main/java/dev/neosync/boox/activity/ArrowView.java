package dev.neosync.boox.activity;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import dev.neosync.boox.R;

/**
 * A horizontal arrow for the Connections diagram: black on white, pointing
 * right, or both ways; dashed while the link isn't up. Dashes are drawn as
 * separate segments so they stay crisp on e-ink with any layer type.
 */
public class ArrowView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private boolean twoWay;
    private boolean dashed;

    public ArrowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ArrowView);
        twoWay = a.getBoolean(R.styleable.ArrowView_twoWay, false);
        dashed = a.getBoolean(R.styleable.ArrowView_dashed, false);
        a.recycle();
        line.setColor(Color.BLACK);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * density);
        head.setColor(Color.BLACK);
        head.setStyle(Paint.Style.FILL);
    }

    public void setDashed(boolean dashed) {
        if (this.dashed != dashed) {
            this.dashed = dashed;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = Math.round(12 * density) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthSpec),
                resolveSize(h, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float y = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float headLen = 9 * density;
        float headHalf = 5 * density;
        float start = twoWay ? left + headLen - density : left;
        float end = right - headLen + density;
        if (dashed) {
            float dash = 6 * density;
            float gap = 5 * density;
            for (float x = start; x < end; x += dash + gap) {
                canvas.drawLine(x, y, Math.min(x + dash, end), y, line);
            }
        } else {
            canvas.drawLine(start, y, end, y, line);
        }
        drawHead(canvas, right, y, -headLen, headHalf);
        if (twoWay) {
            drawHead(canvas, left, y, headLen, headHalf);
        }
    }

    /** A filled triangle with its tip at (tipX, y), extending by dx. */
    private void drawHead(Canvas canvas, float tipX, float y, float dx, float half) {
        path.reset();
        path.moveTo(tipX, y);
        path.lineTo(tipX + dx, y - half);
        path.lineTo(tipX + dx, y + half);
        path.close();
        canvas.drawPath(path, head);
    }
}
