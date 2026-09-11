package com.n30dyn4m1c.photosphere.camera;

import android.content.Context;
import android.media.MediaActionSound;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * The tick-and-click that tells the user a frame landed.
 *
 * Guided capture fires the shutter on its own, with the phone held at arm's
 * length and the user watching the marker rather than the frame counter, so the
 * confirmation has to be felt and heard rather than read.
 *
 * Holds a loaded sound pool; call {@link #release()} when the screen goes away.
 */
public final class CaptureFeedback {

    private static final String TAG = "CaptureFeedback";

    /** Fallback buzz for devices with no predefined tick (API 26–28). */
    private static final long FALLBACK_TICK_MILLIS = 20L;

    private final Vibrator vibrator;
    private final MediaActionSound shutterSound;
    private boolean isReleased;

    public CaptureFeedback(Context context) {
        this.vibrator = vibratorOf(context);
        this.shutterSound = new MediaActionSound();
        // Preload: an unloaded sample plays late, and late is worse than silent
        // when the point is to mark the instant of capture.
        this.shutterSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    /**
     * Plays the shutter click and a haptic tick.
     *
     * The click is intentionally the system shutter sound: in several
     * jurisdictions a camera must be audible when it captures, and the platform
     * sample is the one that survives silent mode where it is mandated.
     */
    public void onFrameCaptured() {
        if (isReleased) {
            return;
        }
        playTick();
        try {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK);
        } catch (Exception e) {
            Log.w(TAG, "Shutter sound failed", e);
        }
    }

    public void release() {
        if (isReleased) {
            return;
        }
        isReleased = true;
        try {
            shutterSound.release();
        } catch (Exception ignored) {
        }
    }

    private void playTick() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        VibrationEffect effect;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        } else {
            effect = VibrationEffect.createOneShot(
                    FALLBACK_TICK_MILLIS,
                    VibrationEffect.DEFAULT_AMPLITUDE
            );
        }
        try {
            vibrator.vibrate(effect);
        } catch (Exception e) {
            Log.w(TAG, "Haptic tick failed", e);
        }
    }

    private static Vibrator vibratorOf(Context context) {
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
