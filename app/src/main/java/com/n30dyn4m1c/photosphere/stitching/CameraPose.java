package com.n30dyn4m1c.photosphere.stitching;

/**
 * Where the camera was pointing when a frame was shot.
 *
 * Angles follow the conventions the sensor layer publishes: {@link #yawDegrees} is the
 * camera's compass bearing and {@link #pitchDegrees} is <b>negative above the horizon</b>.
 * {@link #getElevationDegrees()} flips the pitch into the sign the geometry here works in,
 * so the rest of this file can read "up is positive" throughout.
 */
public final class CameraPose {
    public final float yawDegrees;
    public final float pitchDegrees;
    public final float rollDegrees;
    /**
     * The camera's basis as a rotation matrix, when the pose was measured as
     * one — in the {@link CameraBasis#toRotationMatrix()} layout (the {@code [right, −up,
     * forward]} columns in the world frame). The sensor layer provides it for
     * every captured frame: the dwell is averaged as *rotations*, because the
     * Euler components collapse into each other at the zenith (pitch ±90°),
     * where yaw alone cannot say which way is up in the frame.
     * {@link CameraBasis#of} prefers it when present; the angles are kept for EXIF,
     * diagnostics and the viewfinder.
     */
    public final double[] matrix;

    public CameraPose(float yawDegrees, float pitchDegrees, float rollDegrees) {
        this(yawDegrees, pitchDegrees, rollDegrees, null);
    }

    public CameraPose(float yawDegrees, float pitchDegrees, float rollDegrees, double[] matrix) {
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.rollDegrees = rollDegrees;
        this.matrix = matrix;
    }

    /** Height above the horizon, positive up. */
    public float getElevationDegrees() {
        return -pitchDegrees;
    }
}
