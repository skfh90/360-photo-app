package com.n30dyn4m1c.photosphere.stitching;

/**
 * The radial scale factor applied to an offset {@code r^2} from the optical axis.
 *
 * {@code 1 + k1 r^2 + k2 r^4 + k3 r^6}, folded as {@code r^2 (k1 + r^2 (k2 + r^2 k3))}.
 * Kept in one place because the renderer inlines it over millions of pixels and
 * the geometry helpers call it for footprints and keypoints — both must use the
 * identical polynomial.
 */
public final class LensModel {

    /** Iterations {@link #undistortPixel} is allowed before it settles for what it has. */
    private static final int UNDISTORT_ITERATIONS = 12;

    /** Pixel movement below which the fixed point is close enough to stop. */
    private static final double UNDISTORT_TOLERANCE_PX = 1e-4;

    private LensModel() {
    }

    public static double radialFactor(double[] coefficients, double r2) {
        return 1.0 + r2 * (coefficients[0] + r2 * (coefficients[1] + r2 * coefficients[2]));
    }

    /**
     * The distorted source pixel for an ideal pixel at ({@code column}, {@code row}).
     *
     * The forward (ideal → distorted) direction of the model above: what the lens
     * turns the pinhole projection of a ray into.
     */
    public static double[] distortPixel(
            double column,
            double row,
            double centreX,
            double centreY,
            double[] coefficients
    ) {
        double x = column - centreX;
        double y = row - centreY;
        double factor = radialFactor(coefficients, x * x + y * y);
        return new double[] {centreX + x * factor, centreY + y * factor};
    }

    /**
     * The ideal (undistorted) pixel whose {@code column}/{@code row} the lens captured.
     *
     * The inverse of {@link #distortPixel}. There is no closed form, so it is inverted
     * with fixed-point iteration: {@code x_{n+1} = x_d / factor(x_n^2 + y_n^2)} converges
     * quickly because the factor is close to 1 for the modest distortion a phone
     * lens exhibits. The loop stops as soon as the estimate stops moving, which is
     * usually within three passes and matters because pose refinement calls this
     * once per keypoint.
     */
    public static double[] undistortPixel(
            double column,
            double row,
            double centreX,
            double centreY,
            double[] coefficients
    ) {
        double targetX = column - centreX;
        double targetY = row - centreY;
        double x = targetX;
        double y = targetY;
        for (int i = 0; i < UNDISTORT_ITERATIONS; i++) {
            double factor = radialFactor(coefficients, x * x + y * y);
            // A factor at or below zero means the polynomial has folded the frame
            // over on itself, which no phone lens does inside its own image circle.
            // Bail to the last good estimate rather than diverging.
            if (factor <= 0.0) {
                return new double[] {centreX + x, centreY + y};
            }
            double nextX = targetX / factor;
            double nextY = targetY / factor;
            boolean settled = Math.abs(nextX - x) < UNDISTORT_TOLERANCE_PX
                    && Math.abs(nextY - y) < UNDISTORT_TOLERANCE_PX;
            x = nextX;
            y = nextY;
            if (settled) {
                return new double[] {centreX + x, centreY + y};
            }
        }
        return new double[] {centreX + x, centreY + y};
    }
}
