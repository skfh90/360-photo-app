package com.n30dyn4m1c.photosphere.sensor;

import android.content.Context;
import android.view.Surface;
import android.view.WindowManager;

/**
 * The rotation the display is currently drawn at, as a {@code Surface.ROTATION_*}.
 *
 * Falls back to {@link Surface#ROTATION_0} when the context has no display to speak of
 * — an application context on API 30+, or a preview renderer.
 */
public final class DisplayRotation {

    private DisplayRotation() {
    }

    public static int current(Context context) {
        try {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                return windowManager.getDefaultDisplay().getRotation();
            }
        } catch (Exception ignored) {
        }
        return Surface.ROTATION_0;
    }
}
