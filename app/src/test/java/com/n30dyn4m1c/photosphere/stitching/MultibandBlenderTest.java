package com.n30dyn4m1c.photosphere.stitching;

import org.junit.Assert;
import org.junit.Test;

/**
 * Covers the pure-Java parts of the multi-band blend: how a canvas is split
 * into pyramid levels, and how footprints travel between levels. The pyramid
 * arithmetic itself is exercised on a device, where OpenCV's native library
 * is available.
 */
public class MultibandBlenderTest {

    @Test
    public void aTinyCanvasKeepsASingleLevel() {
        int[] heights = new int[] {1, 4, 8};
        for (int i = 0; i < heights.length; i++) {
            int height = heights[i];
            Assert.assertEquals(
                    "height " + height + " should not split",
                    1,
                    MultibandBlender.levelCountFor(height));
        }
    }

    @Test
    public void theCanvasIsSplitSoTheCoarsestLevelIsAboutEightRowsTall() {
        // 16 rows split once into 8, 32 rows twice into 8, and so on.
        Assert.assertEquals(2, MultibandBlender.levelCountFor(16));
        Assert.assertEquals(3, MultibandBlender.levelCountFor(32));
        Assert.assertEquals(4, MultibandBlender.levelCountFor(64));
        Assert.assertEquals(5, MultibandBlender.levelCountFor(128));
        Assert.assertEquals(6, MultibandBlender.levelCountFor(256));
    }

    @Test
    public void theLevelCountCapsSoThePassesStayWorthwhile() {
        // 2048 rows would happily split into 8 levels; the cap keeps the extra
        // passes out.
        Assert.assertEquals(MultibandBlender.MAX_LEVELS, MultibandBlender.levelCountFor(2048));
        Assert.assertEquals(MultibandBlender.MAX_LEVELS, MultibandBlender.levelCountFor(4096));
        Assert.assertEquals(MultibandBlender.MAX_LEVELS, MultibandBlender.levelCountFor(100000));
    }

    @Test
    public void aBrokenCanvasIsTreatedAsSingleLevel() {
        Assert.assertEquals(1, MultibandBlender.levelCountFor(0));
        Assert.assertEquals(1, MultibandBlender.levelCountFor(-5));
    }

    @Test
    public void levelZeroIsTheCanvasItself() {
        MultibandBlender.LevelSize full = MultibandBlender.levelSize(4096, 2048, 0);
        Assert.assertEquals(4096, full.width);
        Assert.assertEquals(2048, full.height);
        MultibandBlender.LevelSize small = MultibandBlender.levelSize(512, 256, 0);
        Assert.assertEquals(512, small.width);
        Assert.assertEquals(256, small.height);
    }

    @Test
    public void eachLevelIsHalfThePreviousInBothAxes() {
        assertLevelSize(2048, 1024, MultibandBlender.levelSize(4096, 2048, 1));
        assertLevelSize(1024, 512, MultibandBlender.levelSize(4096, 2048, 2));
        assertLevelSize(512, 256, MultibandBlender.levelSize(4096, 2048, 3));
        assertLevelSize(64, 32, MultibandBlender.levelSize(4096, 2048, 6));
    }

    @Test
    public void oddSizesRoundDownButNeverToNothing() {
        // A 72-row ring band: level 2 is 18 rows, level 3 is 9.
        Assert.assertEquals(72, MultibandBlender.levelSize(4096, 72, 0).height);
        Assert.assertEquals(36, MultibandBlender.levelSize(4096, 72, 1).height);
        Assert.assertEquals(18, MultibandBlender.levelSize(4096, 72, 2).height);
        Assert.assertEquals(9, MultibandBlender.levelSize(4096, 72, 3).height);
        // Far past the canvas the level is still one pixel wide.
        assertLevelSize(1, 1, MultibandBlender.levelSize(3, 3, 4));
    }

    @Test
    public void levelOneHalvesAFootprint() {
        CanvasFootprint scaled = EquirectangularRenderer.scaleFootprint(
                new CanvasFootprint(400, 800, 300, 400),
                2);
        Assert.assertTrue(scaled.startColumn >= 199 && scaled.startColumn <= 201);
        Assert.assertTrue(scaled.startRow >= 149 && scaled.startRow <= 151);
        Assert.assertTrue(scaled.columnSpan >= 400 && scaled.columnSpan <= 402);
        Assert.assertTrue(scaled.rowSpan >= 200 && scaled.rowSpan <= 202);
    }

    @Test
    public void scalingASeamWrappingFootprintKeepsItOnTheLeftOfZero() {
        // A footprint straddling the ±180° seam unwraps to negative columns;
        // scaling must not round -3 toward zero and lose the wrapped edge.
        CanvasFootprint scaled = EquirectangularRenderer.scaleFootprint(
                new CanvasFootprint(-3, 600, 0, 100),
                2);
        Assert.assertTrue("wrapped edge was rounded toward zero", scaled.startColumn <= -1);
    }

    @Test
    public void scalingByOneLeavesTheFootprintUntouched() {
        CanvasFootprint footprint = new CanvasFootprint(-3, 600, 4, 100);
        Assert.assertEquals(footprint, EquirectangularRenderer.scaleFootprint(footprint, 1));
    }

    @Test
    public void everyBandFitsInsideItsUpsampledRegion() {
        // A full sphere: level 1 of a 4096x2048 canvas is 1024 tall, bands of 128.
        for (int band = 0; band < 8; band++) {
            int bandTop = band * 128;
            MultibandBlender.BandGeometry g = MultibandBlender.bandGeometry(bandTop, 128, 2048, 1024);
            Assert.assertNotNull(g);
            Assert.assertTrue(
                    "band " + band + " crop runs off its region",
                    g.localTop + g.bandRows <= g.upRows);
            Assert.assertTrue(
                    "band " + band + " region past the coarse level",
                    g.coarseTop + g.coarseRows <= 1024);
        }
    }

    @Test
    public void anOddCanvasHeightKeepsTheLastBandInBounds() {
        // A 72-degree ring over 360 degrees of longitude: 4096x819 canvas.
        // Level 1 is 409 rows tall, so 818 maps to coarse row 409 (clamped to
        // 408) and the naive 2x2 coarseRows upsample is one row short — this
        // used to throw a native CvException from submat.
        int lastBandTop = 6 * 128; // 768
        MultibandBlender.BandGeometry g = MultibandBlender.bandGeometry(lastBandTop, 128, 819, 409);
        Assert.assertNotNull(g);
        Assert.assertTrue(g.localTop + g.bandRows <= g.upRows);
        Assert.assertTrue(g.coarseTop + g.coarseRows <= 409);
    }

    @Test
    public void oddLevelsAtEveryScaleStayInBounds() {
        // 819 >> 4 = 51 rows, reconstructed from level 5 (25 rows).
        MultibandBlender.BandGeometry g = MultibandBlender.bandGeometry(0, 51, 51, 25);
        Assert.assertNotNull(g);
        Assert.assertTrue(g.localTop + g.bandRows <= g.upRows);
        Assert.assertTrue(g.coarseTop + g.coarseRows <= 25);
    }

    @Test
    public void theUpsampledRegionIsNeverSmallerThanTheBand() {
        // Sweep odd and even canvas heights and every band position.
        for (int canvasHeight = 9; canvasHeight <= 300; canvasHeight++) {
            int coarseHeight = canvasHeight / 2;
            int bandTop = 0;
            while (bandTop < canvasHeight) {
                int bandHeight = Math.min(128, canvasHeight - bandTop);
                MultibandBlender.BandGeometry g =
                        MultibandBlender.bandGeometry(bandTop, bandHeight, canvasHeight, coarseHeight);
                if (g == null) {
                    Assert.fail("h=" + canvasHeight + " bandTop=" + bandTop);
                }
                Assert.assertTrue(g.localTop + g.bandRows <= g.upRows);
                Assert.assertTrue(g.coarseTop + g.coarseRows <= coarseHeight);
                bandTop += 128;
            }
        }
    }

    @Test
    public void emptyBandsReconstructToNothing() {
        Assert.assertEquals(null, MultibandBlender.bandGeometry(2048, 128, 2048, 1024));
        Assert.assertEquals(null, MultibandBlender.bandGeometry(0, 128, 0, 0));
    }

    @Test
    public void aCoarseFootprintIsNeverEmpty() {
        CanvasFootprint scaled = EquirectangularRenderer.scaleFootprint(
                new CanvasFootprint(10, 20, 10, 20),
                16);
        Assert.assertTrue(scaled.columnSpan > 0);
        Assert.assertTrue(scaled.rowSpan > 0);
    }

    private static void assertLevelSize(int width, int height, MultibandBlender.LevelSize size) {
        Assert.assertEquals(width, size.width);
        Assert.assertEquals(height, size.height);
    }
}
