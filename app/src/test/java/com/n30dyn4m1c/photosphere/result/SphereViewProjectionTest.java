package com.n30dyn4m1c.photosphere.result;

import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata;
import com.n30dyn4m1c.photosphere.stitching.Equirectangular;

import org.junit.Assert;
import org.junit.Test;

/**
 * The 360 viewer has to sample the same sphere the stitcher painted, or a
 * look that should show north shows a seam instead. These cases pin the
 * mapping down without spinning up OpenGL.
 */
public class SphereViewProjectionTest {

    @Test
    public void lookingNorthAtTheScreenCentreSamplesTheMiddleOfAFullPano() {
        float[] uv = centreUv(0f, 0f);

        Assert.assertNotNull(uv);
        Assert.assertEquals(0.5f, uv[0], 1e-4f);
        Assert.assertEquals(0.5f, uv[1], 1e-4f);
    }

    @Test
    public void lookingEastSamplesThreeQuartersOfTheWayAcrossTheCanvas() {
        // Longitude 90° is (90 + 180) / 360 = 0.75, the same column Equirectangular
        // would pick for a frame shot facing east.
        float[] uv = centreUv(90f, 0f);

        Assert.assertEquals(0.75f, uv[0], 1e-4f);
        Assert.assertEquals(0.5f, uv[1], 1e-4f);
    }

    @Test
    public void lookingWestSamplesAQuarterOfTheWayAcrossTheCanvas() {
        float[] uv = centreUv(-90f, 0f);

        Assert.assertEquals(0.25f, uv[0], 1e-4f);
    }

    @Test
    public void lookingUpMovesTheSampleTowardTheTopOfTheImage() {
        float horizon = centreUv(0f, 0f)[1];
        float up = centreUv(0f, 30f)[1];

        Assert.assertTrue(
                "looking up should decrease v (north pole is v=0), was " + up + " vs " + horizon,
                up < horizon);
    }

    @Test
    public void theLookDirectionMatchesEquirectangularDirectionAtTheSameBearing() {
        double yaw = 40.0;
        double pitch = 15.0;
        double[] dir = SphereViewProjection.viewDirection(
                0.0,
                0.0,
                (float) yaw,
                (float) pitch,
                1.0,
                1.0);
        double[] expected = Equirectangular.direction(yaw, pitch);

        Assert.assertEquals(expected[0], dir[0], 1e-6);
        Assert.assertEquals(expected[1], dir[1], 1e-6);
        Assert.assertEquals(expected[2], dir[2], 1e-6);
    }

    @Test
    public void aRingCropRejectsALookAtTheZenith() {
        SphereViewCrop crop = SphereViewCrop.from(
                GPanoMetadata.forSphereRegion(
                        4096,
                        1024,
                        360f,
                        0f,
                        80f,
                        0f));
        double[] dir = SphereViewProjection.viewDirection(
                0.0,
                0.0,
                0f,
                90f,
                0.1,
                0.1);

        Assert.assertNull(SphereViewProjection.sampleUv(dir, crop));
    }

    @Test
    public void aRingCropStillSamplesTheHorizon() {
        SphereViewCrop crop = SphereViewCrop.from(
                GPanoMetadata.forSphereRegion(
                        4096,
                        1024,
                        360f,
                        0f,
                        80f,
                        0f));
        float[] uv = SphereViewProjection.sampleUv(
                SphereViewProjection.viewDirection(
                        0.0,
                        0.0,
                        0f,
                        0f,
                        0.1,
                        0.1),
                crop);

        Assert.assertNotNull(uv);
        Assert.assertEquals(0.5f, uv[0], 1e-3f);
        Assert.assertEquals(0.5f, uv[1], 1e-3f);
    }

    @Test
    public void gpanoFullPanoIsAFullSphereCrop() {
        SphereViewCrop crop = SphereViewCrop.from(GPanoMetadata.forFullPano(4096, 2048));

        Assert.assertEquals(SphereViewCrop.Full, crop);
        Assert.assertTrue(crop.wrapsLongitude());
        Assert.assertEquals(90f, crop.getMaxLatitudeDegrees(), 0.01f);
        Assert.assertEquals(-90f, crop.getMinLatitudeDegrees(), 0.01f);
    }

    @Test
    public void draggingRightLooksLeftSoTheSceneFollowsTheFinger() {
        float[] delta = SphereViewProjection.lookDelta(
                100f,
                0f,
                1000,
                500,
                80f);

        Assert.assertEquals(-8f, delta[0], 1e-4f);
        Assert.assertEquals(0f, delta[1], 1e-4f);
    }

    @Test
    public void draggingDownLooksUpSoTheSceneFollowsTheFinger() {
        float[] delta = SphereViewProjection.lookDelta(
                0f,
                50f,
                1000,
                500,
                80f);

        Assert.assertTrue("downward drag should raise the look, was " + delta[1], delta[1] > 0f);
    }

    @Test
    public void pitchIsClampedInsideARingSoTheLookCannotLeaveTheBand() {
        SphereViewCrop crop = new SphereViewCrop(0f, 0.3f, 1f, 0.4f);
        float clamped = SphereViewProjection.clampPitch(
                80f,
                20f,
                crop);

        Assert.assertTrue(clamped < 80f);
        Assert.assertTrue(clamped <= crop.getMaxLatitudeDegrees() - 10f + 1e-3f);
        Assert.assertTrue(clamped >= crop.getMinLatitudeDegrees() + 10f - 1e-3f);
    }

    @Test
    public void wrapDegreesKeepsASpinThroughSouthContinuous() {
        Assert.assertEquals(-180f, SphereViewProjection.wrapDegrees(180f), 0f);
        Assert.assertEquals(-170f, SphereViewProjection.wrapDegrees(190f), 1e-4f);
        Assert.assertEquals(10f, SphereViewProjection.wrapDegrees(-350f), 1e-4f);
    }

    @Test
    public void aPixelToTheRightOfCentreLooksEastOfTheAim() {
        double tanHalf = Math.tan(Math.toRadians(40.0));
        double[] centre = SphereViewProjection.viewDirection(
                0.0, 0.0, 0f, 0f, tanHalf, tanHalf);
        double[] right = SphereViewProjection.viewDirection(
                0.5, 0.0, 0f, 0f, tanHalf, tanHalf);
        double centreLon = Equirectangular.longitudeOf(centre[0], centre[1]);
        double rightLon = Equirectangular.longitudeOf(right[0], right[1]);

        Assert.assertTrue(
                "a pixel to the right should increase longitude, was " + rightLon + " vs " + centreLon,
                rightLon > centreLon);
        Assert.assertTrue(Math.abs(Equirectangular.latitudeOf(right[2])) < 1.0);
    }

    private float[] centreUv(float lookYawDegrees, float lookPitchDegrees) {
        double tanHalf = Math.tan(Math.toRadians(SphereViewProjection.DEFAULT_FOV_DEGREES / 2.0));
        return SphereViewProjection.sampleUv(
                SphereViewProjection.viewDirection(
                        0.0,
                        0.0,
                        lookYawDegrees,
                        lookPitchDegrees,
                        tanHalf,
                        tanHalf),
                SphereViewCrop.Full);
    }
}
