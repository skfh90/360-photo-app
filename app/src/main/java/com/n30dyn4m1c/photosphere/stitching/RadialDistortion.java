package com.n30dyn4m1c.photosphere.stitching;

import java.util.Arrays;

/**
 * Brown-Conrady radial lens distortion, in the convention the camera reports it.
 *
 * The camera2 API describes a lens's distortion with the coefficients that map
 * an <em>undistorted</em> (ideal pinhole) image coordinate to the <em>distorted</em>
 * coordinate that must be sampled in the captured frame
 * ({@code CameraCharacteristics.LENS_DISTORTION}):
 *
 * <pre>
 * x_c = x_i * (1 + k1 r^2 + k2 r^4 + k3 r^6)
 * y_c = y_i * (1 + k1 r^2 + k2 r^4 + k3 r^6)
 * </pre>
 *
 * with {@code r^2 = x_i^2 + y_i^2}. The coefficients are <b>unitless</b> and the
 * coordinates they act on are normalized <em>by the focal length</em>: the origin is
 * the optical centre and {@code x_i = (column − c_x) / f_x}, with {@code f_x}/{@code c_x} taken
 * from {@code LENS_INTRINSIC_CALIBRATION}. This is the OpenCV convention, and it is
 * the whole difference between {@code LENS_DISTORTION} and the {@code LENS_RADIAL_DISTORTION}
 * key it replaced — the older key normalized so that the <em>farthest array edge</em>
 * sat at ±1, which Android itself describes as "inconsistently defined". The two
 * differ by {@code (f / halfEdge)^(2n)} per term, a factor of well over two on {@code k1}
 * for a typical phone lens and more than ten on {@code k3}, so reading one key through
 * the other's normalization does not merely blur the correction — it multiplies
 * it.
 *
 * The tangential terms of the full model are dropped: they are tiny on phone
 * lenses, and the radial terms carry almost all of the seam error at frame
 * borders.
 *
 * Every pipeline stage applies the same model in the same pixel space as the
 * frame it is looking at — the renderer samples through it, the footprint walks
 * the distorted border, and pose refinement undistorts keypoints — so a
 * direction, its ideal pixel and its distorted pixel always agree.
 * {@link #pixelCoefficientsFor} is what converts the unitless polynomial into that
 * pixel space, and because the conversion is driven by the focal length <em>of the
 * frame being worked on</em>, it is automatically right for a frame decoded at any
 * subsampling factor.
 */
public final class RadialDistortion {

    /** Unitless {@code [k1, k2, k3]}, applied to focal-length-normalized coordinates. */
    public final double[] coefficients;

    public RadialDistortion(double[] coefficients) {
        this.coefficients = coefficients;
    }

    public double[] getCoefficients() {
        return coefficients;
    }

    /**
     * True when the coefficients are worth applying at all.
     *
     * A camera that reports all-zero distortion is a pinhole, and carrying an
     * all-zero polynomial through the renderer's inner loop costs three
     * multiplies per pixel for nothing.
     */
    public boolean isSignificant() {
        if (coefficients.length < 3) {
            return false;
        }
        for (int i = 0; i < coefficients.length; i++) {
            if (Math.abs(coefficients[i]) > 0.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The same distortion re-expressed for a frame whose focal length is
     * {@code focalLengthPx} pixels.
     *
     * A pixel offset {@code p} is the normalized offset {@code p / f}, so the polynomial in
     * normalized {@code r²} becomes one in pixel {@code r²} with each term divided by
     * {@code f^(2·order)}: k1 by f², k2 by f⁴, k3 by f⁶. The result is what
     * {@link LensModel#distortPixel} and {@link LensModel#undistortPixel} expect: coefficients
     * that take {@code r²} in pixels.
     *
     * Returns null when there is nothing to correct, which is the signal the
     * rest of the pipeline reads as "treat this lens as a pinhole".
     */
    public double[] pixelCoefficientsFor(double focalLengthPx) {
        if (!isSignificant() || !Double.isFinite(focalLengthPx) || focalLengthPx <= 0.0) {
            return null;
        }
        double f2 = focalLengthPx * focalLengthPx;
        double f4 = f2 * f2;
        double f6 = f4 * f2;
        return new double[] {
                coefficients[0] / f2,
                coefficients[1] / f4,
                coefficients[2] / f6
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RadialDistortion)) return false;
        RadialDistortion that = (RadialDistortion) o;
        return Arrays.equals(coefficients, that.coefficients);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(coefficients);
    }
}
