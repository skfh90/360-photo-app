package com.n30dyn4m1c.photosphere.camera;

/**
 * What the reticle should currently be showing.
 *
 * Kept separate from {@link AlignmentGate} so the overlay can be handed a value class
 * and previewed without a sensor behind it.
 */
public final class AlignmentState {

    /** Angle between the camera's aim and the active target. NaN before the first fix. */
    private final float distanceDegrees;
    /** How much of the dwell has elapsed, 0..1. */
    private final float dwellProgress;
    /** True while the aim is inside the capture threshold. */
    private final boolean isAligned;
    /** True while a shutter is in flight, so the overlay can flash. */
    private final boolean isCapturing;

    public AlignmentState() {
        this(Float.NaN, 0f, false, false);
    }

    public AlignmentState(
            float distanceDegrees,
            float dwellProgress,
            boolean isAligned,
            boolean isCapturing
    ) {
        this.distanceDegrees = distanceDegrees;
        this.dwellProgress = dwellProgress;
        this.isAligned = isAligned;
        this.isCapturing = isCapturing;
    }

    public float getDistanceDegrees() {
        return distanceDegrees;
    }

    public float getDwellProgress() {
        return dwellProgress;
    }

    public boolean isAligned() {
        return isAligned;
    }

    public boolean isCapturing() {
        return isCapturing;
    }

    public boolean hasDistance() {
        return !Float.isNaN(distanceDegrees);
    }

    public boolean getHasDistance() {
        return hasDistance();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AlignmentState)) return false;
        AlignmentState that = (AlignmentState) o;
        return Float.compare(that.distanceDegrees, distanceDegrees) == 0
                && Float.compare(that.dwellProgress, dwellProgress) == 0
                && isAligned == that.isAligned
                && isCapturing == that.isCapturing;
    }

    @Override
    public int hashCode() {
        int result = (distanceDegrees != 0.0f ? Float.floatToIntBits(distanceDegrees) : 0);
        result = 31 * result + (dwellProgress != 0.0f ? Float.floatToIntBits(dwellProgress) : 0);
        result = 31 * result + (isAligned ? 1 : 0);
        result = 31 * result + (isCapturing ? 1 : 0);
        return result;
    }
}
