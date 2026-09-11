package com.n30dyn4m1c.photosphere.camera;

/**
 * The camera's orthonormal axes in the world frame (X east, Y north, Z up).
 *
 * Built once per attitude by {@link SphereProjection#cameraFrame}; the components are
 * held as flat doubles so projecting a target allocates nothing beyond the
 * result.
 */
public final class CameraFrame {

    private final double forwardX;
    private final double forwardY;
    private final double forwardZ;
    private final double rightX;
    private final double rightY;
    private final double rightZ;
    private final double upX;
    private final double upY;
    private final double upZ;

    CameraFrame(
            double forwardX,
            double forwardY,
            double forwardZ,
            double rightX,
            double rightY,
            double rightZ,
            double upX,
            double upY,
            double upZ
    ) {
        this.forwardX = forwardX;
        this.forwardY = forwardY;
        this.forwardZ = forwardZ;
        this.rightX = rightX;
        this.rightY = rightY;
        this.rightZ = rightZ;
        this.upX = upX;
        this.upY = upY;
        this.upZ = upZ;
    }

    /** Where {@code target} sits relative to this camera. */
    public TargetView project(SphereTarget target) {
        double dx = target.getDirectionX();
        double dy = target.getDirectionY();
        double dz = target.getDirectionZ();
        double z = dx * forwardX + dy * forwardY + dz * forwardZ;
        return new TargetView(
                (float) (dx * rightX + dy * rightY + dz * rightZ),
                (float) (dx * upX + dy * upY + dz * upZ),
                (float) z,
                (float) Math.toDegrees(Math.acos(coerceIn(z, -1.0, 1.0)))
        );
    }

    private static double coerceIn(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
