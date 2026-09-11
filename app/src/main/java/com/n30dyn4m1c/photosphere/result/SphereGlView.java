package com.n30dyn4m1c.photosphere.result;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * {@link GLSurfaceView} that paints one equirectangular frame as a look-around.
 *
 * <p>A pannable 360° view of an equirectangular sphere. Drag grabs the scene;
 * pinch changes the field of view. The look starts on north — the centre of the
 * canvas, which is where guided capture begins — and stays inside the crop so a
 * ring capture cannot be aimed at uncovered sky.
 *
 * <p>Rendered with a full-screen OpenGL ES 2.0 quad rather than a tessellated
 * sphere: every pixel is a look direction, sampled through the same mapping
 * the stitcher used to paint the JPEG. No extra dependency, and it runs on
 * every device this app already supports.
 *
 * <p><b>Gestures</b> are handled on this view ({@link #onTouchEvent}): drag pans,
 * pinch zooms. If a transparent overlay sits on top and consumes the pointer
 * stream (as the old Compose overlay did — a {@code SurfaceView} is a separate
 * compositor layer), drive the look through {@link #setLook} instead of relying
 * on this view's own touches.
 *
 * <p>Lifecycle pause/resume is bound through the ViewTree owner so a host
 * recomposition cannot leak the GL thread after this screen leaves.
 */
public class SphereGlView extends GLSurfaceView {

    /**
     * Called after a user-driven look change (drag or pinch). Hosts that used
     * to pass {@code onLook} into the Compose {@code SphereViewer} can set this.
     */
    public interface LookListener {
        void onLook();
    }

    private final EquirectSphereRenderer renderer = new EquirectSphereRenderer();
    private final ScaleGestureDetector scaleDetector;

    private LifecycleEventObserver lifecycleObserver;

    private float yawDegrees = 0f;
    private float pitchDegrees = 0f;
    private float fovDegrees = SphereViewProjection.DEFAULT_FOV_DEGREES;
    private SphereViewCrop crop = SphereViewCrop.Full;
    private LookListener lookListener;

    private float lastX;
    private float lastY;
    private boolean dragging;

    public SphereGlView(Context context) {
        this(context, null);
    }

    public SphereGlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        getHolder().setFormat(PixelFormat.RGBA_8888);
        setPreserveEGLContextOnPause(true);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setClickable(true);
        setFocusable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float zoom = detector.getScaleFactor();
                if (zoom > 0f) {
                    fovDegrees = clamp(
                            fovDegrees / zoom,
                            SphereViewProjection.MIN_FOV_DEGREES,
                            SphereViewProjection.MAX_FOV_DEGREES);
                    applyLook(0f, 0f);
                }
                return true;
            }
        });
    }

    private static LifecycleOwner lifecycleOwnerOf(android.view.View view) {
        Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof LifecycleOwner) {
                return (LifecycleOwner) context;
            }
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public EquirectSphereRenderer getRenderer() {
        return renderer;
    }

    public void setLookListener(LookListener lookListener) {
        this.lookListener = lookListener;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LifecycleOwner owner = lifecycleOwnerOf(this);
        if (owner == null) {
            return;
        }
        LifecycleEventObserver observer = new LifecycleEventObserver() {
            @Override
            public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
                if (event == Lifecycle.Event.ON_RESUME) {
                    onResume();
                } else if (event == Lifecycle.Event.ON_PAUSE) {
                    onPause();
                }
            }
        };
        owner.getLifecycle().addObserver(observer);
        lifecycleObserver = observer;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (lifecycleObserver != null) {
            LifecycleOwner owner = lifecycleOwnerOf(this);
            if (owner != null) {
                owner.getLifecycle().removeObserver(lifecycleObserver);
            }
            lifecycleObserver = null;
        }
        onPause();
        super.onDetachedFromWindow();
    }

    public void setSphere(final Bitmap bitmap, final SphereViewCrop crop) {
        this.crop = crop;
        queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.setSphere(bitmap, crop);
                requestRender();
            }
        });
    }

    public void setLook(float yawDegrees, float pitchDegrees, float fovDegrees) {
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.fovDegrees = fovDegrees;
        renderer.lookYawDegrees = yawDegrees;
        renderer.lookPitchDegrees = pitchDegrees;
        renderer.horizontalFovDegrees = fovDegrees;
        requestRender();
    }

    /**
     * Drag pans the look; pinch (via {@link ScaleGestureDetector}) changes FOV.
     * An overlay that intercepts touches should call {@link #setLook} instead.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean scaled = scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastX = event.getX();
            lastY = event.getY();
            dragging = true;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (!scaleDetector.isInProgress() && dragging) {
                float panX = event.getX() - lastX;
                float panY = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();
                applyPan(panX, panY);
            } else {
                lastX = event.getX();
                lastY = event.getY();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return true;
        }
        return scaled || super.onTouchEvent(event);
    }

    private void applyPan(float panX, float panY) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            notifyLook();
            return;
        }
        float[] delta = SphereViewProjection.lookDelta(panX, panY, width, height, fovDegrees);
        applyLook(delta[0], delta[1]);
    }

    private void applyLook(float dYaw, float dPitch) {
        int width = Math.max(getWidth(), 1);
        int height = Math.max(getHeight(), 1);
        float fovV = SphereViewProjection.verticalFovDegrees(fovDegrees, width, height);
        yawDegrees = SphereViewProjection.wrapDegrees(yawDegrees + dYaw);
        pitchDegrees = SphereViewProjection.clampPitch(pitchDegrees + dPitch, fovV, crop);
        setLook(yawDegrees, pitchDegrees, fovDegrees);
        notifyLook();
    }

    private void notifyLook() {
        LookListener callback = lookListener;
        if (callback != null) {
            callback.onLook();
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
