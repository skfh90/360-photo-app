package com.n30dyn4m1c.photosphere.stitching;

/**
 * Package-level spherical helpers from {@code SphericalGeometry.kt}.
 *
 * Kotlin {@code internal} functions become public static methods here so tests
 * and the rest of the pipeline can call them.
 */
public final class SphericalGeometry {

    /**
     * Directions closer to the image plane than this count as behind the camera:
     * their projection races off to infinity and is useless as a pixel position.
     */
    public static final double MIN_DEPTH = 1e-6;

    private SphericalGeometry() {}

    /**
     * Blend weight for a pixel that landed at ({@code idealColumn}, {@code idealRow}) in a
     * frame, 0 at the border and 1 on the optical axis.
     *
     * This is the feather that turns an overlap into a cross-fade. It is measured
     * against the *ideal* (pinhole) pixel rather than the distorted one because the
     * ideal offset is proportional to the angle off the optical axis, which is what
     * "how squarely did this frame see that direction" actually means.
     *
     * Both factors are clamped at zero before they meet: radial distortion can pull
     * a pixel whose ideal position is outside the frame back inside the distorted
     * bounds, and two negative factors would otherwise multiply into a *positive*
     * weight for a direction the frame never really saw. Raised to a power so the
     * frame that sees a shared direction most head-on wins the blend rather than
     * smearing it across a wide linear cross-fade.
     */
    public static double featherWeight(
        double idealColumn,
        double idealRow,
        double centreX,
        double centreY
    ) {
        double across = 1.0 - Math.abs(idealColumn - centreX) / centreX;
        if (across <= 0.0) return 0.0;
        double down = 1.0 - Math.abs(idealRow - centreY) / centreY;
        if (down <= 0.0) return 0.0;
        double linear = across * down;
        // Cubed by hand: this runs once per canvas pixel per frame, where a
        // `Math.pow` call is several times the cost of two multiplies.
        return linear * linear * linear;
    }

    /**
     * The direction *from the pivot* to the scene point a camera ray runs into.
     *
     * {@code x}, {@code y}, {@code z} are a unit world direction leaving the lens, and the lens sits
     * at {@code pivotRatio × forward} on a unit sphere of scene (see {@link PivotModel}). The
     * scene point is where that ray meets the sphere, and what comes back is the
     * unit direction to it from the pivot — the frame the canvas is indexed in.
     *
     * With {@code pivotRatio} at zero the lens *is* the pivot and the direction is handed
     * straight back.
     */
    public static double[] pivotDirection(
        CameraBasis basis,
        double pivotRatio,
        double x,
        double y,
        double z
    ) {
        if (pivotRatio <= 0.0) return new double[] {x, y, z};

        // |k·f + t·d| = 1 with d a unit ray, solved for the positive root. The
        // discriminant cannot go negative for k < 1, but the floor costs nothing.
        double alongAxis = basis.depthOf(x, y, z);
        double half = pivotRatio * alongAxis;
        double distance = -half + Math.sqrt(Math.max(half * half - pivotRatio * pivotRatio + 1.0, 0.0));

        double px = pivotRatio * basis.forwardX + distance * x;
        double py = pivotRatio * basis.forwardY + distance * y;
        double pz = pivotRatio * basis.forwardZ + distance * z;
        double length = Math.sqrt(px * px + py * py + pz * pz);
        if (length <= 0.0) return new double[] {x, y, z};
        return new double[] {px / length, py / length, pz / length};
    }

    /**
     * The pixel of a frame that looked in a given world direction, or null when the
     * direction falls outside the frame or behind the camera.
     *
     * This is the whole of the camera model in five lines: rotate into the camera's
     * axes, divide through by depth, scale by the focal length, then push the ideal
     * pixel through the lens's radial distortion to find where it was captured. The
     * renderer inlines it over millions of pixels rather than calling it, so any
     * change here has to be made in both places — which is why the footprint code
     * and the tests share this one, keeping an independent statement of the same
     * geometry.
     *
     * {@code x}, {@code y}, {@code z} is a direction from the *pivot*, which is the lens only when
     * {@code pivotRatio} is zero. Everywhere else the lens sits {@code pivotRatio} of the way
     * out towards the scene along its own optical axis, and correcting for that
     * costs exactly one subtraction: the lever arm is parallel to the forward axis,
     * so it cancels out of the lateral and vertical components and shortens the
     * depth alone. The direction must be a *unit* vector for that to hold.
     *
     * The returned array is {@code [column, row]} in the frame's pixel coordinates, where
     * rows increase downwards while the camera's "up" axis points the other way.
     */
    public static double[] projectDirection(
        CameraBasis basis,
        FrameIntrinsics intrinsics,
        double x,
        double y,
        double z
    ) {
        return projectDirection(basis, intrinsics, x, y, z, 0.0);
    }

    public static double[] projectDirection(
        CameraBasis basis,
        FrameIntrinsics intrinsics,
        double x,
        double y,
        double z,
        double pivotRatio
    ) {
        double depth = basis.depthOf(x, y, z) - pivotRatio;
        if (depth <= MIN_DEPTH) return null;
        double centreX = intrinsics.getCenterXPx();
        double centreY = intrinsics.getCenterYPx();
        double idealColumn = centreX + intrinsics.focalXPx * basis.lateralOf(x, y, z) / depth;
        double idealRow = centreY - intrinsics.focalYPx * basis.verticalOf(x, y, z) / depth;

        double[] radial = intrinsics.radial;
        double column;
        double row;
        if (radial != null) {
            double[] distorted = LensModel.distortPixel(idealColumn, idealRow, centreX, centreY, radial);
            column = distorted[0];
            row = distorted[1];
        } else {
            column = idealColumn;
            row = idealRow;
        }
        if (column < 0.0 || column > intrinsics.widthPx - 1.0) return null;
        if (row < 0.0 || row > intrinsics.heightPx - 1.0) return null;
        return new double[] {column, row};
    }

    /** Shifts {@code degrees} by whole turns until it lands within 180° of {@code reference}. */
    public static double unwrapNear(double degrees, double reference) {
        double result = degrees;
        while (result - reference > 180.0) result -= 360.0;
        while (result - reference < -180.0) result += 360.0;
        return result;
    }

    /** Wraps a canvas column onto {@code 0..width-1}, for footprints that cross the seam. */
    public static int wrapColumn(int column, int width) {
        int remainder = column % width;
        return remainder < 0 ? remainder + width : remainder;
    }

    /**
     * How much two aims overlap, or null when they do not.
     *
     * The second camera's aim is projected into the first's camera frame and must
     * land within one field of view on both axes — the condition for two
     * axis-aligned field-of-view windows to intersect, since two frames each
     * reaching half a field of view either side of their aim meet exactly when
     * their aims are less than one full field of view apart. The returned value is
     * the squared angular separation (tangent-space), so a smaller value is a
     * stronger shared view, which is how the pose graph orders its edges.
     */
    public static Double angularOverlap(
        CameraBasis a,
        CameraBasis b,
        float horizontalFovDegrees,
        float verticalFovDegrees
    ) {
        double depth = a.depthOf(b.forwardX, b.forwardY, b.forwardZ);
        if (depth <= MIN_DEPTH) return null;
        double horizontalSeparation = Math.abs(a.lateralOf(b.forwardX, b.forwardY, b.forwardZ) / depth);
        double verticalSeparation = Math.abs(a.verticalOf(b.forwardX, b.forwardY, b.forwardZ) / depth);
        if (horizontalSeparation >= tanOf(horizontalFovDegrees)) return null;
        if (verticalSeparation >= tanOf(verticalFovDegrees)) return null;
        return horizontalSeparation * horizontalSeparation + verticalSeparation * verticalSeparation;
    }

    /**
     * {@code tan(degrees)}, saturating at a right angle.
     *
     * A field of view of 90° or more spans the whole half-space in that axis, where
     * the tangent flips sign and would reject every pair; the overlap test wants
     * "no bound at all" instead.
     */
    private static double tanOf(float degrees) {
        return degrees >= 90f ? Double.MAX_VALUE : Math.tan(Math.toRadians((double) degrees));
    }
}
