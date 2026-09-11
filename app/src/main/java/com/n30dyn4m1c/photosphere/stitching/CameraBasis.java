package com.n30dyn4m1c.photosphere.stitching;

/**
 * The camera's axes expressed in the world frame (X east, Y north, Z up).
 *
 * This is the same triple {@code SphereProjection} builds to place markers on the
 * viewfinder, kept in its own type here because the stitcher needs it per frame
 * and evaluates it against millions of directions — the components are held as
 * flat doubles so the projection loop allocates nothing.
 *
 * The three vectors are orthonormal and left-handed: {@code up = right × forward},
 * so {@code right × up = −forward}.
 */
public final class CameraBasis {
    public final double forwardX;
    public final double forwardY;
    public final double forwardZ;
    public final double rightX;
    public final double rightY;
    public final double rightZ;
    public final double upX;
    public final double upY;
    public final double upZ;

    private CameraBasis(
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

    /** Component of a world direction along the optical axis — its depth. */
    public double depthOf(double x, double y, double z) {
        return x * forwardX + y * forwardY + z * forwardZ;
    }

    /** Component across the frame, positive to the right of the image. */
    public double lateralOf(double x, double y, double z) {
        return x * rightX + y * rightY + z * rightZ;
    }

    /** Component up the frame, positive towards the top of the image. */
    public double verticalOf(double x, double y, double z) {
        return x * upX + y * upY + z * upZ;
    }

    /**
     * Turns a direction in the camera's own frame back into a world direction.
     *
     * The inverse of the three projections above. Used to walk a frame's border
     * out onto the sphere when working out which part of the canvas it covers.
     */
    public double[] toWorld(double cameraX, double cameraY, double cameraZ) {
        return new double[] {
            cameraX * rightX + cameraY * upX + cameraZ * forwardX,
            cameraX * rightY + cameraY * upY + cameraZ * forwardY,
            cameraX * rightZ + cameraY * upZ + cameraZ * forwardZ,
        };
    }

    /**
     * This basis as a row-major 3×3 rotation matrix.
     *
     * The camera's own (right, up, forward) triple is left-handed — {@code up} is
     * built as {@code right × forward} — so those columns would form a reflection,
     * and the rotation algebra downstream (quaternion means, pose refinement)
     * assumes proper rotations: an improper matrix smuggles a 90° rotation
     * through the quaternion conversion. The exchange format therefore mirrors
     * the up axis: the columns are {@code [right, −up, forward]}, the right-handed
     * counterpart of the camera frame. {@link #fromRotationMatrix} is the exact
     * inverse, and the sensor layer writes the same layout in
     * {@code cameraBasisMatrix}.
     */
    public double[] toRotationMatrix() {
        return new double[] {
            rightX, -upX, forwardX,
            rightY, -upY, forwardY,
            rightZ, -upZ, forwardZ,
        };
    }

    /** The yaw/pitch/roll this basis was built from. */
    public CameraPose toPose() {
        float yaw = (float) Math.toDegrees(Math.atan2(forwardX, forwardY));
        float elevation = (float) Math.toDegrees(Math.asin(clamp(forwardZ, -1.0, 1.0)));
        // Rebuild the unrolled pair for this yaw/elevation and measure how far
        // the actual up/right pair has rolled about forward.
        double yawR = Math.toRadians((double) yaw);
        double elevationR = Math.toRadians((double) elevation);
        double sinYaw = Math.sin(yawR);
        double cosYaw = Math.cos(yawR);
        double sinElevation = Math.sin(elevationR);
        double up0X = -sinYaw * sinElevation;
        double up0Y = -cosYaw * sinElevation;
        double up0Z = Math.cos(elevationR);
        float roll = (float) Math.toDegrees(
            Math.atan2(
                -(rightX * up0X + rightY * up0Y + rightZ * up0Z),
                upX * up0X + upY * up0Y + upZ * up0Z
            )
        );
        return new CameraPose(yaw, -elevation, roll);
    }

    /**
     * Builds the basis for {@code pose}.
     *
     * Forward is where the lens points. "Right" is the bearing a quarter
     * turn clockwise from the aim and stays horizontal for an unrolled
     * device; "up" completes the right-handed triple. Roll then turns that
     * pair about the forward axis — both are perpendicular to it, so
     * Rodrigues' formula collapses to a plain rotation within their plane.
     */
    public static CameraBasis of(CameraPose pose) {
        // A measured basis is exact where the angles are not: the sensor
        // averaged the dwell as a rotation, and at the zenith the angles
        // alone cannot say which way is up in the frame. It wins when
        // present.
        if (pose.matrix != null) return fromRotationMatrix(pose.matrix);

        double yaw = Math.toRadians((double) pose.yawDegrees);
        double elevation = Math.toRadians((double) pose.getElevationDegrees());
        double roll = Math.toRadians((double) pose.rollDegrees);

        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double cosElevation = Math.cos(elevation);

        double forwardX = sinYaw * cosElevation;
        double forwardY = cosYaw * cosElevation;
        double forwardZ = Math.sin(elevation);

        double right0X = cosYaw;
        double right0Y = -sinYaw;
        // right0 × forward, with right0.z == 0 folded through.
        double up0X = right0Y * forwardZ;
        double up0Y = -right0X * forwardZ;
        double up0Z = right0X * forwardY - right0Y * forwardX;

        double cosRoll = Math.cos(roll);
        double sinRoll = Math.sin(roll);
        return new CameraBasis(
            forwardX,
            forwardY,
            forwardZ,
            right0X * cosRoll - up0X * sinRoll,
            right0Y * cosRoll - up0Y * sinRoll,
            -up0Z * sinRoll,
            up0X * cosRoll + right0X * sinRoll,
            up0Y * cosRoll + right0Y * sinRoll,
            up0Z * cosRoll
        );
    }

    /**
     * Builds the basis a row-major rotation matrix describes — the inverse
     * of {@link #toRotationMatrix()}. {@code matrix} is expected to be orthonormal and
     * right-handed, as the refinement pipeline produces.
     *
     * The vectors live in the matrix's *columns*, matching {@link #toRotationMatrix()}:
     * the storage is {@code [right, −up, forward]} laid out one component-row at a
     * time, so a column is read at stride 3 — and the up column is mirrored
     * back to the camera's own left-handed triple. Reading the rows instead
     * would transpose the basis — which for a level frame collapses every
     * forward onto due north and stacks all the frames of a capture on one
     * longitude.
     */
    public static CameraBasis fromRotationMatrix(double[] matrix) {
        return new CameraBasis(
            matrix[2],
            matrix[5],
            matrix[8],
            matrix[0],
            matrix[3],
            matrix[6],
            -matrix[1],
            -matrix[4],
            -matrix[7]
        );
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
