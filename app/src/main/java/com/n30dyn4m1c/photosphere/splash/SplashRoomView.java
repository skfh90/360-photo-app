package com.n30dyn4m1c.photosphere.splash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.n30dyn4m1c.photosphere.R;

/**
 * Cream splash illustration: an isometric room that eases into place.
 */
public final class SplashRoomView extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int cream;
    private int linen;
    private int walnut;
    private int terracotta;
    private int brass;

    private float appear = 1f;
    private float sway;

    public SplashRoomView(Context context) {
        super(context);
        init();
    }

    public SplashRoomView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SplashRoomView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        cream = ContextCompat.getColor(getContext(), R.color.sphere_background);
        linen = ContextCompat.getColor(getContext(), R.color.sphere_surface_high);
        walnut = ContextCompat.getColor(getContext(), R.color.sphere_on_surface);
        terracotta = ContextCompat.getColor(getContext(), R.color.sphere_accent);
        brass = ContextCompat.getColor(getContext(), R.color.sphere_active);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setPose(float progress, float appear) {
        this.appear = Math.max(0f, Math.min(1f, appear));
        this.sway = (float) Math.sin(progress * Math.PI) * 0.04f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawColor(cream);
        if (w <= 0 || h <= 0) {
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.42f;
        float size = Math.min(w, h) * 0.34f * (0.88f + 0.12f * appear);
        canvas.save();
        canvas.translate(cx, cy + (1f - appear) * size * 0.12f);
        canvas.scale(appear, appear);
        canvas.rotate(sway * 18f);

        float[] a = iso(-1f, 0f, -1f, size);
        float[] b = iso(1f, 0f, -1f, size);
        float[] c = iso(1f, 0f, 1f, size);
        float[] d = iso(-1f, 0f, 1f, size);
        float[] a2 = iso(-1f, 1.15f, -1f, size);
        float[] b2 = iso(1f, 1.15f, -1f, size);
        float[] d2 = iso(-1f, 1.15f, 1f, size);

        fillQuad(canvas, a, b, c, d, linen);
        fillQuad(canvas, a, d, d2, a2, mix(linen, terracotta, 0.08f));
        fillQuad(canvas, a, b, b2, a2, mix(linen, walnut, 0.06f));

        strokePaint.setStrokeWidth(Math.max(2.5f, size * 0.018f));
        strokePaint.setColor(withAlpha(walnut, Math.round(200 * appear)));
        strokeQuad(canvas, a, b, c, d);
        strokeQuad(canvas, a, d, d2, a2);
        strokeQuad(canvas, a, b, b2, a2);

        float[] rug1 = iso(-0.45f, 0.02f, -0.2f, size);
        float[] rug2 = iso(0.45f, 0.02f, -0.2f, size);
        float[] rug3 = iso(0.45f, 0.02f, 0.55f, size);
        float[] rug4 = iso(-0.45f, 0.02f, 0.55f, size);
        fillQuad(canvas, rug1, rug2, rug3, rug4, withAlpha(terracotta, Math.round(150 * appear)));

        float[] w1 = iso(0.15f, 0.35f, -0.98f, size);
        float[] w2 = iso(0.75f, 0.35f, -0.98f, size);
        float[] w3 = iso(0.75f, 0.95f, -0.98f, size);
        float[] w4 = iso(0.15f, 0.95f, -0.98f, size);
        fillQuad(canvas, w1, w2, w3, w4, withAlpha(brass, Math.round(90 * appear)));
        strokePaint.setColor(withAlpha(terracotta, Math.round(220 * appear)));
        strokeQuad(canvas, w1, w2, w3, w4);

        fillPaint.setShader(new LinearGradient(
                0f, -size,
                0f, size,
                withAlpha(0x00FFFFFF, 0),
                withAlpha(walnut, Math.round(18 * appear)),
                Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(0f, size * 0.15f, size * 1.15f, fillPaint);
        fillPaint.setShader(null);
        canvas.restore();
    }

    private static float[] iso(float x, float y, float z, float size) {
        float ix = (x - z) * 0.86f * size;
        float iy = (x + z) * 0.5f * size - y * size * 0.92f;
        return new float[] {ix, iy};
    }

    private void fillQuad(Canvas canvas, float[] p, float[] q, float[] r, float[] s, int color) {
        path.reset();
        path.moveTo(p[0], p[1]);
        path.lineTo(q[0], q[1]);
        path.lineTo(r[0], r[1]);
        path.lineTo(s[0], s[1]);
        path.close();
        fillPaint.setColor(color);
        canvas.drawPath(path, fillPaint);
    }

    private void strokeQuad(Canvas canvas, float[] p, float[] q, float[] r, float[] s) {
        path.reset();
        path.moveTo(p[0], p[1]);
        path.lineTo(q[0], q[1]);
        path.lineTo(r[0], r[1]);
        path.lineTo(s[0], s[1]);
        path.close();
        canvas.drawPath(path, strokePaint);
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
