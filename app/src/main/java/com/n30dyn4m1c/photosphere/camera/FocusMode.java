package com.n30dyn4m1c.photosphere.camera;

/**
 * What focus does across a session.
 *
 * Kept top-level rather than nested so the screen can pattern-match on it
 * without qualification.
 */
public enum FocusMode {
    /** The lens has no focus mechanism; {@code AF_MODE_OFF} is mandatory. */
    FIXED_FOCUS,

    /**
     * Tap-to-focus: {@code AF_MODE_AUTO}, triggered where the user taps and then held.
     *
     * The lens stays locked on that distance until the next tap — no frame
     * re-focuses mid-sweep, so a sphere's frames keep one focal plane, and the
     * shots are sharp on the scene instead of parked at infinity.
     */
    FOCUS_POINT
}
