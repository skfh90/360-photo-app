package com.n30dyn4m1c.photosphere.camera;

import org.junit.Assert;
import org.junit.Test;

public class CameraOpticsTest {

    private static final float TOLERANCE = 0.5f;

    /** Galaxy S23-shaped: ultrawide, main, 3x telephoto behind one logical id. */
    private final float[] threeLenses = new float[] {2.2f, 6.3f, 17.0f};

    /** Diagonal of a 1/1.56" main sensor, near enough. */
    private final float sensorDiagonalMm = 9.5f;

    private final FieldOfView geometry = new FieldOfView(66f, 52f);

    /** A 4:3 sensor seeing 66° across and 52° down. */
    private final FieldOfView sensor = new FieldOfView(66f, 52f);

    @Test
    public void aSingleLensCameraIsTakenAtItsWord() {
        Assert.assertEquals(
                6.3f,
                CameraOptics.selectFocalLengthMm(new float[] {6.3f}, sensorDiagonalMm, false)
                        .floatValue(),
                1e-4f);
    }

    @Test
    public void aCameraThatListsNothingHasNoFocalLengthToOffer() {
        Assert.assertNull(CameraOptics.selectFocalLengthMm(null, sensorDiagonalMm, false));
        Assert.assertNull(CameraOptics.selectFocalLengthMm(new float[] {}, sensorDiagonalMm, false));
        Assert.assertNull(
                CameraOptics.selectFocalLengthMm(new float[] {0f, -1f}, sensorDiagonalMm, false));
    }

    @Test
    public void theMainLensIsPickedOutOfAMultiCameraRatherThanTheUltrawide() {
        // The bug this exists to stop: taking the first entry hands back the
        // ultrawide's 2.2 mm while the session streams the main lens, which
        // nearly doubles the field of view and spaces the plan clean past any
        // overlap at all.
        Float focal = CameraOptics.selectFocalLengthMm(threeLenses, sensorDiagonalMm, false);

        Assert.assertEquals(6.3f, focal.floatValue(), 1e-4f);
    }

    @Test
    public void aProfileThatAskedForTheWidestLensGetsIt() {
        Float focal = CameraOptics.selectFocalLengthMm(threeLenses, sensorDiagonalMm, true);

        Assert.assertEquals(2.2f, focal.floatValue(), 1e-4f);
    }

    @Test
    public void withoutASensorSizeTheSafeGuessIsTheNarrowestLens() {
        // Guessing narrow costs frames; guessing wide costs the run.
        Float focal = CameraOptics.selectFocalLengthMm(threeLenses, 0f, false);

        Assert.assertEquals(17.0f, focal.floatValue(), 1e-4f);
    }

    @Test
    public void aCalibrationTheGeometryAgreesWithIsTheOneThatIsUsed() {
        FieldOfView calibration = new FieldOfView(68f, 53f);

        Assert.assertEquals(calibration, CameraOptics.reconcileFieldOfView(calibration, geometry));
    }

    @Test
    public void aCalibrationQuotedAgainstTheWrongCropIsThrownOut() {
        // The classic failure: a focal length in pixels of the full array
        // divided through by a binned array, which halves the answer.
        FieldOfView calibration = new FieldOfView(36f, 28f);

        Assert.assertEquals(geometry, CameraOptics.reconcileFieldOfView(calibration, geometry));
    }

    @Test
    public void eitherEstimateAloneIsBetterThanNothing() {
        Assert.assertEquals(geometry, CameraOptics.reconcileFieldOfView(null, geometry));
        Assert.assertEquals(geometry, CameraOptics.reconcileFieldOfView(geometry, null));
        // And with neither, the fallback still has to be a legal field of view.
        FieldOfView fallback = CameraOptics.reconcileFieldOfView(null, null);
        Assert.assertTrue(
                fallback.getHorizontalDegrees() >= 1f && fallback.getHorizontalDegrees() <= 179f);
        Assert.assertTrue(
                fallback.getVerticalDegrees() >= 1f && fallback.getVerticalDegrees() <= 179f);
    }

    @Test
    public void aStreamTheSameShapeAsTheSensorSeesAllOfIt() {
        FieldOfView stream = CameraOptics.croppedToStreamAspect(sensor, 4f / 3f, 4f / 3f);

        Assert.assertEquals(sensor.getHorizontalDegrees(), stream.getHorizontalDegrees(), TOLERANCE);
        Assert.assertEquals(sensor.getVerticalDegrees(), stream.getVerticalDegrees(), TOLERANCE);
    }

    @Test
    public void anUnknownStreamShapeLeavesTheSensorAnglesAlone() {
        Assert.assertEquals(sensor, CameraOptics.croppedToStreamAspect(sensor, 4f / 3f, 0f));
        Assert.assertEquals(sensor, CameraOptics.croppedToStreamAspect(sensor, 0f, 16f / 9f));
    }

    @Test
    public void aWidescreenStreamOffAFourByThreeSensorLosesHeightNotWidth() {
        FieldOfView stream = CameraOptics.croppedToStreamAspect(sensor, 4f / 3f, 16f / 9f);

        // Camera2 crops rather than squeezes, so the full width survives...
        Assert.assertEquals(sensor.getHorizontalDegrees(), stream.getHorizontalDegrees(), TOLERANCE);
        // ...and the height is cut by the quarter the 16:9 window throws away.
        Assert.assertTrue(
                "expected the vertical angle to shrink, was " + stream.getVerticalDegrees(),
                stream.getVerticalDegrees() < sensor.getVerticalDegrees() - 5f);
    }

    @Test
    public void aTallerStreamLosesWidthInstead() {
        FieldOfView stream = CameraOptics.croppedToStreamAspect(sensor, 4f / 3f, 1f);

        Assert.assertEquals(sensor.getVerticalDegrees(), stream.getVerticalDegrees(), TOLERANCE);
        Assert.assertTrue(
                "expected the horizontal angle to shrink, was " + stream.getHorizontalDegrees(),
                stream.getHorizontalDegrees() < sensor.getHorizontalDegrees() - 5f);
    }

    @Test
    public void croppingIsConsistentWithTheShapeItCropsTo() {
        // The point of the crop: what comes back really is the requested shape,
        // measured the way a pinhole measures shape — in tangents, not degrees.
        float[] requestedAspects = new float[] {16f / 9f, 3f / 2f, 1f, 3f / 4f};
        for (int i = 0; i < requestedAspects.length; i++) {
            float requested = requestedAspects[i];
            FieldOfView stream = CameraOptics.croppedToStreamAspect(sensor, 4f / 3f, requested);
            float ratio = tanHalfOf(stream.getHorizontalDegrees())
                    / tanHalfOf(stream.getVerticalDegrees());
            Assert.assertEquals("aspect " + requested + " came back as " + ratio, requested, ratio, 0.02f);
        }
    }

    private float tanHalfOf(float degrees) {
        return (float) Math.tan(Math.toRadians(degrees / 2.0));
    }
}
