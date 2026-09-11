package com.n30dyn4m1c.photosphere.camera;

/**
 * One frame the sphere needs, expressed as a direction to aim the camera at.
 *
 * Angles follow {@link com.n30dyn4m1c.photosphere.sensor.OrientationData} exactly, so a target is
 * "reached" when the tracker reports the same pair: {@link #getYawDegrees()} is the compass
 * bearing of the camera and {@link #getPitchDegrees()} is <b>negative above the horizon</b>.
 * {@link #getElevationDegrees()} is the friendlier reading of the same number for anything
 * user-facing.
 */
public final class SphereTarget {

    /** Compass bearing to aim at, -180°..180° (0° = north). */
    private final float yawDegrees;
    /** Vertical aim, -90°..90°, negative above the horizon. */
    private final float pitchDegrees;

    /**
     * The unit world direction (X east, Y north, Z up) to aim at.
     *
     * Computed once per target rather than per projection: a plan holds a few
     * hundred of these and the overlay projects every one of them on every
     * frame, so the four trigonometric calls behind them are worth spending at
     * construction time instead of at display rate.
     */
    private final double directionX;
    private final double directionY;
    private final double directionZ;

    public SphereTarget(float yawDegrees, float pitchDegrees) {
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        double yaw = Math.toRadians(yawDegrees);
        double elevation = Math.toRadians(getElevationDegrees());
        double cosElevation = Math.cos(elevation);
        this.directionX = Math.sin(yaw) * cosElevation;
        this.directionY = Math.cos(yaw) * cosElevation;
        this.directionZ = Math.sin(elevation);
    }

    public float getYawDegrees() {
        return yawDegrees;
    }

    public float getPitchDegrees() {
        return pitchDegrees;
    }

    /** Height above the horizon, positive up — the sign most people expect. */
    public float getElevationDegrees() {
        return -pitchDegrees;
    }

    /** True for the dedicated feet / ground shot at the bottom of a sphere run. */
    public boolean isNadir() {
        return SphereTargetPlan.isNadirElevation(getElevationDegrees());
    }

    public double getDirectionX() {
        return directionX;
    }

    public double getDirectionY() {
        return directionY;
    }

    public double getDirectionZ() {
        return directionZ;
    }

    /** Builds a target from an elevation (positive up) rather than a pitch. */
    public static SphereTarget atElevation(float yawDegrees, float elevationDegrees) {
        return new SphereTarget(normalizeDegrees(yawDegrees), -elevationDegrees);
    }

    /** Wraps {@code degrees} into {@code [-180, 180)}, so yaw crossing north stays continuous. */
    private static float normalizeDegrees(float degrees) {
        float value = degrees % 360f;
        if (value >= 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SphereTarget)) return false;
        SphereTarget that = (SphereTarget) o;
        return Float.compare(that.yawDegrees, yawDegrees) == 0
                && Float.compare(that.pitchDegrees, pitchDegrees) == 0;
    }

    @Override
    public int hashCode() {
        int result = (yawDegrees != 0.0f ? Float.floatToIntBits(yawDegrees) : 0);
        result = 31 * result + (pitchDegrees != 0.0f ? Float.floatToIntBits(pitchDegrees) : 0);
        return result;
    }
}
