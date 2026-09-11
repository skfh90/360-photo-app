package com.n30dyn4m1c.photosphere.stitching;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The rotation algebra behind pose refinement, exercised without OpenCV: this is
 * the part that decides how well the overlaps align, so it is pinned down here.
 */
public class RotationMathTest {

    private final Random unseeded = new Random();

    @Test
    public void rotationBetweenMapsOneUnitVectorExactlyOntoAnother() {
        double[] from = new double[] {1.0, 0.0, 0.0};
        double[] to = normalized(new double[] {0.0, 1.0, 1.0});
        double[] rotation = RotationMath.rotationBetween(from, to);
        assertVector(to, RotationMath.apply(rotation, from));
    }

    @Test
    public void antiParallelVectorsStillProduceAValidRotation() {
        double[] from = new double[] {1.0, 0.0, 0.0};
        double[] to = new double[] {-1.0, 0.0, 0.0};
        double[] rotation = RotationMath.rotationBetween(from, to);
        assertVector(to, RotationMath.apply(rotation, from));
    }

    @Test
    public void aRotationSurvivesATripThroughItsQuaternion() {
        double[] axis = normalized(new double[] {1.0, -2.0, 0.5});
        double[] angles = new double[] {0.1, 0.7, 1.3, 2.9};
        for (int i = 0; i < angles.length; i++) {
            double angle = angles[i];
            double[] rotation = RotationMath.rotationAboutAxis(axis, angle);
            double[] rebuilt = RotationMath.quaternionToMatrix(RotationMath.matrixToQuaternion(rotation));
            Assert.assertEquals("angle " + angle, 0.0, RotationMath.angle(rotation, rebuilt), 1e-6);
        }
    }

    @Test
    public void procrustesRecoversARotationFromNoisyCorrespondences() {
        double[] truth = RotationMath.rotationAboutAxis(normalized(new double[] {0.5, 0.2, 0.9}), 0.8);
        double[][] from = new double[60][];
        double[][] to = new double[60][];
        for (int i = 0; i < 60; i++) {
            from[i] = randomUnit();
            to[i] = perturb(RotationMath.apply(truth, from[i]), Math.toRadians(0.3));
        }

        double[] estimate = RotationMath.procrustes(from, to);
        Assert.assertTrue(
                "procrustes off by " + Math.toDegrees(RotationMath.angle(estimate, truth)) + "°",
                RotationMath.angle(estimate, truth) < Math.toRadians(0.2));
    }

    @Test
    public void aSymmetricMatrixReturnsItsEigenvaluesAndVectorsInOrder() {
        // A symmetric matrix with known eigenvalues 3, 2, 1 along the axes.
        double[] matrix = new double[] {
                3.0, 0.0, 0.0,
                0.0, 2.0, 0.0,
                0.0, 0.0, 1.0
        };
        RotationMath.EigenDecomposition eigen = RotationMath.symmetricEigen3x3(matrix);
        double[] values = eigen.values;
        double[] vectors = eigen.vectors;
        Assert.assertEquals(3.0, values[0], 1e-9);
        Assert.assertEquals(2.0, values[1], 1e-9);
        Assert.assertEquals(1.0, values[2], 1e-9);
        // Eigenvectors are unit and mutually orthogonal.
        Assert.assertEquals(1.0, norm(Arrays.copyOfRange(vectors, 0, 3)), 1e-9);
        Assert.assertEquals(1.0, norm(Arrays.copyOfRange(vectors, 3, 6)), 1e-9);
        Assert.assertEquals(1.0, norm(Arrays.copyOfRange(vectors, 6, 9)), 1e-9);
    }

    @Test
    public void basisRotationIsExactForAPureRotation() {
        double[] truth = RotationMath.rotationAboutAxis(normalized(new double[] {0.3, 1.0, 0.0}), 0.7);
        double[] a1 = normalized(new double[] {1.0, 0.2, 0.1});
        double[] a2 = normalized(new double[] {0.1, 1.0, -0.3});
        double[] b1 = RotationMath.apply(truth, a1);
        double[] b2 = RotationMath.apply(truth, a2);
        double[] estimate = RotationMath.basisRotation(a1, b1, a2, b2);
        Assert.assertEquals(0.0, RotationMath.angle(estimate, truth), 1e-9);
    }

    @Test
    public void ransacRecoversARotationDespiteAThirdOfTheMatchesLying() {
        double[] truth = RotationMath.rotationAboutAxis(normalized(new double[] {0.2, 0.9, 0.3}), 0.5);
        double[][] from = new double[300][];
        double[][] to = new double[300][];
        for (int index = 0; index < 300; index++) {
            from[index] = randomUnit();
            if (index % 3 == 0) {
                // Outlier: a completely wrong correspondence.
                to[index] = randomUnit();
            } else {
                // Inlier with a degree of measurement noise.
                to[index] = perturb(RotationMath.apply(truth, from[index]), Math.toRadians(0.4));
            }
        }

        RotationMath.RotationEstimate estimate = RotationMath.estimateRotation(
                from,
                to,
                Math.toRadians(1.5),
                400,
                new Random(7),
                100);

        Assert.assertNotNull("RANSAC should find a consensus", estimate);
        Assert.assertTrue("inlier count " + estimate.getInlierCount(), estimate.getInlierCount() >= 150);
        Assert.assertTrue(
                "recovered rotation off by "
                        + Math.toDegrees(RotationMath.angle(estimate.rotation, truth)) + "°",
                RotationMath.angle(estimate.rotation, truth) < Math.toRadians(0.6));
    }

    @Test
    public void rotationAveragingRecoversTheAbsoluteRotationsFromMeasuredEdges() {
        int count = 8;
        List<double[]> truth = new ArrayList<double[]>();
        for (int index = 0; index < count; index++) {
            truth.add(RotationMath.rotationAboutAxis(normalized(new double[] {0.1, 1.0, 0.2}), index * 0.5));
        }

        // A ring of relative rotations with small measurement noise — the
        // ~0.2° residual a RANSAC rotation fit leaves behind. The initial poses
        // carry the larger ~1° sensor error the averaging is meant to remove.
        List<RotationMath.RotationEdge> edges = new ArrayList<RotationMath.RotationEdge>();
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double[] trueRelative = RotationMath.multiply(RotationMath.transpose(truth.get(j)), truth.get(i));
            double[] noisy = RotationMath.multiply(
                    RotationMath.rotationAboutAxis(randomUnit(), 0.003),
                    trueRelative);
            edges.add(new RotationMath.RotationEdge(i, j, noisy, 1.0));
        }

        // The anchor is exact; every other frame is given a sensor-style error.
        List<double[]> initial = new ArrayList<double[]>();
        for (int index = 0; index < count; index++) {
            if (index == 0) {
                initial.add(truth.get(0));
            } else {
                initial.add(RotationMath.multiply(
                        RotationMath.rotationAboutAxis(randomUnit(), 0.01),
                        truth.get(index)));
            }
        }

        List<double[]> refined = RotationMath.averageRotations(initial, edges, 20);
        for (int i = 0; i < count; i++) {
            double error = RotationMath.angle(refined.get(i), truth.get(i));
            Assert.assertTrue("frame " + i + " off by " + Math.toDegrees(error) + "°", error < Math.toRadians(0.5));
        }
    }

    @Test
    public void theWeightedMeanOfIdenticalRotationsIsThatRotation() {
        double[] rotation = RotationMath.rotationAboutAxis(normalized(new double[] {1.0, 0.0, 0.0}), 1.0);
        double[] mean = RotationMath.weightedMeanRotation(
                Arrays.asList(rotation, rotation, rotation),
                Arrays.asList(1.0, 2.0, 0.5));
        Assert.assertEquals(0.0, RotationMath.angle(mean, rotation), 1e-6);
    }

    @Test
    public void theFocalWarpDerivativeMatchesANumericDifference() {
        // The solver's derivative model must describe the actual physics of a
        // focal-length error: a correction of δ divides the tangent of every
        // measured bearing by e^δ, pulling it radially toward or away from the
        // optical axis. Check the closed form against a central difference of
        // exactly that model — the same way PoseRefiner evaluates it numerically.
        double[] bearing = normalized(new double[] {0.35, -0.6, 0.8});
        double h = 1e-5;
        double[] plus = correctedBearing(bearing, h);
        double[] minus = correctedBearing(bearing, -h);
        double[] numeric = new double[] {
                (plus[0] - minus[0]) / (2 * h),
                (plus[1] - minus[1]) / (2 * h),
                (plus[2] - minus[2]) / (2 * h)
        };
        double[] analytic = focalDerivative(bearing);
        Assert.assertEquals(numeric[0], analytic[0], 1e-5);
        Assert.assertEquals(numeric[1], analytic[1], 1e-5);
        Assert.assertEquals(numeric[2], analytic[2], 1e-5);
    }

    @Test
    public void perFrameFocalCorrectionsAreRecoveredFromMatchedBearings() {
        // A sweep of cameras, each recording its bearings through a per-frame
        // focal-length error. The solver should put each camera back onto its
        // true focal length, with the anchor (frame 0) held at its reported one.
        int frameCount = 6;
        double[] focalScales = new double[] {1.0, 1.04, 0.97, 1.08, 1.02, 0.95};
        List<RotationMath.FocalCorrespondence> correspondences =
                focalCorrespondences(frameCount, focalScales, new Random(7));
        Assert.assertTrue("wanted rich overlaps, got " + correspondences.size(), correspondences.size() >= 400);

        double[] delta = RotationMath.refineFocalLengths(correspondences, frameCount);

        Assert.assertEquals("the anchor keeps its reported focal length", 0.0, delta[0], 1e-12);
        for (int frame = 1; frame < frameCount; frame++) {
            double expected = Math.log(focalScales[frame]);
            Assert.assertEquals(
                    "frame " + frame + " wants log(" + focalScales[frame] + ") = " + expected,
                    expected, delta[frame], 0.02);
        }
    }

    @Test
    public void correctlyReportedFocalLengthsDrawNoCorrection() {
        int frameCount = 5;
        double[] scales = new double[frameCount];
        Arrays.fill(scales, 1.0);
        List<RotationMath.FocalCorrespondence> correspondences =
                focalCorrespondences(frameCount, scales, new Random(11));
        double[] delta = RotationMath.refineFocalLengths(correspondences, frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            Assert.assertEquals("frame " + frame, 0.0, delta[frame], 1e-3);
        }
    }

    @Test
    public void aDegenerateSolveIsClampedAndLeavesUntouchedFramesAlone() {
        // One correspondence whose derivative is a thousandth of its residual
        // would demand a thousandfold focal change; the clamp stops it at 15%.
        RotationMath.FocalCorrespondence correspondence = new RotationMath.FocalCorrespondence(
                0,
                1,
                new double[] {1.0, 0.0, 0.0},
                new double[] {0.0, 1.0, 0.0},
                new double[] {0.0, 0.0, 0.0},
                new double[] {0.001, 0.0, 0.0});
        double[] delta = RotationMath.refineFocalLengths(Arrays.asList(correspondence), 4);
        Assert.assertEquals(0.0, delta[0], 1e-12);
        Assert.assertEquals(0.15, delta[1], 1e-12);
        // Frames the correspondences never touched keep their reported focal.
        Assert.assertEquals(0.0, delta[2], 1e-12);
        Assert.assertEquals(0.0, delta[3], 1e-12);
    }

    /**
     * The correspondences a ring of {@code frameCount} level cameras would measure
     * for shared scene points, with each camera's bearings recorded through a
     * focal-length error of {@code focalScales[frame]}.
     */
    private List<RotationMath.FocalCorrespondence> focalCorrespondences(
            int frameCount,
            double[] focalScales,
            Random random) {
        // A sweep at 30° steps rather than a closed ring: cameras that face
        // each other share no front-hemisphere points, and the solver needs
        // plenty of shared points to separate focal error from noise.
        double[][] rotations = new double[frameCount][];
        for (int frame = 0; frame < frameCount; frame++) {
            double yaw = frame * (Math.PI / 6.0);
            rotations[frame] = CameraBasis.of(
                    new CameraPose((float) Math.toDegrees(yaw), 0f, 0f)
            ).toRotationMatrix();
        }
        List<double[]> directions = new ArrayList<double[]>();
        for (int i = 0; i < 1200; i++) {
            directions.add(normalized(new double[] {
                    random.nextDouble() - 0.5,
                    random.nextDouble() - 0.5,
                    0.5 + random.nextDouble() * 0.5
            }));
        }
        List<RotationMath.FocalCorrespondence> correspondences =
                new ArrayList<RotationMath.FocalCorrespondence>();
        for (int first = 0; first < frameCount; first++) {
            for (int second = first + 1; second < frameCount; second++) {
                double[] relative = RotationMath.multiply(
                        RotationMath.transpose(rotations[second]),
                        rotations[first]);
                for (int d = 0; d < directions.size(); d++) {
                    double[] direction = directions.get(d);
                    double[] fromBearing = RotationMath.apply(
                            RotationMath.transpose(rotations[first]), direction);
                    double[] toBearing = RotationMath.apply(
                            RotationMath.transpose(rotations[second]), direction);
                    if (fromBearing[2] < 0.35 || toBearing[2] < 0.35) {
                        continue;
                    }
                    double[] measuredFrom = focalWarp(fromBearing, focalScales[first]);
                    double[] measuredTo = focalWarp(toBearing, focalScales[second]);
                    correspondences.add(new RotationMath.FocalCorrespondence(
                            first,
                            second,
                            RotationMath.apply(relative, measuredFrom),
                            measuredTo,
                            RotationMath.apply(relative, focalDerivative(measuredFrom)),
                            focalDerivative(measuredTo)));
                }
            }
        }
        return correspondences;
    }

    /** The bearing a pixel records when the focal length is {@code scale}× reported. */
    private double[] focalWarp(double[] bearing, double scale) {
        return normalized(new double[] {bearing[0] * scale, bearing[1] * scale, bearing[2]});
    }

    /**
     * The corrected bearing after a log-scale focal correction of {@code delta}: the
     * tangent is divided by {@code e^delta}.
     */
    private double[] correctedBearing(double[] bearing, double delta) {
        return normalized(new double[] {
                bearing[0] * Math.exp(-delta),
                bearing[1] * Math.exp(-delta),
                bearing[2]
        });
    }

    /**
     * {@code d(bearing)/d(ln scale)} for the correction above: {@code (−x, −y, 0) + (x²+y²)·p}.
     */
    private double[] focalDerivative(double[] bearing) {
        double x = bearing[0];
        double y = bearing[1];
        double r2 = x * x + y * y;
        return new double[] {-x + r2 * x, -y + r2 * y, r2 * bearing[2]};
    }

    private double[] randomUnit() {
        double[] candidate;
        do {
            candidate = new double[] {
                    unseeded.nextDouble() - 0.5,
                    unseeded.nextDouble() - 0.5,
                    unseeded.nextDouble() - 0.5
            };
        } while (norm(candidate) < 1e-6);
        return normalized(candidate);
    }

    /** Rotates {@code v} by up to {@code angleRad} about a random axis. */
    private double[] perturb(double[] v, double angleRad) {
        return RotationMath.apply(RotationMath.rotationAboutAxis(randomUnit(), angleRad), v);
    }

    private double[] normalized(double[] v) {
        double magnitude = norm(v);
        return new double[] {v[0] / magnitude, v[1] / magnitude, v[2] / magnitude};
    }

    private double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private void assertVector(double[] expected, double[] actual) {
        double dot = expected[0] * actual[0] + expected[1] * actual[1] + expected[2] * actual[2];
        Assert.assertEquals("angle between them", 0.0, Math.acos(coerceIn(dot, -1.0, 1.0)), 1e-9);
        // Also check it is not the reflection.
        Assert.assertEquals(expected[0], actual[0], 1e-6);
        Assert.assertEquals(expected[1], actual[1], 1e-6);
        Assert.assertEquals(expected[2], actual[2], 1e-6);
    }

    private static double coerceIn(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
