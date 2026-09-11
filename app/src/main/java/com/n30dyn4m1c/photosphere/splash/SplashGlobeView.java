package com.n30dyn4m1c.photosphere.splash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.n30dyn4m1c.photosphere.R;

import java.util.Random;

/**
 * Cinematic wireframe globe for the launch splash: a glowing sphere that
 * spins once while latitude/longitude lines sweep past a lit equator.
 */
public final class SplashGlobeView extends View {

    private static final int MERIDIANS = 12;
    private static final int PARALLELS = 8;
    private static final int SEGMENTS = 56;
    private static final int STAR_COUNT = 56;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint equatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();

    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starA = new float[STAR_COUNT];

    private int accent;
    private int background;
    private float spinRadians;
    private float appear = 1f;

    public SplashGlobeView(Context context) {
        super(context);
        init();
    }

    public SplashGlobeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SplashGlobeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        accent = ContextCompat.getColor(getContext(), R.color.sphere_accent);
        background = ContextCompat.getColor(getContext(), R.color.sphere_background);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        equatorPaint.setStyle(Paint.Style.STROKE);
        equatorPaint.setStrokeCap(Paint.Cap.ROUND);
        highlightPaint.setStyle(Paint.Style.FILL);
        starPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        fillPaint.setStyle(Paint.Style.FILL);
        haloPaint.setStyle(Paint.Style.STROKE);
    }

    /**
     * @param spinTurns full revolutions (1 = one spin)
     * @param appear    0..1 scale and fade of the globe
     */
    public void setPose(float spinTurns, float appear) {
        this.spinRadians = spinTurns * (float) (Math.PI * 2.0);
        this.appear = Math.max(0f, Math.min(1f, appear));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        Random random = new Random(2026L);
        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = random.nextFloat();
            starY[i] = random.nextFloat();
            starA[i] = 0.18f + random.nextFloat() * 0.7f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        canvas.drawColor(background);

        float cx = w * 0.5f;
        float cy = h * 0.42f;
        float radius = Math.min(w, h) * 0.28f * (0.86f + 0.14f * appear);
        drawStars(canvas, w, h);
        drawGlow(canvas, cx, cy, radius);
        drawBody(canvas, cx, cy, radius);
        drawWireframe(canvas, cx, cy, radius);
        drawHighlight(canvas, cx, cy, radius);
    }

    private void drawStars(Canvas canvas, int w, int h) {
        for (int i = 0; i < STAR_COUNT; i++) {
            float twinkle = 0.55f + 0.45f * (float) Math.sin(spinRadians * 1.7 + i);
            int alpha = Math.round(255f * appear * starA[i] * twinkle * 0.55f);
            starPaint.setColor(Color.argb(alpha, 231, 237, 241));
            canvas.drawCircle(starX[i] * w, starY[i] * h, 1.2f + starA[i] * 1.6f, starPaint);
        }
    }

    private void drawGlow(Canvas canvas, float cx, float cy, float radius) {
        int[] colors = new int[] {
                withAlpha(accent, Math.round(70 * appear)),
                withAlpha(accent, Math.round(22 * appear)),
                Color.TRANSPARENT
        };
        glowPaint.setShader(new RadialGradient(
                cx, cy, radius * 2.15f,
                colors,
                new float[] {0.18f, 0.48f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, radius * 2.15f, glowPaint);
        glowPaint.setShader(null);

        haloPaint.setStrokeWidth(radius * 0.045f);
        haloPaint.setColor(withAlpha(accent, Math.round(90 * appear)));
        canvas.drawCircle(cx, cy, radius * 1.08f, haloPaint);
    }

    private void drawBody(Canvas canvas, float cx, float cy, float radius) {
        int[] colors = new int[] {
                Color.argb(Math.round(230 * appear), 18, 48, 58),
                Color.argb(Math.round(255 * appear), 8, 14, 18),
                Color.argb(Math.round(255 * appear), 4, 7, 10)
        };
        fillPaint.setShader(new RadialGradient(
                cx - radius * 0.28f,
                cy - radius * 0.32f,
                radius * 1.25f,
                colors,
                new float[] {0f, 0.55f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, radius, fillPaint);
        fillPaint.setShader(null);
    }

    private void drawWireframe(Canvas canvas, float cx, float cy, float radius) {
        linePaint.setStrokeWidth(Math.max(1.2f, radius * 0.012f));
        equatorPaint.setStrokeWidth(Math.max(2.4f, radius * 0.028f));

        for (int m = 0; m < MERIDIANS; m++) {
            float lon = (float) (m * Math.PI / (MERIDIANS / 2.0));
            strokeCurve(canvas, cx, cy, radius, lon, true, false);
        }
        for (int p = 1; p < PARALLELS; p++) {
            float lat = (float) (-Math.PI / 2.0 + Math.PI * p / (double) PARALLELS);
            strokeCurve(canvas, cx, cy, radius, lat, false, Math.abs(lat) < 0.08f);
        }
    }

    private void strokeCurve(
            Canvas canvas,
            float cx,
            float cy,
            float radius,
            float fixedAngle,
            boolean meridian,
            boolean equator
    ) {
        linePath.reset();
        boolean started = false;
        float prevX = 0f;
        for (int i = 0; i <= SEGMENTS; i++) {
            float t = i / (float) SEGMENTS;
            float lat;
            float lon;
            if (meridian) {
                lat = (float) (-Math.PI / 2.0 + Math.PI * t);
                lon = fixedAngle;
            } else {
                lat = fixedAngle;
                lon = (float) (t * Math.PI * 2.0);
            }
            float x = (float) (Math.cos(lat) * Math.sin(lon));
            float y = (float) Math.sin(lat);
            float z = (float) (Math.cos(lat) * Math.cos(lon));
            float cos = (float) Math.cos(spinRadians);
            float sin = (float) Math.sin(spinRadians);
            float rx = x * cos + z * sin;
            float rz = -x * sin + z * cos;
            float sx = cx + rx * radius;
            float sy = cy - y * radius;
            if (rz < -0.08f) {
                started = false;
                continue;
            }
            if (!started) {
                linePath.moveTo(sx, sy);
                started = true;
            } else {
                // Skip a wrap seam so parallels do not draw a chord across the globe.
                if (!meridian && Math.abs(sx - prevX) > radius * 0.85f) {
                    linePath.moveTo(sx, sy);
                } else {
                    linePath.lineTo(sx, sy);
                }
            }
            prevX = sx;
        }
        Paint paint = equator ? equatorPaint : linePaint;
        int alpha = equator ? Math.round(230 * appear) : Math.round(150 * appear);
        paint.setColor(withAlpha(accent, alpha));
        if (equator) {
            paint.setShadowLayer(radius * 0.12f, 0f, 0f, withAlpha(accent, Math.round(160 * appear)));
        } else {
            paint.clearShadowLayer();
        }
        canvas.drawPath(linePath, paint);
        paint.clearShadowLayer();
    }

    private void drawHighlight(Canvas canvas, float cx, float cy, float radius) {
        highlightPaint.setShader(new LinearGradient(
                cx - radius,
                cy - radius,
                cx + radius * 0.2f,
                cy + radius * 0.1f,
                new int[] {
                        withAlpha(Color.WHITE, Math.round(55 * appear)),
                        Color.TRANSPARENT
                },
                new float[] {0f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx - radius * 0.22f, cy - radius * 0.28f, radius * 0.42f, highlightPaint);
        highlightPaint.setShader(null);
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
