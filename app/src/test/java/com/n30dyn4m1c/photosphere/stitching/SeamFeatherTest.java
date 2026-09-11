package com.n30dyn4m1c.photosphere.stitching;

import org.junit.Assert;
import org.junit.Test;

/**
 * Covers the pure-Java seam feathering: how the solved label map becomes the
 * narrow cross-fade the renderer looks up, and how the lookup maps pyramid
 * levels back onto the reduced grid.
 */
public class SeamFeatherTest {

    @Test
    public void boundaryPixelsGetTheOtherFrameWithAHalfWeightAtTheCut() {
        // Two frames split down the middle: frame 0 paints the left half,
        // frame 1 the right half.
        int[] labelMap = new int[] {
                0, 0, 1, 1,
                0, 0, 1, 1
        };
        SeamFeather.Result derived = SeamFeather.derive(labelMap, 4, 2, 2);
        int[] loser = derived.loserLabel;
        float[] weight = derived.loserWeight;

        // The pixel right next to the cut (column 1, frame 0's side) loses to
        // frame 1 at the nearest distance 1.
        Assert.assertEquals(1, loser[1]);
        Assert.assertEquals(0.5f * (1f - 1f / 3f), weight[1], 1e-6f);
        Assert.assertEquals(1, loser[4 + 1]);
        // Column 2, on frame 1's side, loses to frame 0.
        Assert.assertEquals(0, loser[2]);
        Assert.assertEquals(0, loser[4 + 2]);
    }

    @Test
    public void pixelsFarFromABoundaryKeepNoLosingFrame() {
        int[] labelMap = new int[] {
                0, 0, 0,
                0, 0, 0
        };
        SeamFeather.Result derived = SeamFeather.derive(labelMap, 3, 2, 2);
        int[] loser = derived.loserLabel;
        float[] weight = derived.loserWeight;
        for (int i = 0; i < loser.length; i++) {
            Assert.assertEquals(-1, loser[i]);
        }
        for (int i = 0; i < weight.length; i++) {
            Assert.assertEquals(0f, weight[i], 0f);
        }
    }

    @Test
    public void theWeightFallsMonotonicallyAwayFromTheSeam() {
        // A single vertical seam in the middle of a wide row.
        int width = 7;
        int[] labelMap = new int[width];
        for (int i = 0; i < width; i++) {
            labelMap[i] = i < 3 ? 0 : 1;
        }
        SeamFeather.Result derived = SeamFeather.derive(labelMap, width, 1, 2);
        int[] loser = derived.loserLabel;
        float[] weight = derived.loserWeight;
        Assert.assertEquals(0, loser[3]);
        Assert.assertEquals(1, loser[2]);
        // Weight at the boundary is highest and decays with distance.
        Assert.assertTrue("boundary weight is the half of the ramp", weight[2] > weight[1]);
        Assert.assertTrue("one pixel further the ramp is smaller still", weight[1] > weight[0]);
        // All weights stay within [0, 0.5].
        for (int i = 0; i < weight.length; i++) {
            Assert.assertTrue("weight " + weight[i] + " out of range", weight[i] >= 0f && weight[i] <= 0.5f);
        }
    }

    @Test
    public void uncoveredPixelsAreIgnoredByTheFeather() {
        // The middle column is never shot (-1); the two frames still carve a
        // seam, but no feather reaches across the gap.
        int[] labelMap = new int[] {0, 0, -1, 1, 1};
        SeamFeather.Result derived = SeamFeather.derive(labelMap, 5, 1, 2);
        int[] loser = derived.loserLabel;
        float[] weight = derived.loserWeight;
        Assert.assertEquals(-1, loser[2]);
        Assert.assertEquals(0f, weight[2], 1e-6f);
        Assert.assertEquals(1, loser[1]);
        Assert.assertEquals(0, loser[3]);
    }

    @Test
    public void theWeightLookupMapsAPyramidLevelBackToTheGrid() {
        // Grid scale 2: each grid pixel covers a 2x2 block of level-0 pixels.
        SeamWeights seams = new SeamWeights(
                2,
                1,
                2,
                new int[] {0, 1},
                new int[] {1, 0},
                new float[] {0.25f, 0.25f});
        // Level 0, left block: frame 0 wins at full strength.
        Assert.assertEquals(1f, seams.weightFor(0, 0, 0, 1), 1e-6f);
        Assert.assertEquals(0.25f, seams.weightFor(1, 0, 0, 1), 1e-6f);
        // Right block: frame 1 wins.
        Assert.assertEquals(1f, seams.weightFor(1, 0, 3, 1), 1e-6f);
        Assert.assertEquals(0.25f, seams.weightFor(0, 0, 3, 1), 1e-6f);
        // A frame that is neither winner nor loser contributes nothing.
        Assert.assertEquals(0f, seams.weightFor(2, 0, 0, 1), 1e-6f);
        // Level 1 halves the coordinates: (1, 1) at level 1 is (2, 2) at level 0,
        // which is the right block.
        Assert.assertEquals(1f, seams.weightFor(1, 1, 1, 2), 1e-6f);
    }
}
