package com.n30dyn4m1c.photosphere.sensor;

/**
 * Which physical axis the reported angles describe.
 *
 * <p>Both frames are corrected for display rotation; they differ in what counts as
 * "level".
 */
public enum OrientationReference {
    /**
     * Android's own convention: the angles describe the <b>screen</b> lying flat,
     * face up, with its top edge pointing north.
     *
     * <p>Convenient for a levelling UI, but degenerate for sphere capture: a phone
     * held upright sits at pitch ≈ -90°, which is exactly the gimbal-lock pose
     * where yaw and roll collapse into each other and jitter wildly.
     */
    Screen,

    /**
     * The angles describe where the <b>rear camera</b> points: pitch ≈ 0° when the
     * phone is held upright and aimed at the horizon.
     *
     * <p>This is the useful frame for a photo sphere — yaw is the bearing of the
     * frame being captured, pitch is how far above or below the horizon it sits
     * — and it keeps the singularity at straight-up/straight-down instead of at
     * the app's normal shooting pose.
     */
    Camera
}
