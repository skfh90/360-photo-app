package com.n30dyn4m1c.photosphere.camera;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

import com.n30dyn4m1c.photosphere.R;
import com.n30dyn4m1c.photosphere.sensor.OrientationData;

import java.util.List;

/**
 * Alignment HUD over the viewfinder: projected markers, a centre reticle, and
 * the tap-to-focus box.
 */
public class TargetOverlayView extends View {

    private static final int FOCUS_TRANSITION_MILLIS = 350;
    private static final int PULSE_PERIOD_MILLIS = 1600;
    private static final float RETICLE_RADIUS_FRACTION = 0.16f;

    public static final class FocusReticle {
        public final float x;
        public final float y;
        public final boolean isWorking;
        public final boolean isLocked;

        public FocusReticle(float x, float y, boolean isWorking, boolean isLocked) {
            this.x = x;
            this.y = y;
            this.isWorking = isWorking;
            this.isLocked = isLocked;
        }
    }

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arcRect = new RectF();

    private OrientationData orientation = new OrientationData();
    private AlignmentState alignment = new AlignmentState();
    private SphereTargetPlan plan;
    private int activeIndex;
    private FieldOfView fieldOfView = new FieldOfView(52f, 66f);
    private FocusReticle focusReticle;

    private float focusProgress = 1f;
    private float pulse;
    private float focusAppear = 1f;

    private ValueAnimator pulseAnimator;
    private ValueAnimator focusAnimator;
    private ValueAnimator focusAppearAnimator;

    private int colorReticle;
    private int colorReticleAligned;
    private int colorActive;
    private int colorCompleted;
    private int colorPending;
    private int colorGuide;
    private int colorFlash;

    public TargetOverlayView(Context context) {
        this(context, null);
    }

    public TargetOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeCap(Paint.Cap.ROUND);
        colorReticle = Color.argb(230, 255, 255, 255);
        colorReticleAligned = ContextCompat.getColor(context, R.color.sphere_accent);
        colorActive = ContextCompat.getColor(context, R.color.sphere_active);
        colorCompleted = colorReticleAligned;
        colorPending = Color.argb(82, 255, 255, 255);
        colorGuide = Color.argb(61, 255, 255, 255);
        colorFlash = Color.argb(46, 255, 255, 255);
    }

    public void setOrientation(OrientationData orientation) {
        this.orientation = orientation != null ? orientation : new OrientationData();
        invalidate();
    }

    public void setAlignment(AlignmentState alignment) {
        this.alignment = alignment != null ? alignment : new AlignmentState();
        invalidate();
    }

    public void setPlan(SphereTargetPlan plan, int activeIndex) {
        boolean indexChanged = this.activeIndex != activeIndex;
        this.plan = plan;
        this.activeIndex = activeIndex;
        if (indexChanged) {
            startFocusTransition();
        }
        invalidate();
    }

    public void setFieldOfView(FieldOfView fieldOfView) {
        if (fieldOfView != null) {
            this.fieldOfView = fieldOfView;
            invalidate();
        }
    }

    public void setFocusReticle(FocusReticle focusReticle) {
        FocusReticle previous = this.focusReticle;
        this.focusReticle = focusReticle;
        if (focusReticle != null
                && (previous == null
                || previous.x != focusReticle.x
                || previous.y != focusReticle.y)) {
            startFocusAppear();
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(PULSE_PERIOD_MILLIS);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                pulse = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        pulseAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }
        if (focusAppearAnimator != null) {
            focusAppearAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        float focalPx = SphereProjection.focalLengthPx(width, height, fieldOfView);
        CameraFrame camera = SphereProjection.cameraFrame(orientation);
        drawTargets(canvas, camera, focalPx, width, height);
        drawReticle(canvas, width, height);
        drawFocusBox(canvas, width, height);
        if (alignment.isCapturing()) {
            canvas.drawColor(colorFlash);
        }
    }

    private void drawTargets(
            Canvas canvas,
            CameraFrame camera,
            float focalPx,
            float width,
            float height
    ) {
        if (plan == null) {
            return;
        }
        List<SphereTarget> targets = plan.getTargets();
        if (targets.isEmpty()) {
            return;
        }
        float margin = dp(28f);
        float cx = width / 2f;
        float cy = height / 2f;
        for (int i = 0; i < targets.size(); i++) {
            if (i == activeIndex) {
                continue;
            }
            float[] pos = screenOffset(camera.project(targets.get(i)), cx, cy, focalPx);
            if (pos == null || !isInside(pos[0], pos[1], width, height, margin)) {
                continue;
            }
            boolean shot = i < activeIndex;
            drawTargetDot(
                    canvas,
                    pos[0],
                    pos[1],
                    shot ? colorCompleted : colorPending,
                    shot,
                    dp(shot ? 5f : 7f)
            );
        }
        SphereTarget active = plan.getOrNull(activeIndex);
        if (active != null) {
            drawActiveTarget(canvas, camera, active, focalPx, width, height, margin);
        }
    }

    private void drawTargetDot(Canvas canvas, float x, float y, int color, boolean filled, float radius) {
        if (filled) {
            fillPaint.setColor(color);
            canvas.drawCircle(x, y, radius, fillPaint);
        } else {
            strokePaint.setColor(color);
            strokePaint.setStrokeWidth(dp(1.5f));
            canvas.drawCircle(x, y, radius, strokePaint);
        }
    }

    private void drawActiveTarget(
            Canvas canvas,
            CameraFrame camera,
            SphereTarget target,
            float focalPx,
            float width,
            float height,
            float margin
    ) {
        TargetView view = camera.project(target);
        float cx = width / 2f;
        float cy = height / 2f;
        float[] pos = screenOffset(view, cx, cy, focalPx);
        if (pos == null || !isInside(pos[0], pos[1], width, height, margin)) {
            float[] dir = screenDirection(view);
            drawEdgeChevron(
                    canvas,
                    dir[0],
                    dir[1],
                    withAlpha(colorActive, 0.55f + 0.45f * focusProgress),
                    margin,
                    width,
                    height
            );
            return;
        }
        float separation = (float) Math.hypot(pos[0] - cx, pos[1] - cy);
        if (separation > Math.min(width, height) * RETICLE_RADIUS_FRACTION + dp(24f)) {
            dashPaint.setColor(withAlpha(colorGuide, Color.alpha(colorGuide) / 255f * focusProgress));
            dashPaint.setStrokeWidth(dp(1f));
            dashPaint.setPathEffect(new DashPathEffect(new float[] {dp(6f), dp(8f)}, 0f));
            canvas.drawLine(cx, cy, pos[0], pos[1], dashPaint);
        }
        float ringRadius = dp(16f) + dp(6f) * pulse;
        strokePaint.setColor(withAlpha(colorActive, (1f - pulse) * 0.6f * focusProgress));
        strokePaint.setStrokeWidth(dp(2.5f));
        canvas.drawCircle(pos[0], pos[1], ringRadius, strokePaint);
        fillPaint.setColor(withAlpha(colorActive, focusProgress));
        canvas.drawCircle(pos[0], pos[1], dp(5f), fillPaint);
    }

    private void drawReticle(Canvas canvas, float width, float height) {
        float cx = width / 2f;
        float cy = height / 2f;
        float radius = Math.min(width, height) * RETICLE_RADIUS_FRACTION;
        float strokeWidth = dp(3f);
        float tickLength = dp(10f);
        float closeness;
        if (!alignment.hasDistance()) {
            closeness = 0f;
        } else {
            closeness = coerceIn(
                    1f - (alignment.getDistanceDegrees() - AlignmentGate.DEFAULT_THRESHOLD_DEGREES) / 6f,
                    0f,
                    1f
            );
        }
        int color = lerpColor(colorReticle, colorReticleAligned, closeness);
        strokePaint.setColor(withAlpha(colorReticleAligned, 0.10f + 0.18f * closeness));
        strokePaint.setStrokeWidth(dp(1.5f));
        canvas.drawCircle(cx, cy, radius + dp(8f), strokePaint);
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(strokeWidth);
        canvas.drawCircle(cx, cy, radius, strokePaint);
        fillPaint.setColor(color);
        canvas.drawCircle(cx, cy, dp(2.5f), fillPaint);
        float[] angles = new float[] {0f, 90f, 180f, 270f};
        for (int i = 0; i < angles.length; i++) {
            canvas.save();
            canvas.rotate(angles[i], cx, cy);
            canvas.drawLine(
                    cx,
                    cy - radius - dp(2f),
                    cx,
                    cy - radius - dp(2f) - tickLength,
                    strokePaint
            );
            canvas.restore();
        }
        if (alignment.getDwellProgress() > 0f) {
            strokePaint.setColor(colorReticleAligned);
            strokePaint.setStrokeWidth(strokeWidth * 1.5f);
            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.drawArc(arcRect, -90f, 360f * alignment.getDwellProgress(), false, strokePaint);
        }
    }

    private void drawFocusBox(Canvas canvas, float width, float height) {
        FocusReticle focus = focusReticle;
        if (focus == null) {
            return;
        }
        int color;
        if (focus.isWorking) {
            color = colorActive;
        } else if (focus.isLocked) {
            color = colorReticleAligned;
        } else {
            color = colorReticle;
        }
        float resting = dp(44f);
        float sizePx = focus.isWorking ? resting * (1f + 0.35f * (1f - focusAppear)) : resting;
        float half = sizePx / 2f;
        float cx = focus.x * width;
        float cy = focus.y * height;
        float corner = dp(14f);
        if (focus.isWorking) {
            fillPaint.setColor(withAlpha(color, 0.10f));
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, fillPaint);
        }
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(dp(2.5f));
        path.reset();
        path.moveTo(cx - half, cy - half + corner);
        path.lineTo(cx - half, cy - half);
        path.lineTo(cx - half + corner, cy - half);
        path.moveTo(cx + half - corner, cy - half);
        path.lineTo(cx + half, cy - half);
        path.lineTo(cx + half, cy - half + corner);
        path.moveTo(cx + half, cy + half - corner);
        path.lineTo(cx + half, cy + half);
        path.lineTo(cx + half - corner, cy + half);
        path.moveTo(cx - half + corner, cy + half);
        path.lineTo(cx - half, cy + half);
        path.lineTo(cx - half, cy + half - corner);
        canvas.drawPath(path, strokePaint);
    }

    private void drawEdgeChevron(
            Canvas canvas,
            float dirX,
            float dirY,
            int color,
            float margin,
            float width,
            float height
    ) {
        float[] position = edgePosition(dirX, dirY, margin, width, height);
        float length = dp(14f);
        float angle = (float) Math.toDegrees(Math.atan2(dirY, dirX));
        canvas.save();
        canvas.rotate(angle, position[0], position[1]);
        path.reset();
        path.moveTo(position[0] + length, position[1]);
        path.lineTo(position[0] - length * 0.55f, position[1] - length * 0.8f);
        path.lineTo(position[0] - length * 0.55f, position[1] + length * 0.8f);
        path.close();
        fillPaint.setColor(color);
        canvas.drawPath(path, fillPaint);
        canvas.restore();
    }

    private float[] edgePosition(float dirX, float dirY, float margin, float width, float height) {
        float halfWidth = Math.max(width / 2f - margin, 1f);
        float halfHeight = Math.max(height / 2f - margin, 1f);
        float scaleX = Math.abs(dirX) < 1e-4f ? Float.MAX_VALUE : halfWidth / Math.abs(dirX);
        float scaleY = Math.abs(dirY) < 1e-4f ? Float.MAX_VALUE : halfHeight / Math.abs(dirY);
        float scale = Math.min(scaleX, scaleY);
        return new float[] {width / 2f + dirX * scale, height / 2f + dirY * scale};
    }

    private static float[] screenOffset(TargetView view, float cx, float cy, float focalPx) {
        if (!view.isInFront()) {
            return null;
        }
        return new float[] {
                cx + view.getX() / view.getZ() * focalPx,
                cy - view.getY() / view.getZ() * focalPx
        };
    }

    private static float[] screenDirection(TargetView view) {
        float rawX = view.getX();
        float rawY = -view.getY();
        float length = (float) Math.hypot(rawX, rawY);
        if (length < 1e-4f) {
            return new float[] {1f, 0f};
        }
        return new float[] {rawX / length, rawY / length};
    }

    private static boolean isInside(float x, float y, float width, float height, float margin) {
        return x >= margin && x <= width - margin && y >= margin && y <= height - margin;
    }

    private void startFocusTransition() {
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }
        focusProgress = 0f;
        focusAnimator = ValueAnimator.ofFloat(0f, 1f);
        focusAnimator.setDuration(FOCUS_TRANSITION_MILLIS);
        focusAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        focusAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                focusProgress = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        focusAnimator.start();
    }

    private void startFocusAppear() {
        if (focusAppearAnimator != null) {
            focusAppearAnimator.cancel();
        }
        focusAppear = 0f;
        focusAppearAnimator = ValueAnimator.ofFloat(0f, 1f);
        focusAppearAnimator.setDuration(240);
        focusAppearAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        focusAppearAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                focusAppear = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        focusAppearAnimator.start();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float coerceIn(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(coerceIn(alpha, 0f, 1f) * 255f);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int lerpColor(int start, int end, float t) {
        float amount = coerceIn(t, 0f, 1f);
        int a = Math.round(Color.alpha(start) + (Color.alpha(end) - Color.alpha(start)) * amount);
        int r = Math.round(Color.red(start) + (Color.red(end) - Color.red(start)) * amount);
        int g = Math.round(Color.green(start) + (Color.green(end) - Color.green(start)) * amount);
        int b = Math.round(Color.blue(start) + (Color.blue(end) - Color.blue(start)) * amount);
        return Color.argb(a, r, g, b);
    }
}
