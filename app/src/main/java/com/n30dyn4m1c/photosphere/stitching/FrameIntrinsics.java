package com.n30dyn4m1c.photosphere.stitching;

/**
 * The pinhole model of one captured frame.
 *
 * Focal length is kept per axis rather than as one number. Both are derived from
 * the same physical sensor so they ought to agree, but the field of view is an
 * estimate read from optics the camera may describe loosely, and splitting the
 * axes guarantees the frame's full width maps to the full horizontal field of
 * view and its full height to the vertical one. A single focal length would let
 * a small inconsistency between the two show up as a systematic gap or overlap
 * between neighbouring frames.
 */
public final class FrameIntrinsics {
    public final int widthPx;
    public final int heightPx;
    public final double focalXPx;
    public final double focalYPx;
    /**
     * Brown-Conrady radial coefficients {@code [k1, k2, k3]}, already rescaled to this
     * frame's focal length (see {@link RadialDistortion#pixelCoefficientsFor}), or
     * null when the camera does not describe its distortion.
     */
    public final double[] radial;

    public FrameIntrinsics(int widthPx, int heightPx, double focalXPx, double focalYPx) {
        this(widthPx, heightPx, focalXPx, focalYPx, null);
    }

    public FrameIntrinsics(int widthPx, int heightPx, double focalXPx, double focalYPx, double[] radial) {
        if (widthPx <= 0 || heightPx <= 0) {
            throw new IllegalArgumentException("frame must have positive extent");
        }
        if (focalXPx <= 0.0 || focalYPx <= 0.0) {
            throw new IllegalArgumentException("focal length must be positive");
        }
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.focalXPx = focalXPx;
        this.focalYPx = focalYPx;
        this.radial = radial;
    }

    public double getCenterXPx() {
        return widthPx / 2.0;
    }

    public double getCenterYPx() {
        return heightPx / 2.0;
    }

    /**
     * The single focal length the radial model is normalized against.
     *
     * The distortion polynomial takes one scalar {@code r²}, while the projection
     * keeps a focal length per axis. Square pixels make the two agree, so the
     * geometric mean is exact for any real sensor and degrades gracefully if a
     * loosely-described lens makes them differ slightly.
     */
    public double getFocalPx() {
        return Math.sqrt(focalXPx * focalYPx);
    }

    /**
     * The same lens with its focal length multiplied by {@code focalScale}.
     *
     * This is what pose refinement's focal correction hands down to the
     * renderer: the per-frame scale that the feature correspondences said the
     * reported field of view was off by. Both axes are scaled together, because
     * the correction is a single scale on a single physical lens, and the
     * radial coefficients are re-normalised against the *new* focal length —
     * a pixel offset {@code p} is the focal-normalized offset {@code p / f}, so the
     * polynomial in {@code r²} gains a factor {@code 1 / f^(2·order)} per term
     * ({@code k1} by {@code s²}, {@code k2} by {@code s⁴}, {@code k3} by {@code s⁶}). Without that the lens would
     * be described by one focal length and corrected by another, which is
     * exactly the inconsistency the refinement exists to remove.
     */
    public FrameIntrinsics scaledBy(double focalScale) {
        if (focalScale <= 0.0 || Math.abs(focalScale - 1.0) < 1e-6) return this;
        double[] scaledRadial = null;
        if (radial != null) {
            double divisor = focalScale * focalScale;
            scaledRadial = new double[radial.length];
            for (int index = 0; index < radial.length; index++) {
                scaledRadial[index] = radial[index] / divisor;
                divisor *= focalScale * focalScale;
            }
        }
        return new FrameIntrinsics(
            widthPx,
            heightPx,
            focalXPx * focalScale,
            focalYPx * focalScale,
            scaledRadial
        );
    }

    /**
     * Intrinsics of a {@code widthPx} × {@code heightPx} frame covering the given
     * angles, with {@code radial} already in this frame's pixel space.
     *
     * Prefer {@link #forLens} when the coefficients come from the camera — it does
     * the conversion from the unitless polynomial for you, against the
     * focal length these very intrinsics imply.
     */
    public static FrameIntrinsics fromFieldOfView(
        int widthPx,
        int heightPx,
        float horizontalFovDegrees,
        float verticalFovDegrees
    ) {
        return fromFieldOfView(widthPx, heightPx, horizontalFovDegrees, verticalFovDegrees, null);
    }

    public static FrameIntrinsics fromFieldOfView(
        int widthPx,
        int heightPx,
        float horizontalFovDegrees,
        float verticalFovDegrees,
        double[] radial
    ) {
        if (horizontalFovDegrees < 1f || horizontalFovDegrees > 179f) {
            throw new IllegalArgumentException("horizontal fov: " + horizontalFovDegrees);
        }
        if (verticalFovDegrees < 1f || verticalFovDegrees > 179f) {
            throw new IllegalArgumentException("vertical fov: " + verticalFovDegrees);
        }
        return new FrameIntrinsics(
            widthPx,
            heightPx,
            (widthPx / 2.0) / tanHalf(horizontalFovDegrees),
            (heightPx / 2.0) / tanHalf(verticalFovDegrees),
            radial
        );
    }

    /**
     * Intrinsics for a frame shot through {@code distortion}.
     *
     * The lens's unitless coefficients are converted against the focal
     * length this frame implies, which is what makes the conversion correct
     * at whatever size the frame happened to be decoded to.
     */
    public static FrameIntrinsics forLens(
        int widthPx,
        int heightPx,
        float horizontalFovDegrees,
        float verticalFovDegrees,
        RadialDistortion distortion
    ) {
        FrameIntrinsics pinhole = fromFieldOfView(
            widthPx, heightPx, horizontalFovDegrees, verticalFovDegrees
        );
        if (distortion == null) return pinhole;
        double[] radial = distortion.pixelCoefficientsFor(pinhole.getFocalPx());
        if (radial == null) return pinhole;
        return new FrameIntrinsics(
            pinhole.widthPx, pinhole.heightPx, pinhole.focalXPx, pinhole.focalYPx, radial
        );
    }

    private static double tanHalf(float degrees) {
        return Math.tan(Math.toRadians(degrees / 2.0));
    }
}
