package com.n30dyn4m1c.photosphere.sensor;

import com.n30dyn4m1c.photosphere.stitching.CameraBasis;
import com.n30dyn4m1c.photosphere.stitching.CameraPose;
import com.n30dyn4m1c.photosphere.stitching.RotationMath;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class OrientationMeanTest {

    private static float[] toFloatArray(double[] source) {
        float[] out = new float[source.length];
        for (int i = 0; i < source.length; i++) {
            out[i] = (float) source[i];
        }
        return out;
    }

    private static double[] toDoubleArray(float[] source) {
        double[] out = new double[source.length];
        for (int i = 0; i < source.length; i++) {
            out[i] = source[i];
        }
        return out;
    }

    @Test
    public void aPlainSetOfAnglesAveragesAsExpected() {
        Assert.assertEquals(5f, OrientationMean.meanAngleDegrees(Arrays.asList(0f, 10f)), 1e-3f);
        Assert.assertEquals(-5f, OrientationMean.meanAngleDegrees(Arrays.asList(-10f, 0f)), 1e-3f);
    }

    @Test
    public void aCrossingOfPlusOrMinusOneEightyUnwrapsInsteadOfSmearing() {
        // 179° and -179° are 2° apart, and the short arc between them passes
        // through the date line, so their mean is ±180° — not the 0° a naive
        // average would claim.
        Assert.assertEquals(
                180f,
                Math.abs(OrientationMean.meanAngleDegrees(Arrays.asList(179f, -179f))),
                1e-3f);
        Assert.assertEquals(
                180f,
                Math.abs(OrientationMean.meanAngleDegrees(Arrays.asList(-179f, 179f))),
                1e-3f);
    }

    @Test
    public void theMeanOrientationOfAWindowIsTheMeanOfItsAngles() {
        OrientationData mean = OrientationMean.meanOrientation(
                Arrays.asList(
                        new OrientationData(10f, 1f, -2f, OrientationAccuracy.Unknown, 1L),
                        new OrientationData(12f, 3f, 0f, OrientationAccuracy.Unknown, 2L)));

        Assert.assertEquals(11f, mean.yawDegrees, 1e-3f);
        Assert.assertEquals(2f, mean.pitchDegrees, 1e-3f);
        Assert.assertEquals(-1f, mean.rollDegrees, 1e-3f);
    }

    @Test
    public void theMeanCarriesTheFreshestAccuracyAndTimestamp() {
        OrientationData mean = OrientationMean.meanOrientation(
                Arrays.asList(
                        new OrientationData(0f, 0f, 0f, OrientationAccuracy.Low, 1L),
                        new OrientationData(1f, 0f, 0f, OrientationAccuracy.Medium, 2L)));

        Assert.assertEquals(OrientationAccuracy.Medium, mean.accuracy);
        Assert.assertEquals(2L, mean.timestampNanos);
    }

    @Test
    public void samplesWithoutAFixAreIgnored() {
        OrientationData mean = OrientationMean.meanOrientation(
                Arrays.asList(
                        new OrientationData(),
                        new OrientationData(20f, 0f, 0f, OrientationAccuracy.Unknown, 5L)));
        Assert.assertEquals(20f, mean.yawDegrees, 1e-3f);
        Assert.assertEquals(5L, mean.timestampNanos);
    }

    @Test
    public void aZenithDwellAveragesAsARotationNotAsScatteredAngles() {
        // Two physically nearby orientations of a camera aimed near the zenith,
        // whose Euler representations scatter: at pitch -89° yaw and roll trade
        // against each other, so the components alone look wildly different and
        // averaging them would reconstruct an orientation rolled far away about
        // the pole axis. The rotation mean must land where both samples agree.
        CameraBasis first = CameraBasis.of(new CameraPose(10f, -89f, 5f));
        CameraBasis second = CameraBasis.of(new CameraPose(170f, -89f, -175f));
        double[] firstMatrix = first.toRotationMatrix();
        double[] secondMatrix = second.toRotationMatrix();

        OrientationData mean = OrientationMean.meanOrientation(
                Arrays.asList(
                        new OrientationData(
                                10f, -89f, 5f, OrientationAccuracy.Unknown, 1L,
                                toFloatArray(firstMatrix)),
                        new OrientationData(
                                170f, -89f, -175f, OrientationAccuracy.Unknown, 2L,
                                toFloatArray(secondMatrix))));

        // The mean carries a basis, and it sits between the two samples.
        Assert.assertNotNull(mean.cameraBasis);
        double[] meanMatrix = CameraBasis.fromRotationMatrix(toDoubleArray(mean.cameraBasis))
                .toRotationMatrix();
        double fromFirst = Math.toDegrees(RotationMath.angle(meanMatrix, firstMatrix));
        double fromSecond = Math.toDegrees(RotationMath.angle(meanMatrix, secondMatrix));
        Assert.assertTrue("mean " + fromFirst + "° from the first sample", fromFirst < 12.0);
        Assert.assertTrue("mean " + fromSecond + "° from the second sample", fromSecond < 12.0);

        // The angles the mean reports reconstruct the same orientation the
        // matrix describes: the two halves of the pose agree.
        CameraBasis reconstructed = CameraBasis.of(
                new CameraPose(mean.yawDegrees, mean.pitchDegrees, mean.rollDegrees));
        Assert.assertEquals(
                0.0,
                Math.toDegrees(RotationMath.angle(reconstructed.toRotationMatrix(), meanMatrix)),
                1.0);
    }

    @Test
    public void anIdenticalZenithDwellReproducesItsOrientationExactly() {
        // A still phone aimed at the zenith reports a different arbitrary
        // (yaw, roll) split on every sample — the two Euler triples below are
        // the same camera. The basis is identical on both, and the rotation
        // mean must come back as exactly that basis rather than a smeared
        // average of the scattered angles.
        float[] basis = toFloatArray(
                CameraBasis.of(new CameraPose(0f, -90f, 0f)).toRotationMatrix());

        OrientationData mean = OrientationMean.meanOrientation(
                Arrays.asList(
                        new OrientationData(
                                0f, -90f, 0f, OrientationAccuracy.Unknown, 1L, basis),
                        new OrientationData(
                                120f, -90f, -120f, OrientationAccuracy.Unknown, 2L, basis)));

        double[] expected = CameraBasis.fromRotationMatrix(toDoubleArray(basis)).toRotationMatrix();
        double[] meanMatrix = CameraBasis.fromRotationMatrix(toDoubleArray(mean.cameraBasis))
                .toRotationMatrix();
        Assert.assertEquals(0.0, RotationMath.angle(meanMatrix, expected), 1e-4);

        // And the reported angles are consistent with the basis.
        CameraBasis reconstructed = CameraBasis.of(
                new CameraPose(mean.yawDegrees, mean.pitchDegrees, mean.rollDegrees));
        Assert.assertEquals(0.0, RotationMath.angle(reconstructed.toRotationMatrix(), meanMatrix), 1e-3);
    }
}
