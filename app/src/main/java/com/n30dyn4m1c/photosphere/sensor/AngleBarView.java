package com.n30dyn4m1c.photosphere.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.n30dyn4m1c.photosphere.R;

/** Horizontal track showing where an angle sits within its range. */
public class AngleBarView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float range = 180f;
    private float degrees;
    private int track;
    private int marker;
    private int zero;

    public AngleBarView(Context context) {
        this(context, null);
    }

    public AngleBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        track = ContextCompat.getColor(context, R.color.sphere_surface_high);
        marker = ContextCompat.getColor(context, R.color.sphere_accent);
        zero = ContextCompat.getColor(context, R.color.sphere_outline);
    }

    public void setRange(float range) {
        this.range = range > 0f ? range : 180f;
        invalidate();
    }

    public void setDegrees(float degrees) {
        this.degrees = degrees;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float radius = height / 2f;
        paint.setColor(track);
        canvas.drawRoundRect(0f, 0f, width, height, radius, radius, paint);
        paint.setColor(zero);
        paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        canvas.drawLine(width / 2f, 0f, width / 2f, height, paint);
        float fraction = (coerceIn(degrees / range, -1f, 1f) + 1f) / 2f;
        paint.setColor(marker);
        canvas.drawCircle(radius + fraction * (width - 2f * radius), radius, radius, paint);
    }

    private static float coerceIn(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
