package com.n30dyn4m1c.photosphere.sensor;

import android.hardware.SensorManager;
import android.view.Surface;

import com.n30dyn4m1c.photosphere.stitching.CameraBasis;
import com.n30dyn4m1c.photosphere.stitching.CameraPose;
import com.n30dyn4m1c.photosphere.stitching.RotationMath;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Covers the parts of the tracker that are pure arithmetic.
 *
 * The sensor path itself needs a device ({@code SensorManager}'s matrix helpers are
 * stubbed out in local unit tests), and is verified by hand through
 * {@code OrientationDebugScreen}. The {@code Surface.ROTATION_*} and {@code SensorManager.AXIS_*}
 * values used here are compile-time constants, so they survive the stub jar.
 */
public class OrientationTrackerTest {

    private static final float TOLERANCE = 1e-4f;

    @Test
    public void normalizeDegreesLeavesInRangeAnglesAlone() {
        Assert.assertEquals(0f, OrientationTracker.normalizeDegrees(0f), TOLERANCE);
        Assert.assertEquals(90f, OrientationTracker.normalizeDegrees(90f), TOLERANCE);
        Assert.assertEquals(-90f, OrientationTracker.normalizeDegrees(-90f), TOLERANCE);
        Assert.assertEquals(-179.5f, OrientationTracker.normalizeDegrees(-179.5f), TOLERANCE);
    }

    @Test
    public void normalizeDegreesWrapsOntoAHalfOpenRange() {
        // 180 and -180 are the same bearing; the range is [-180, 180).
        Assert.assertEquals(-180f, OrientationTracker.normalizeDegrees(180f), TOLERANCE);
        Assert.assertEquals(-180f, OrientationTracker.normalizeDegrees(-180f), TOLERANCE);
        Assert.assertEquals(-90f, OrientationTracker.normalizeDegrees(270f), TOLERANCE);
        Assert.assertEquals(90f, OrientationTracker.normalizeDegrees(-270f), TOLERANCE);
        Assert.assertEquals(0f, OrientationTracker.normalizeDegrees(360f), TOLERANCE);
        Assert.assertEquals(1f, OrientationTracker.normalizeDegrees(721f), TOLERANCE);
    }

    @Test
    public void eachDisplayRotationMapsToItsOwnAxisPair() {
        int[] rotations = new int[] {
                Surface.ROTATION_0,
                Surface.ROTATION_90,
                Surface.ROTATION_180,
                Surface.ROTATION_270
        };
        Set<DisplayAxes> pairs = new HashSet<DisplayAxes>();
        for (int i = 0; i < rotations.length; i++) {
            pairs.add(DisplayAxes.forDisplayRotation(rotations[i]));
        }

        Assert.assertEquals(rotations.length, pairs.size());
    }

    @Test
    public void portraitIsTheIdentityRemap() {
        DisplayAxes axes = DisplayAxes.forDisplayRotation(Surface.ROTATION_0);
        Assert.assertEquals(SensorManager.AXIS_X, axes.axisX);
        Assert.assertEquals(SensorManager.AXIS_Y, axes.axisY);
    }

    @Test
    public void landscapeSwapsTheChassisAxes() {
        DisplayAxes axes = DisplayAxes.forDisplayRotation(Surface.ROTATION_90);
        Assert.assertEquals(SensorManager.AXIS_Y, axes.axisX);
        Assert.assertEquals(SensorManager.AXIS_MINUS_X, axes.axisY);
    }

    @Test
    public void anUnknownRotationFallsBackToPortrait() {
        Assert.assertEquals(
                DisplayAxes.forDisplayRotation(Surface.ROTATION_0),
                DisplayAxes.forDisplayRotation(-1));
    }

    @Test
    public void sensorAccuracyMapsOntoTheReadableEnum() {
        Assert.assertEquals(
                OrientationAccuracy.High,
                OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_HIGH));
        Assert.assertEquals(
                OrientationAccuracy.Medium,
                OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM));
        Assert.assertEquals(
                OrientationAccuracy.Low,
                OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_ACCURACY_LOW));
        Assert.assertEquals(
                OrientationAccuracy.Unreliable,
                OrientationAccuracy.fromSensorAccuracy(SensorManager.SENSOR_STATUS_UNRELIABLE));
        // SENSOR_STATUS_NO_CONTACT and anything else a vendor invents.
        Assert.assertEquals(OrientationAccuracy.Unknown, OrientationAccuracy.fromSensorAccuracy(-1));
    }

    @Test
    public void onlyMediumAndHighAccuracyCountAsUsable() {
        Assert.assertTrue(OrientationAccuracy.High.isUsable());
        Assert.assertTrue(OrientationAccuracy.Medium.isUsable());
        Assert.assertFalse(OrientationAccuracy.Low.isUsable());
        Assert.assertFalse(OrientationAccuracy.Unreliable.isUsable());
        Assert.assertFalse(OrientationAccuracy.Unknown.isUsable());
    }

    @Test
    public void captureIsOnlyRefusedWhenTheSensorCallsItselfUnreliable() {
        // The capture plan is anchored on the bearing the run started at, so an
        // uncalibrated compass shifts every target together and costs the sphere
        // nothing. Gating capture on `isUsable` instead strands the user
        // indoors: the reticle tracks perfectly and the shutter never fires,
        // with nothing on screen to explain it.
        Assert.assertTrue(OrientationAccuracy.High.allowsCapture());
        Assert.assertTrue(OrientationAccuracy.Medium.allowsCapture());
        Assert.assertTrue(OrientationAccuracy.Low.allowsCapture());
        Assert.assertTrue(OrientationAccuracy.Unknown.allowsCapture());
        Assert.assertFalse(OrientationAccuracy.Unreliable.allowsCapture());
    }

    @Test
    public void theDefaultSampleHasNoFix() {
        Assert.assertFalse(new OrientationData().hasFix());
        Assert.assertTrue(new OrientationData(0f, 0f, 0f, OrientationAccuracy.Unknown, 1L).hasFix());
    }

    @Test
    public void cameraAnglesRoundTripThroughADeviceMatrix() {
        // `cameraAnglesDegrees` is the inverse of the axis construction
        // `CameraBasis.of` performs, so feeding it the matrix a phone with that
        // basis would produce must hand the same angles back — or every frame
        // lands on the sphere rotated.
        int[] displays = new int[] {
                Surface.ROTATION_0,
                Surface.ROTATION_90,
                Surface.ROTATION_180,
                Surface.ROTATION_270
        };
        for (int yaw = -150; yaw <= 150; yaw += 30) {
            for (int pitch = -60; pitch <= 60; pitch += 20) {
                for (int roll = -120; roll <= 120; roll += 40) {
                    for (int d = 0; d < displays.length; d++) {
                        int display = displays[d];
                        String label = "yaw " + yaw + " pitch " + pitch + " roll " + roll
                                + " display " + display;
                        CameraBasis basis = CameraBasis.of(
                                new CameraPose((float) yaw, (float) pitch, (float) roll));
                        float[] out = new float[3];
                        OrientationTracker.cameraAnglesDegrees(deviceMatrix(basis, display), display, out);
                        Assert.assertEquals(label + ": yaw", (float) yaw, out[0], 1e-3f);
                        Assert.assertEquals(label + ": pitch", (float) pitch, out[1], 1e-3f);
                        Assert.assertEquals(label + ": roll", (float) roll, out[2], 1e-3f);
                    }
                }
            }
        }
    }

    @Test
    public void aLevelPanReadsAsYawNotRoll() {
        // The regression this file exists for: the camera-reference angles used
        // to report a level pan as *roll* and kept yaw pinned near zero, so the
        // stitcher placed every frame of a ring onto the same longitude, each
        // one rotated differently — the "aligned but at odd angles" failure.
        float[] headings = new float[] {0f, 30f, 60f, 90f, -45f, 170f};
        for (int i = 0; i < headings.length; i++) {
            float heading = headings[i];
            CameraBasis basis = CameraBasis.of(new CameraPose(heading, 0f, 0f));
            float[] out = new float[3];
            OrientationTracker.cameraAnglesDegrees(
                    deviceMatrix(basis, Surface.ROTATION_0), Surface.ROTATION_0, out);
            Assert.assertEquals("yaw for heading " + heading, heading, out[0], 1e-3f);
            Assert.assertEquals("roll for heading " + heading, 0f, out[2], 1e-3f);
        }
    }

    @Test
    public void aRollOfThePhoneReadsAsRollNotYaw() {
        float[] rolls = new float[] {0f, 30f, 90f, -45f};
        for (int i = 0; i < rolls.length; i++) {
            float roll = rolls[i];
            CameraBasis basis = CameraBasis.of(new CameraPose(0f, 0f, roll));
            float[] out = new float[3];
            OrientationTracker.cameraAnglesDegrees(
                    deviceMatrix(basis, Surface.ROTATION_0), Surface.ROTATION_0, out);
            Assert.assertEquals("roll " + roll, roll, out[2], 1e-3f);
            Assert.assertEquals("yaw for roll " + roll, 0f, out[0], 1e-3f);
        }
    }

    @Test
    public void negativePitchIsAimedAboveTheHorizon() {
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, -30f, 0f));
        float[] out = new float[3];
        OrientationTracker.cameraAnglesDegrees(
                deviceMatrix(basis, Surface.ROTATION_0), Surface.ROTATION_0, out);
        Assert.assertEquals("aimed up is negative pitch", -30f, out[1], 1e-3f);
    }

    @Test
    public void yawAndRollWrapOntoTheHalfOpenRange() {
        // The camera looks at the pole of the compass — a heading that could be
        // ±180 — and the extraction has to pick one side.
        CameraBasis basis = CameraBasis.of(new CameraPose(180f, 0f, 0f));
        float[] out = new float[3];
        OrientationTracker.cameraAnglesDegrees(
                deviceMatrix(basis, Surface.ROTATION_0), Surface.ROTATION_0, out);
        Assert.assertTrue(
                "yaw " + out[0] + " should sit in [-180, 180)",
                out[0] >= -180f && out[0] <= 180f);
        Assert.assertTrue(out[0] != 180f || out[0] == -180f);
    }

    @Test
    public void theCameraBasisExtractedFromADeviceMatrixRoundTrips() {
        // cameraBasisMatrix reads the same axes cameraAnglesDegrees reads, so
        // feeding it the matrix a phone with a known basis would produce must
        // hand that basis back unchanged — including the axes the display
        // rotation remaps.
        int[] displays = new int[] {
                Surface.ROTATION_0,
                Surface.ROTATION_90,
                Surface.ROTATION_180,
                Surface.ROTATION_270
        };
        for (int i = 0; i < displays.length; i++) {
            int display = displays[i];
            CameraBasis expected = CameraBasis.of(new CameraPose(37f, -22f, 9f));
            float[] extracted = new float[9];
            OrientationTracker.cameraBasisMatrix(deviceMatrix(expected, display), display, extracted);
            CameraBasis basis = CameraBasis.fromRotationMatrix(
                    new double[] {
                            extracted[0], extracted[1], extracted[2],
                            extracted[3], extracted[4], extracted[5],
                            extracted[6], extracted[7], extracted[8]
                    });
            Assert.assertEquals(
                    "display " + display,
                    0.0,
                    RotationMath.angle(basis.toRotationMatrix(), expected.toRotationMatrix()),
                    1e-4);
        }
    }

    private float[] deviceMatrix(CameraBasis basis, int displayRotation) {
        // Build the device->world matrix a real phone with this camera basis
        // would report, given how the display rotation remaps which chassis axis
        // is the image's right/up.
        double[] right = new double[] {basis.rightX, basis.rightY, basis.rightZ};
        double[] up = new double[] {basis.upX, basis.upY, basis.upZ};
        double[] forward = new double[] {basis.forwardX, basis.forwardY, basis.forwardZ};

        double[] devX;
        double[] devY;
        switch (displayRotation) {
            case Surface.ROTATION_90:
                devX = neg(up);
                devY = right;
                break;
            case Surface.ROTATION_180:
                devX = neg(right);
                devY = neg(up);
                break;
            case Surface.ROTATION_270:
                devX = up;
                devY = neg(right);
                break;
            default:
                devX = right;
                devY = up;
                break;
        }
        double[] devZ = neg(forward);

        float[] out = new float[9];
        put(devX, 0, out);
        put(devY, 1, out);
        put(devZ, 2, out);
        return out;
    }

    private static double[] neg(double[] v) {
        return new double[] {-v[0], -v[1], -v[2]};
    }

    private static void put(double[] v, int column, float[] out) {
        out[column] = (float) v[0];
        out[3 + column] = (float) v[1];
        out[6 + column] = (float) v[2];
    }
}
