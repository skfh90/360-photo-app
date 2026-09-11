package com.n30dyn4m1c.photosphere.stitching;

import org.junit.Assert;
import org.junit.Test;

/**
 * The radial lens model that keeps frame edges honest.
 */
public class LensModelTest {

    // Realistic phone-lens barrel distortion, in the *normalized* space
    // `CameraCharacteristics.LENS_DISTORTION` uses: coordinates divided by the
    // focal length, exactly as OpenCV does it. A k1 of −0.02 pulls a ray 30°
    // off the axis in by roughly 0.7%.
    private final double[] unitlessCoefficients = new double[] {-0.02, 1.0e-3, -5.0e-5};

    private final RadialDistortion lens = new RadialDistortion(unitlessCoefficients);

    /** A 1024×768 working frame across 66°, which is what the stitch decodes to. */
    private final FrameIntrinsics workingFrame = FrameIntrinsics.forLens(
            1024,
            768,
            66f,
            52f,
            lens);

    private final double[] smallCoefficients = workingFrame.radial;

    private final double centreX = workingFrame.getCenterXPx();
    private final double centreY = workingFrame.getCenterYPx();

    @Test
    public void theOpticalCentreIsUnmovedByDistortion() {
        double[] distorted = LensModel.distortPixel(centreX, centreY, centreX, centreY, smallCoefficients);
        Assert.assertEquals(centreX, distorted[0], 1e-9);
        Assert.assertEquals(centreY, distorted[1], 1e-9);
    }

    @Test
    public void barrelDistortionPullsTheFrameEdgeTowardsTheCentre() {
        // An ideal pixel near the corner should land closer to the centre once
        // the negative k1 pushes it in, in proportion to its distance from it.
        double[] distorted = LensModel.distortPixel(900.0, 700.0, centreX, centreY, smallCoefficients);
        double xOffset = 900.0 - centreX;
        double yOffset = 700.0 - centreY;
        double xShrink = xOffset - (distorted[0] - centreX);
        double yShrink = yOffset - (distorted[1] - centreY);
        Assert.assertTrue("x should shrink", xShrink > 0.0);
        Assert.assertTrue("y should shrink", yShrink > 0.0);
        // The pull is proportional to the offset from the optical centre.
        Assert.assertEquals(xShrink / yShrink, xOffset / yOffset, 1e-9);
    }

    @Test
    public void undistortIsTheInverseOfDistort() {
        double[][] points = new double[][] {
                {512.0, 384.0},
                {900.0, 700.0},
                {100.0, 50.0},
                {811.0, 24.0},
                {64.0, 740.0}
        };
        for (int i = 0; i < points.length; i++) {
            double column = points[i][0];
            double row = points[i][1];
            double[] distorted = LensModel.distortPixel(column, row, centreX, centreY, smallCoefficients);
            double[] recovered = LensModel.undistortPixel(
                    distorted[0], distorted[1], centreX, centreY, smallCoefficients);
            Assert.assertEquals("column " + column, column, recovered[0], 1e-3);
            Assert.assertEquals("row " + row, row, recovered[1], 1e-3);
        }
    }

    @Test
    public void noCoefficientsMeansNoMovement() {
        double[] pinhole = LensModel.distortPixel(
                900.0, 700.0, centreX, centreY, new double[] {0.0, 0.0, 0.0});
        Assert.assertEquals(900.0, pinhole[0], 1e-12);
        Assert.assertEquals(700.0, pinhole[1], 1e-12);
    }

    @Test
    public void pixelCoefficientsDivideByTheFocalLengthNotTheFrameEdge() {
        // This is the whole of the LENS_DISTORTION contract: `x_i = (x - c_x)/f`,
        // so a term of order 2n divides by f^(2n). Getting this wrong by using
        // the deprecated LENS_RADIAL_DISTORTION's "array edge = ±1" convention
        // overstates k1 by (f / halfEdge)^2 — more than double on a normal lens
        // — and k3 by the sixth power of the same.
        double focal = 1234.5;
        double[] effective = lens.pixelCoefficientsFor(focal);
        Assert.assertNotNull(effective);
        Assert.assertEquals(unitlessCoefficients[0] / Math.pow(focal, 2.0), effective[0], 1e-18);
        Assert.assertEquals(unitlessCoefficients[1] / Math.pow(focal, 4.0), effective[1], 1e-24);
        Assert.assertEquals(unitlessCoefficients[2] / Math.pow(focal, 6.0), effective[2], 1e-30);
    }

    @Test
    public void theWorkingFrameIsNormalizedAgainstItsOwnFocalLength() {
        // 1024 px across 66° is a focal length of 1024/2/tan(33°) ≈ 788 px. The
        // vertical axis lands within a pixel of the same number, which is what
        // "square pixels" means and why one focal length can carry the radial
        // polynomial for both.
        double expectedFocal = 512.0 / Math.tan(Math.toRadians(33.0));
        Assert.assertEquals(expectedFocal, workingFrame.focalXPx, 1e-9);
        Assert.assertEquals(expectedFocal, workingFrame.getFocalPx(), 1.0);

        double focal = workingFrame.getFocalPx();
        Assert.assertEquals(unitlessCoefficients[0] / (focal * focal), smallCoefficients[0], 1e-18);
    }

    @Test
    public void aDistortionThisCameraDidNotReportIsNothingToCorrect() {
        Assert.assertNull(new RadialDistortion(new double[] {}).pixelCoefficientsFor(1000.0));
        Assert.assertNull(new RadialDistortion(new double[] {-1.0, 0.0}).pixelCoefficientsFor(1000.0));
        Assert.assertNull(lens.pixelCoefficientsFor(0.0));
        Assert.assertNull(lens.pixelCoefficientsFor(Double.NaN));
    }

    @Test
    public void anAllZeroCalibrationIsAPinholeAndIsSkippedEntirely() {
        RadialDistortion pinhole = new RadialDistortion(new double[] {0.0, 0.0, 0.0});
        Assert.assertFalse(pinhole.isSignificant());
        Assert.assertNull(pinhole.pixelCoefficientsFor(1500.0));
        // And a frame built for it carries no polynomial into the inner loop.
        Assert.assertNull(FrameIntrinsics.forLens(1024, 768, 66f, 52f, pinhole).radial);
    }

    @Test
    public void scalingTheFocalLengthReNormalisesTheRadialCoefficients() {
        // A focal correction of s means the frame really spans s× the focal
        // length it was described with, so the pixel-space polynomial has to be
        // re-derived against the new focal: each term divides by s^(2·order).
        double scale = 1.2;
        FrameIntrinsics scaled = workingFrame.scaledBy(scale);
        Assert.assertEquals(workingFrame.focalXPx * scale, scaled.focalXPx, 1e-9);
        Assert.assertEquals(workingFrame.focalYPx * scale, scaled.focalYPx, 1e-9);

        // Scaling is a lens change, so the scaled frame must be identical to
        // re-deriving the same lens at the new focal length from the unitless
        // coefficients — that is the whole point of carrying them through.
        double[] scaledRadial = scaled.radial;
        double[] reDerived = lens.pixelCoefficientsFor(workingFrame.getFocalPx() * scale);
        Assert.assertNotNull(scaledRadial);
        Assert.assertNotNull(reDerived);
        Assert.assertEquals(reDerived[0], scaledRadial[0], 1e-15);
        Assert.assertEquals(reDerived[1], scaledRadial[1], 1e-20);
        Assert.assertEquals(reDerived[2], scaledRadial[2], 1e-25);
    }

    @Test
    public void aUnitFocalScaleIsTheIdentity() {
        FrameIntrinsics same = workingFrame.scaledBy(1.0);
        Assert.assertTrue("scaledBy(1.0) should hand back the same instance", same == workingFrame);
    }

    @Test
    public void theTwoResolutionsDescribeTheSameLens() {
        // Two decodes of one capture: the same physical ray must land on the
        // same *fraction* of the frame however coarsely it was decoded, which is
        // what makes the correction independent of the subsampling factor.
        FrameIntrinsics full = FrameIntrinsics.forLens(4000, 3000, 66f, 52f, lens);
        FrameIntrinsics small = FrameIntrinsics.forLens(1000, 750, 66f, 52f, lens);

        double atFull = LensModel.distortPixel(
                4000.0, full.getCenterYPx(), full.getCenterXPx(), full.getCenterYPx(), full.radial)[0];
        double atSmall = LensModel.distortPixel(
                1000.0, small.getCenterYPx(), small.getCenterXPx(), small.getCenterYPx(), small.radial)[0];
        Assert.assertEquals(atFull / 4.0, atSmall, 1e-6);
    }

    @Test
    public void aRealisticLensMovesAFrameCornerByABelievableAmount() {
        // The guard against a normalization that is off by a power: a phone
        // lens's own corner should move by a couple of percent, not by a third
        // of the frame (which pushes every source pixel out of bounds and makes
        // the stitch cover nothing) and not by a hundredth of a pixel.
        double[] corner = LensModel.distortPixel(
                workingFrame.widthPx - 1.0,
                workingFrame.heightPx - 1.0,
                centreX,
                centreY,
                smallCoefficients);
        double before = Math.hypot(workingFrame.widthPx - 1.0 - centreX, workingFrame.heightPx - 1.0 - centreY);
        double after = Math.hypot(corner[0] - centreX, corner[1] - centreY);
        double shift = (before - after) / before;
        Assert.assertTrue(
                "corner moved by " + (shift * 100) + "%, expected 0.1%..10%",
                shift >= 0.001 && shift <= 0.10);
    }
}
