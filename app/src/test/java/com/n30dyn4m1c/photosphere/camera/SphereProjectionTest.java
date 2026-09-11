package com.n30dyn4m1c.photosphere.camera;

import com.n30dyn4m1c.photosphere.sensor.OrientationData;

import org.junit.Assert;
import org.junit.Test;

public class SphereProjectionTest {

    private static final float TOLERANCE = 1e-3f;

    private OrientationData orientation(float yaw, float pitch) {
        return orientation(yaw, pitch, 0f);
    }

    private OrientationData orientation(float yaw, float pitch, float roll) {
        return new OrientationData(yaw, pitch, roll);
    }

    @Test
    public void aTargetTheCameraIsAlreadyAimedAtSitsDeadCentre() {
        TargetView view = SphereProjection.project(
                orientation(30f, -12f, 7f),
                new SphereTarget(30f, -12f));

        Assert.assertEquals(0f, view.getX(), TOLERANCE);
        Assert.assertEquals(0f, view.getY(), TOLERANCE);
        Assert.assertEquals(1f, view.getZ(), TOLERANCE);
        Assert.assertEquals(0f, view.getAngularDistanceDegrees(), TOLERANCE);
        Assert.assertTrue(view.isInFront());
    }

    @Test
    public void aTargetFurtherClockwiseFallsToTheRightOfFrame() {
        TargetView view = SphereProjection.project(
                orientation(0f, 0f),
                new SphereTarget(10f, 0f));

        Assert.assertTrue("expected a right-of-centre offset, was " + view.getX(), view.getX() > 0f);
        Assert.assertEquals(0f, view.getY(), TOLERANCE);
        Assert.assertEquals(10f, view.getAngularDistanceDegrees(), TOLERANCE);
    }

    @Test
    public void aTargetAboveTheHorizonFallsAboveCentre() {
        // Negative pitch is up, per OrientationData.
        TargetView view = SphereProjection.project(
                orientation(0f, 0f),
                new SphereTarget(0f, -10f));

        Assert.assertEquals(0f, view.getX(), TOLERANCE);
        Assert.assertTrue("expected an above-centre offset, was " + view.getY(), view.getY() > 0f);
        Assert.assertEquals(10f, view.getAngularDistanceDegrees(), TOLERANCE);
    }

    @Test
    public void rollingTheDeviceTurnsTheSceneTheOtherWay() {
        // Top edge rolled to the right by a quarter turn: what was overhead is
        // now off the left of the frame.
        TargetView view = SphereProjection.project(
                orientation(0f, 0f, 90f),
                new SphereTarget(0f, -89.99f));

        Assert.assertTrue("expected a left-of-centre offset, was " + view.getX(), view.getX() < 0f);
        Assert.assertEquals(0f, view.getY(), TOLERANCE);
    }

    @Test
    public void aTargetBehindTheCameraIsNotProjectable() {
        TargetView view = SphereProjection.project(
                orientation(0f, 0f),
                new SphereTarget(180f, 0f));

        Assert.assertFalse(view.isInFront());
        Assert.assertEquals(180f, view.getAngularDistanceDegrees(), TOLERANCE);
    }

    @Test
    public void distanceIsMeasuredTheShortWayAroundNorth() {
        float distance = SphereProjection.angularDistanceDegrees(
                orientation(179f, 0f),
                new SphereTarget(-179f, 0f));

        Assert.assertEquals(2f, distance, TOLERANCE);
    }

    @Test
    public void distanceIgnoresRoll() {
        SphereTarget target = new SphereTarget(55f, 10f);
        float level = SphereProjection.angularDistanceDegrees(orientation(20f, -30f), target);
        float rolled = SphereProjection.angularDistanceDegrees(orientation(20f, -30f, 137f), target);

        // Turning the phone in its own plane does not change where it is aimed.
        Assert.assertEquals(level, rolled, TOLERANCE);
    }

    @Test
    public void cameraAxesStayOrthonormalAtAnAwkwardAttitude() {
        // Three perpendicular world directions must stay perpendicular and unit
        // length once expressed in the camera frame. This is what guarantees the
        // rolled basis is a rotation rather than a skew.
        OrientationData at = orientation(37f, -22f, 61f);
        float[] east = vector(SphereProjection.project(at, new SphereTarget(90f, 0f)));
        float[] north = vector(SphereProjection.project(at, new SphereTarget(0f, 0f)));
        float[] up = vector(SphereProjection.project(at, new SphereTarget(0f, -90f)));

        Assert.assertEquals(1f, length(east), TOLERANCE);
        Assert.assertEquals(1f, length(north), TOLERANCE);
        Assert.assertEquals(1f, length(up), TOLERANCE);
        Assert.assertEquals(0f, dot(east, north), TOLERANCE);
        Assert.assertEquals(0f, dot(east, up), TOLERANCE);
        Assert.assertEquals(0f, dot(north, up), TOLERANCE);
    }

    @Test
    public void focalLengthIsTheOneThatKeepsBothAxesInView() {
        // A 90-degree field of view puts the edge exactly one half-extent from
        // the centre, so the focal length is that half-extent.
        float square = SphereProjection.focalLengthPx(
                1000f,
                1000f,
                new FieldOfView(90f, 90f));
        Assert.assertEquals(500f, square, 0.1f);

        // Portrait phone: the vertical axis is the wide one, and the taller
        // viewport wins, cropping the horizontal.
        float portrait = SphereProjection.focalLengthPx(
                1080f,
                2400f,
                new FieldOfView(52f, 66f));
        float horizontalCandidate = 540f / (float) Math.tan(Math.toRadians(26.0));
        float verticalCandidate = 1200f / (float) Math.tan(Math.toRadians(33.0));
        Assert.assertEquals(Math.max(horizontalCandidate, verticalCandidate), portrait, 0.1f);
        Assert.assertTrue(portrait >= horizontalCandidate);
    }

    @Test
    public void aMarkerOneFieldOfViewAcrossLandsAtTheFrameEdge() {
        // Half the horizontal field of view to the right of a level camera puts
        // the target on the right edge of a viewport that is not cropped.
        FieldOfView fov = new FieldOfView(90f, 90f);
        float focal = SphereProjection.focalLengthPx(1000f, 1000f, fov);
        TargetView view = SphereProjection.project(
                orientation(0f, 0f),
                new SphereTarget(45f, 0f));

        float offsetPx = view.getX() / view.getZ() * focal;
        Assert.assertEquals(500f, offsetPx, 0.5f);
    }

    private float[] vector(TargetView view) {
        return new float[] {view.getX(), view.getY(), view.getZ()};
    }

    private float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
