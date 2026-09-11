package com.n30dyn4m1c.photosphere.sensor;

import android.hardware.SensorManager;

/** {@code SensorEvent.accuracy} as something readable. */
public enum OrientationAccuracy {
    /** No sample yet, or the sensor never reported its accuracy. */
    Unknown,

    /** Readings cannot be trusted at all — usually a magnetometer that needs a figure-eight. */
    Unreliable,
    Low,
    Medium,
    High;

    /**
     * Medium is the lowest accuracy whose <em>absolute</em> bearing is worth trusting
     * — the reading that would make a sphere's compass heading meaningful.
     */
    public boolean isUsable() {
        return this == Medium || this == High;
    }

    /**
     * Whether guided capture should fire the shutter at this accuracy.
     *
     * <p>Deliberately weaker than {@link #isUsable}. What the capture loop needs is that
     * the aim be <em>consistent between frames</em>, and that comes from the gyroscope
     * half of the fused sensor: the plan is anchored on whatever bearing the
     * user was facing when the first fix landed, so a magnetometer that is
     * merely uncalibrated shifts every target together and costs the sphere
     * nothing. Only {@link #Unreliable} — the state where the fusion says its own
     * output is not to be believed — is worth refusing to shoot at.
     *
     * <p>Blocking on {@link #isUsable} instead is a dead end the user cannot see their
     * way out of: indoors, near steel, a phone can sit at {@link #Low} indefinitely
     * while the reticle tracks the scene perfectly and the shutter never fires.
     */
    public boolean allowsCapture() {
        return this != Unreliable;
    }

    public static OrientationAccuracy fromSensorAccuracy(int accuracy) {
        switch (accuracy) {
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                return High;
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
                return Medium;
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                return Low;
            case SensorManager.SENSOR_STATUS_UNRELIABLE:
                return Unreliable;
            default:
                return Unknown;
        }
    }
}
