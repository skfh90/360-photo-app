package com.n30dyn4m1c.photosphere.camera;

import com.n30dyn4m1c.photosphere.sensor.OrientationData;

/**
 * Maps sphere targets onto the viewfinder.
 *
 * The model is a plain rectilinear (pinhole) camera: a target's direction is
 * rotated out of the world frame into the camera frame, then divided through by
 * its depth. That is the same projection the lens performs, so a marker drawn
 * this way lands on the pixel the target will actually occupy — which is what
 * makes "centre the reticle on the dot" a meaningful instruction.
 *
 * Everything here is pure maths over {@link OrientationData}; there is no Android or
 * Compose dependency, so it is exercised directly by unit tests.
 */
public final class SphereProjection {

    private SphereProjection() {
    }

    /**
     * Focal length, in pixels, of a viewport that shows {@code fieldOfView}.
     *
     * One number covers both axes because the preview is scaled uniformly
     * ({@code FILL_CENTER}). The larger of the two candidates wins: it is the one that
     * keeps <em>both</em> axes inside the available field of view, with the other axis
     * cropped — exactly what filling the viewport does to the frame.
     */
    public static float focalLengthPx(float widthPx, float heightPx, FieldOfView fieldOfView) {
        double horizontal = (widthPx / 2.0) / tanHalf(fieldOfView.getHorizontalDegrees());
        double vertical = (heightPx / 2.0) / tanHalf(fieldOfView.getVerticalDegrees());
        return (float) Math.max(horizontal, vertical);
    }

    /** Where {@code target} sits relative to the camera at {@code orientation}. */
    public static TargetView project(OrientationData orientation, SphereTarget target) {
        return cameraFrame(orientation).project(target);
    }

    /**
     * The camera's axes for one attitude, ready to project many targets through.
     *
     * The overlay redraws the whole plan every frame — hundreds of markers at
     * display rate — and the axes are the same for all of them. Building them
     * once per draw instead of once per marker takes the per-marker cost down to
     * nine multiplies.
     */
    public static CameraFrame cameraFrame(OrientationData orientation) {
        double yaw = Math.toRadians(orientation.getYawDegrees());
        double elevation = Math.toRadians(elevationDegrees(orientation));
        double roll = Math.toRadians(orientation.getRollDegrees());

        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double cosElevation = Math.cos(elevation);

        // Camera axes in the world frame (X east, Y north, Z up).
        // Forward is where the lens points.
        double fx = sinYaw * cosElevation;
        double fy = cosYaw * cosElevation;
        double fz = Math.sin(elevation);

        // Right and up for an unrolled device: "right" is the bearing 90° off
        // the aim and stays horizontal, "up" completes the triple (r × f = u).
        double r0x = cosYaw;
        double r0y = -sinYaw;
        double u0x = r0y * fz;
        double u0y = -r0x * fz;
        double u0z = r0x * fy - r0y * fx;

        // Roll turns the pair about the forward axis. Both are perpendicular to
        // it, so Rodrigues collapses to a plain 2D rotation in their plane —
        // f × r = -u and f × u = r.
        double cosRoll = Math.cos(roll);
        double sinRoll = Math.sin(roll);
        return new CameraFrame(
                fx,
                fy,
                fz,
                r0x * cosRoll - u0x * sinRoll,
                r0y * cosRoll - u0y * sinRoll,
                -u0z * sinRoll,
                u0x * cosRoll + r0x * sinRoll,
                u0y * cosRoll + r0y * sinRoll,
                u0z * cosRoll
        );
    }

    /**
     * Angle between where the camera points and {@code target}, in degrees.
     *
     * This is the number the capture trigger thresholds on. Roll is deliberately
     * ignored: turning the phone in its own plane does not change what the lens
     * is aimed at, and demanding a level device would make the sphere far more
     * tedious to shoot than it needs to be.
     */
    public static float angularDistanceDegrees(OrientationData orientation, SphereTarget target) {
        return project(orientation, target).getAngularDistanceDegrees();
    }

    /** Height above the horizon, positive up. See {@link SphereTarget#getElevationDegrees()}. */
    public static float elevationDegrees(OrientationData orientation) {
        return -orientation.getPitchDegrees();
    }

    private static double tanHalf(float degrees) {
        return Math.tan(Math.toRadians(degrees / 2.0));
    }
}
