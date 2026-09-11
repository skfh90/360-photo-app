package com.n30dyn4m1c.photosphere.sensor;

import java.util.Arrays;

/**
 * A single attitude sample, in degrees.
 *
 * <p>The signs follow {@code SensorManager.getOrientation}, interpreted through the
 * tracker's {@link OrientationReference}. With the default
 * {@link OrientationReference#Camera} frame, and the phone held upright with the rear
 * camera aimed at the horizon:
 *
 * <ul>
 *   <li>{@link #yawDegrees} is the compass bearing the <b>camera</b> points at: 0° = magnetic
 *       north, +90° = east, ±180° = south, -90° = west.
 *   <li>{@link #pitchDegrees} is 0° at the horizon and goes <b>negative as the camera aims
 *       upward</b> (-90° = straight up, +90° = straight down at your feet).
 *   <li>{@link #rollDegrees} is 0° when the display's up axis points at the sky, and turns
 *       positive as the device is rolled clockwise from the photographer's view.
 * </ul>
 *
 * <p>See {@link OrientationReference#Screen} for the plain Android interpretation.
 */
public final class OrientationData {

    /** Azimuth, -180°..180°. */
    public final float yawDegrees;
    /** Vertical tilt, -90°..90° (an {@code asin}, so it never wraps). */
    public final float pitchDegrees;
    /** Side tilt, -180°..180°. */
    public final float rollDegrees;
    /** How much the fused sensor currently trusts itself. */
    public final OrientationAccuracy accuracy;
    /** {@code SensorEvent.timestamp} of the sample, in nanoseconds of uptime. */
    public final long timestampNanos;
    /**
     * The camera's basis as a rotation matrix — laid out exactly as
     * {@code CameraBasis.toRotationMatrix} (stitching) arranges them (the
     * {@code [right, −up, forward]} columns in the world frame), so it can be handed
     * to {@code CameraBasis.fromRotationMatrix} without a transpose.
     *
     * <p>Carried alongside the angles because a pose is averaged and
     * reconstructed as a <em>rotation</em>: the Euler components collapse into each
     * other at the zenith, where yaw alone cannot say which way is up in the
     * frame. Null for a sample that never saw the sensor.
     */
    public final float[] cameraBasis;

    public OrientationData() {
        this(0f, 0f, 0f, OrientationAccuracy.Unknown, 0L, null);
    }

    public OrientationData(float yawDegrees, float pitchDegrees, float rollDegrees) {
        this(yawDegrees, pitchDegrees, rollDegrees, OrientationAccuracy.Unknown, 0L, null);
    }

    public OrientationData(
            float yawDegrees,
            float pitchDegrees,
            float rollDegrees,
            OrientationAccuracy accuracy) {
        this(yawDegrees, pitchDegrees, rollDegrees, accuracy, 0L, null);
    }

    public OrientationData(
            float yawDegrees,
            float pitchDegrees,
            float rollDegrees,
            OrientationAccuracy accuracy,
            long timestampNanos) {
        this(yawDegrees, pitchDegrees, rollDegrees, accuracy, timestampNanos, null);
    }

    public OrientationData(
            float yawDegrees,
            float pitchDegrees,
            float rollDegrees,
            OrientationAccuracy accuracy,
            long timestampNanos,
            float[] cameraBasis) {
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.rollDegrees = rollDegrees;
        this.accuracy = accuracy != null ? accuracy : OrientationAccuracy.Unknown;
        this.timestampNanos = timestampNanos;
        this.cameraBasis = cameraBasis;
    }

    public float getYawDegrees() {
        return yawDegrees;
    }

    public float getPitchDegrees() {
        return pitchDegrees;
    }

    public float getRollDegrees() {
        return rollDegrees;
    }

    public OrientationAccuracy getAccuracy() {
        return accuracy;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public float[] getCameraBasis() {
        return cameraBasis;
    }

    /** False for the initial placeholder, before the first sensor event lands. */
    public boolean getHasFix() {
        return timestampNanos > 0L;
    }

    /** False for the initial placeholder, before the first sensor event lands. */
    public boolean hasFix() {
        return timestampNanos > 0L;
    }

    public OrientationData copy(
            float yawDegrees,
            float pitchDegrees,
            float rollDegrees,
            OrientationAccuracy accuracy,
            long timestampNanos,
            float[] cameraBasis) {
        return new OrientationData(
                yawDegrees, pitchDegrees, rollDegrees, accuracy, timestampNanos, cameraBasis);
    }

    public OrientationData withAccuracy(OrientationAccuracy accuracy) {
        return new OrientationData(
                yawDegrees, pitchDegrees, rollDegrees, accuracy, timestampNanos, cameraBasis);
    }

    /**
     * Wraps {@code degrees} into {@code [-180, 180)}, so yaw crossing north stays continuous.
     *
     * <p>Public so unit tests can call it as {@code OrientationData.normalizeDegrees}.
     */
    public static float normalizeDegrees(float degrees) {
        return OrientationTracker.normalizeDegrees(degrees);
    }

    /**
     * The rear camera's basis as a rotation matrix. Delegates to
     * {@link OrientationTracker#cameraBasisMatrix} so tests can call either class.
     */
    public static float[] cameraBasisMatrix(
            float[] deviceToWorld, int displayRotation, float[] out) {
        return OrientationTracker.cameraBasisMatrix(deviceToWorld, displayRotation, out);
    }

    /**
     * Yaw/pitch/roll from a camera basis matrix. Delegates to
     * {@link OrientationTracker#anglesFromCameraBasis}.
     */
    public static float[] anglesFromCameraBasis(float[] basis, float[] out) {
        return OrientationTracker.anglesFromCameraBasis(basis, out);
    }

    /**
     * Yaw/pitch/roll from a device→world matrix. Delegates to
     * {@link OrientationTracker#cameraAnglesDegrees}.
     */
    public static float[] cameraAnglesDegrees(
            float[] deviceToWorld, int displayRotation, float[] out) {
        return OrientationTracker.cameraAnglesDegrees(deviceToWorld, displayRotation, out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrientationData)) {
            return false;
        }
        OrientationData that = (OrientationData) o;
        if (Float.compare(that.yawDegrees, yawDegrees) != 0) {
            return false;
        }
        if (Float.compare(that.pitchDegrees, pitchDegrees) != 0) {
            return false;
        }
        if (Float.compare(that.rollDegrees, rollDegrees) != 0) {
            return false;
        }
        if (timestampNanos != that.timestampNanos) {
            return false;
        }
        if (accuracy != that.accuracy) {
            return false;
        }
        return Arrays.equals(cameraBasis, that.cameraBasis);
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(yawDegrees);
        result = 31 * result + Float.floatToIntBits(pitchDegrees);
        result = 31 * result + Float.floatToIntBits(rollDegrees);
        result = 31 * result + (accuracy != null ? accuracy.hashCode() : 0);
        result = 31 * result + (int) (timestampNanos ^ (timestampNanos >>> 32));
        result = 31 * result + Arrays.hashCode(cameraBasis);
        return result;
    }
}
