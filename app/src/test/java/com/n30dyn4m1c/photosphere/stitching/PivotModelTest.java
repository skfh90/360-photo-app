package com.n30dyn4m1c.photosphere.stitching;

import org.junit.Assert;
import org.junit.Test;

/**
 * The correction for turning the phone around yourself instead of around the
 * lens.
 *
 * A hand-held sphere is shot by swivelling on the spot, which puts the camera
 * on the end of a lever a third of a metre long rather than on the axis. That
 * is translation between frames, and translation is what a panorama is not
 * allowed to have — so the pipeline models the lever explicitly and refers
 * everything back to the axis the user actually turned about.
 */
public class PivotModelTest {

    private static final double TOLERANCE = 1e-6;

    /** Level, facing north. */
    private final CameraBasis basis = CameraBasis.of(new CameraPose(0f, 0f, 0f));

    private final FrameIntrinsics intrinsics = FrameIntrinsics.fromFieldOfView(
            1000,
            1000,
            60f,
            60f);

    @Test
    public void onlyTheRatioOfTheTwoLengthsSurvives() {
        // Half a metre at twenty is the same geometry as a metre at forty.
        Assert.assertEquals(
                new PivotModel(0.5f, 20f).ratio,
                new PivotModel(1f, 40f).ratio,
                TOLERANCE);
        Assert.assertEquals(0.035, PivotModel.HandheldBodySwivel.ratio, 1e-4);
    }

    @Test
    public void aLensOnTheAxisIsNoCorrectionAtAll() {
        Assert.assertEquals(0.0, PivotModel.None.ratio, TOLERANCE);
    }

    @Test
    public void aSceneCloserThanTheCorrectionCanDescribeIsClamped() {
        // Arm's length from the wall: no projection saves this, and pretending
        // otherwise would bend the frames further apart than parallax bent them
        // together.
        PivotModel absurd = new PivotModel(0.35f, 0.35f);

        Assert.assertEquals(PivotModel.MAX_RATIO, absurd.ratio, TOLERANCE);
    }

    @Test
    public void withoutALeverArmARayPointsWhereItAlwaysDid() {
        double[] direction = unit(0.3, 0.9, 0.2);
        double[] referred = SphericalGeometry.pivotDirection(
                basis, 0.0, direction[0], direction[1], direction[2]);

        Assert.assertEquals(direction[0], referred[0], TOLERANCE);
        Assert.assertEquals(direction[1], referred[1], TOLERANCE);
        Assert.assertEquals(direction[2], referred[2], TOLERANCE);
    }

    @Test
    public void aReferredRayIsStillADirection() {
        double[] ratios = new double[] {0.02, 0.1, PivotModel.MAX_RATIO};
        double[][] directions = new double[][] {
                unit(0.0, 1.0, 0.0),
                unit(0.5, 0.85, 0.15),
                unit(-0.7, 0.7, -0.1)
        };
        for (int r = 0; r < ratios.length; r++) {
            double ratio = ratios[r];
            for (int d = 0; d < directions.length; d++) {
                double[] direction = directions[d];
                double[] referred = SphericalGeometry.pivotDirection(
                        basis, ratio, direction[0], direction[1], direction[2]);
                double length = Math.sqrt(
                        referred[0] * referred[0]
                                + referred[1] * referred[1]
                                + referred[2] * referred[2]);
                Assert.assertEquals("ratio " + ratio + " left a non-unit direction", 1.0, length, 1e-9);
            }
        }
    }

    @Test
    public void theOpticalAxisIsUnmovedByALeverArmAlongIt() {
        // The lever points down the axis, so the one direction it cannot swing
        // is the axis itself.
        double[] referred = SphericalGeometry.pivotDirection(basis, 0.1, 0.0, 1.0, 0.0);

        Assert.assertEquals(0.0, referred[0], TOLERANCE);
        Assert.assertEquals(1.0, referred[1], TOLERANCE);
        Assert.assertEquals(0.0, referred[2], TOLERANCE);
    }

    @Test
    public void aCameraHeldOutInFrontSeesANarrowerSliceOfTheSphere() {
        // 30° off the axis at the lens is under 28° off it from the pivot: the
        // camera has moved towards what it is looking at, so the same frame
        // spans less of the sphere the canvas is measured in.
        double[] border = unit(0.5, Math.sqrt(3.0) / 2.0, 0.0);
        double[] referred = SphericalGeometry.pivotDirection(
                basis, 0.1, border[0], border[1], border[2]);
        double angle = Math.toDegrees(Math.atan2(referred[0], referred[1]));

        Assert.assertTrue("expected less than 30°, was " + angle, angle < 29.0);
        Assert.assertTrue("expected the slice to narrow, not collapse, was " + angle, angle > 25.0);
    }

    @Test
    public void theProjectionAndTheReferralAreInversesOfEachOther() {
        // What the renderer relies on: a pivot direction projected through the
        // shortened depth lands on the same pixel the original camera ray came
        // out of. If these two disagreed, every frame would be sampled from the
        // wrong place by exactly the amount the correction was worth.
        double ratio = 0.08;
        double[] cameraRay = unit(0.4, 1.0, 0.25);
        double[] expected = SphericalGeometry.projectDirection(
                basis, intrinsics, cameraRay[0], cameraRay[1], cameraRay[2]);
        Assert.assertNotNull("the test ray has to be inside the frame to prove anything", expected);

        double[] fromPivot = SphericalGeometry.pivotDirection(
                basis, ratio, cameraRay[0], cameraRay[1], cameraRay[2]);
        double[] actual = SphericalGeometry.projectDirection(
                basis, intrinsics, fromPivot[0], fromPivot[1], fromPivot[2], ratio);

        Assert.assertNotNull(actual);
        Assert.assertEquals(expected[0], actual[0], 1e-6);
        Assert.assertEquals(expected[1], actual[1], 1e-6);
    }

    @Test
    public void aFramesFootprintShrinksOnceTheLensIsOffTheAxis() {
        CanvasFootprint onAxis = FrameFootprint.compute(basis, intrinsics, 4096, 2048);
        CanvasFootprint offAxis = FrameFootprint.compute(basis, intrinsics, 4096, 2048, 2, 0.1);

        Assert.assertTrue(
                "footprint " + offAxis.columnSpan + " did not narrow from " + onAxis.columnSpan,
                offAxis.columnSpan < onAxis.columnSpan);
        Assert.assertTrue(
                "footprint " + offAxis.rowSpan + " did not narrow from " + onAxis.rowSpan,
                offAxis.rowSpan < onAxis.rowSpan);
        // Still recognisably the same frame, not a collapsed one.
        Assert.assertTrue(offAxis.columnSpan > onAxis.columnSpan * 0.8);
    }

    private double[] unit(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / length, y / length, z / length};
    }
}
