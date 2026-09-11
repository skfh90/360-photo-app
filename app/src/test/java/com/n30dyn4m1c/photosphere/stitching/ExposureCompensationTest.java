package com.n30dyn4m1c.photosphere.stitching;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * The gain compensation that stops the blends showing seams of light.
 */
public class ExposureCompensationTest {

    @Test
    public void gainsEqualiseAPairOfFrames() {
        float[] gains = ExposureCompensation.solveGains(
                new double[] {100.0, 200.0},
                Arrays.asList(new int[] {0, 1}));

        // The corrected means agree, and the geometric mean of the gains is 1 so
        // the sphere is neither brightened nor darkened overall.
        Assert.assertEquals(100.0 * gains[0], 200.0 * gains[1], 1e-3);
        Assert.assertEquals(1.0, Math.sqrt((double) gains[0] * gains[1]), 1e-3);
    }

    @Test
    public void aConnectedChainOfFramesConvergesToConsistentGains() {
        double[] means = new double[] {80.0, 100.0, 130.0, 90.0};
        int[][] edges = new int[][] {{0, 1}, {1, 2}, {2, 3}};
        float[] gains = ExposureCompensation.solveGains(means, Arrays.asList(edges));

        for (int i = 0; i < edges.length; i++) {
            int a = edges[i][0];
            int b = edges[i][1];
            Assert.assertEquals(
                    "edge " + a + "-" + b + ": " + (means[a] * gains[a]) + " vs " + (means[b] * gains[b]),
                    means[a] * gains[a],
                    means[b] * gains[b],
                    1e-2);
        }
    }

    @Test
    public void framesTheGraphDoesNotReachKeepANeutralGain() {
        // Frame 2 is isolated: its gain must stay exactly 1 (the geometric mean
        // of the others' gains is what absorbs the global factor).
        float[] gains = ExposureCompensation.solveGains(
                new double[] {100.0, 200.0, 50.0},
                Arrays.asList(new int[] {0, 1}));

        Assert.assertEquals(1f, gains[2], 1e-6f);
        Assert.assertEquals(100.0 * gains[0], 200.0 * gains[1], 1e-3);
    }

    @Test
    public void identicalFramesGetAGainOfOne() {
        float[] gains = ExposureCompensation.solveGains(
                new double[] {120.0, 120.0, 120.0},
                Arrays.asList(new int[] {0, 1}, new int[] {1, 2}));

        for (int i = 0; i < gains.length; i++) {
            Assert.assertEquals(1f, gains[i], 1e-6f);
        }
    }

    @Test
    public void aRunawayGainIsClampedRatherThanBlowingOutAFrame() {
        // The whole-frame mean is a coarse stand-in for the brightness of the
        // shared region, so a frame that is half sky pulls its own gain in a
        // direction the overlap never asked for. Left unbounded, that lands a
        // blown-out patch on the sphere — worse than the seam it was shaving.
        float[] gains = ExposureCompensation.solveGains(
                new double[] {10.0, 240.0},
                Arrays.asList(new int[] {0, 1}));

        for (int i = 0; i < gains.length; i++) {
            Assert.assertTrue(
                    "gain " + gains[i] + " escaped the clamp",
                    gains[i] >= ExposureCompensation.MIN_GAIN
                            && gains[i] <= ExposureCompensation.MAX_GAIN);
        }
        // The clamp is symmetric in log space, so it does not tilt the sphere's
        // overall exposure one way or the other.
        Assert.assertEquals(1.0, Math.sqrt((double) gains[0] * gains[1]), 1e-3);
    }

    @Test
    public void aHealthySessionNeverReachesTheClamp() {
        double[] means = new double[] {96.0, 104.0, 112.0, 100.0};
        float[] gains = ExposureCompensation.solveGains(
                means,
                Arrays.asList(new int[] {0, 1}, new int[] {1, 2}, new int[] {2, 3}));
        for (int i = 0; i < gains.length; i++) {
            Assert.assertTrue(
                    "gain " + gains[i] + " is suspiciously large",
                    gains[i] >= 0.85f && gains[i] <= 1.18f);
        }
    }

    @Test
    public void aSelfEdgeCannotDivideAFramesGainByItself() {
        // Nothing in the pipeline produces one, but a degenerate edge would make
        // the frame its own neighbour and freeze its equation.
        float[] gains = ExposureCompensation.solveGains(
                new double[] {100.0, 200.0},
                Arrays.asList(new int[] {0, 0}, new int[] {0, 1}));
        Assert.assertEquals(100.0 * gains[0], 200.0 * gains[1], 1e-3);
    }

    @Test
    public void anEmptyGraphLeavesEveryFrameNeutral() {
        float[] gains = ExposureCompensation.solveGains(
                new double[] {80.0, 120.0},
                Collections.<int[]>emptyList());
        Assert.assertEquals(1f, gains[0], 1e-6f);
        Assert.assertEquals(1f, gains[1], 1e-6f);
    }
}
