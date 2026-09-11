package com.n30dyn4m1c.photosphere.camera;

/**
 * Decides when a held aim becomes a capture.
 *
 * The rule is "inside {@code thresholdDegrees} continuously for {@code dwellMillis}". The
 * dwell is what stops a frame being taken while the phone is still swinging
 * through the target: at a walking pan rate the reticle crosses a 2° window in
 * well under 300 ms, so only a deliberate stop fires the shutter. Any sample
 * outside the threshold clears the timer — there is no leniency, because a
 * frame captured mid-swing is a blurred frame the stitcher has to reject later.
 *
 * The caller supplies the clock, which keeps this pure and testable; use a
 * monotonic source such as {@code SystemClock.elapsedRealtime()} in production so a
 * wall-clock adjustment cannot fire the shutter.
 */
public final class AlignmentGate {

    /** Aim tolerance. Tighter than the stitcher needs, loose enough to hold by hand. */
    public static final float DEFAULT_THRESHOLD_DEGREES = 2f;

    /** How long the aim must hold before the shutter fires. */
    public static final long DEFAULT_DWELL_MILLIS = 300L;

    private final float thresholdDegrees;
    private final long dwellMillis;

    /** Timestamp the current uninterrupted alignment began, or null if not aligned. */
    private Long alignedSinceMillis;

    public AlignmentGate() {
        this(DEFAULT_THRESHOLD_DEGREES, DEFAULT_DWELL_MILLIS);
    }

    public AlignmentGate(float thresholdDegrees) {
        this(thresholdDegrees, DEFAULT_DWELL_MILLIS);
    }

    public AlignmentGate(float thresholdDegrees, long dwellMillis) {
        this.thresholdDegrees = thresholdDegrees;
        this.dwellMillis = dwellMillis;
    }

    /** The gate's verdict for one orientation sample. */
    public static final class Reading {
        private final float dwellProgress;
        private final boolean isAligned;
        /** True on the single sample that completes the dwell. */
        private final boolean isTriggered;

        public Reading(float dwellProgress, boolean isAligned, boolean isTriggered) {
            this.dwellProgress = dwellProgress;
            this.isAligned = isAligned;
            this.isTriggered = isTriggered;
        }

        public float getDwellProgress() {
            return dwellProgress;
        }

        public boolean isAligned() {
            return isAligned;
        }

        public boolean isTriggered() {
            return isTriggered;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Reading)) return false;
            Reading reading = (Reading) o;
            return Float.compare(reading.dwellProgress, dwellProgress) == 0
                    && isAligned == reading.isAligned
                    && isTriggered == reading.isTriggered;
        }

        @Override
        public int hashCode() {
            int result = (dwellProgress != 0.0f ? Float.floatToIntBits(dwellProgress) : 0);
            result = 31 * result + (isAligned ? 1 : 0);
            result = 31 * result + (isTriggered ? 1 : 0);
            return result;
        }
    }

    public Reading update(float distanceDegrees, long nowMillis) {
        if (Float.isNaN(distanceDegrees) || distanceDegrees > thresholdDegrees) {
            alignedSinceMillis = null;
            return new Reading(0f, false, false);
        }

        if (alignedSinceMillis == null) {
            alignedSinceMillis = nowMillis;
        }
        long since = alignedSinceMillis;
        long held = nowMillis - since;
        float progress;
        if (dwellMillis <= 0L) {
            progress = 1f;
        } else {
            progress = coerceIn(held / (float) dwellMillis, 0f, 1f);
        }
        boolean triggered = held >= dwellMillis;
        // The trigger is consumed here: leaving the timer running would fire
        // again on the very next sample while the user is still holding still.
        if (triggered) {
            alignedSinceMillis = null;
        }

        return new Reading(progress, true, triggered);
    }

    /** Drops any part-completed dwell, e.g. after advancing to a new target. */
    public void reset() {
        alignedSinceMillis = null;
    }

    private static float coerceIn(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
