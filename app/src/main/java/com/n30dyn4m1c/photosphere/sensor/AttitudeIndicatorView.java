package com.n30dyn4m1c.photosphere.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.n30dyn4m1c.photosphere.R;

/**
 * Artificial-horizon style view of the current attitude.
 */
public class AttitudeIndicatorView extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dial = new Path();
    private final RectF dialRect = new RectF();

    private OrientationData orientation = new OrientationData();
    private int sky;
    private int ground;
    private int horizon;
    private int outline;
    private int marker;
    private String cardinal = "N";

    public AttitudeIndicatorView(Context context) {
        this(context, null);
    }

    public AttitudeIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        sky = ContextCompat.getColor(context, R.color.sphere_accent_16);
        ground = ContextCompat.getColor(context, R.color.sphere_surface_high);
        horizon = ContextCompat.getColor(context, R.color.sphere_on_surface);
        outline = ContextCompat.getColor(context, R.color.sphere_outline);
        marker = ContextCompat.getColor(context, R.color.sphere_accent);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setOrientation(OrientationData orientation) {
        this.orientation = orientation != null ? orientation : new OrientationData();
        cardinal = cardinalFor(this.orientation.yawDegrees);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float radius = Math.min(width, height) / 2f;
        float cx = width / 2f;
        float cy = height / 2f;
        float dialRadius = radius * 0.78f;
        float overdraw = dialRadius * 3f;
        dialRect.set(cx - dialRadius, cy - dialRadius, cx + dialRadius, cy + dialRadius);
        dial.reset();
        dial.addOval(dialRect, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(dial);
        canvas.rotate(-orientation.rollDegrees, cx, cy);
        float horizonY = cy - (orientation.pitchDegrees / 90f) * dialRadius;
        fillPaint.setColor(sky);
        canvas.drawRect(cx - overdraw, horizonY - overdraw, cx + overdraw, horizonY, fillPaint);
        fillPaint.setColor(ground);
        canvas.drawRect(cx - overdraw, horizonY, cx + overdraw, horizonY + overdraw, fillPaint);
        strokePaint.setColor(horizon);
        strokePaint.setStrokeWidth(dp(2f));
        canvas.drawLine(cx - overdraw, horizonY, cx + overdraw, horizonY, strokePaint);
        canvas.restore();

        strokePaint.setColor(outline);
        strokePaint.setStrokeWidth(dp(1.5f));
        canvas.drawCircle(cx, cy, dialRadius, strokePaint);

        canvas.save();
        canvas.rotate(orientation.yawDegrees, cx, cy);
        strokePaint.setColor(marker);
        strokePaint.setStrokeWidth(dp(4f));
        canvas.drawLine(cx, cy - radius, cx, cy - dialRadius - dp(2f), strokePaint);
        canvas.restore();

        fillPaint.setColor(horizon);
        fillPaint.setTextAlign(Paint.Align.CENTER);
        fillPaint.setTextSize(dp(16f));
        canvas.drawText(cardinal, cx, cy - (fillPaint.ascent() + fillPaint.descent()) / 2f, fillPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    static String cardinalFor(float yawDegrees) {
        if (Math.abs(yawDegrees) <= 45f) {
            return "N";
        }
        if (yawDegrees > 45f && yawDegrees <= 135f) {
            return "E";
        }
        if (yawDegrees < -45f && yawDegrees >= -135f) {
            return "W";
        }
        return "S";
    }
}
