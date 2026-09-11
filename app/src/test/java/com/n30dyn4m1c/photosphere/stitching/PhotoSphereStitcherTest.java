package com.n30dyn4m1c.photosphere.stitching;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Covers the parts of the pipeline that are pure arithmetic. The stitch itself needs
 * OpenCV's native library and real frames, so it belongs on a device.
 */
public class PhotoSphereStitcherTest {

    @Test
    public void registrationOutcomesAndMachineryFailuresStayInSeparateRanges() {
        Set<StitchStatus> registrationOutcomes = new HashSet<StitchStatus>();
        registrationOutcomes.add(StitchStatus.Ok);
        registrationOutcomes.add(StitchStatus.NeedMoreImages);
        registrationOutcomes.add(StitchStatus.AlignmentFailed);
        registrationOutcomes.add(StitchStatus.CameraEstimationFailed);

        StitchStatus[] all = StitchStatus.values();
        for (int i = 0; i < all.length; i++) {
            StitchStatus status = all[i];
            if (registrationOutcomes.contains(status)) {
                Assert.assertTrue(
                        status.name() + " should be a low code",
                        status.code >= 0 && status.code <= 3);
            } else {
                Assert.assertTrue(
                        status.name() + " should sit clear of the low range",
                        status.code >= 100);
            }
        }
    }

    @Test
    public void everyStatusCarriesItsOwnCode() {
        StitchStatus[] all = StitchStatus.values();
        Set<Integer> codes = new HashSet<Integer>();
        for (int i = 0; i < all.length; i++) {
            codes.add(all[i].code);
        }
        Assert.assertEquals(
                "codes are what a bug report is searched by, so they cannot collide",
                all.length,
                codes.size());
    }

    @Test
    public void theCanvasIsNeverRenderedWiderThanTheFramesJustify() {
        // A 1024px frame across 66° carries ~15.5 px per degree, so a full turn
        // is worth ~5585px — under the cap, which therefore does not bite.
        Assert.assertEquals(
                5584,
                PhotoSphereStitcher.canvasWidthFor(1024, 66f, 8192));
        // The same frames under a 4096 cap take the cap instead.
        Assert.assertEquals(
                4096,
                PhotoSphereStitcher.canvasWidthFor(1024, 66f, 4096));
    }

    @Test
    public void theCanvasWidthIsAlwaysEvenSoThe2To1CanvasHasWholeRows() {
        int[] frameWidths = new int[] {320, 512, 1024, 1600, 4000};
        float[] fovs = new float[] {37f, 66f, 90f, 121f};
        for (int i = 0; i < frameWidths.length; i++) {
            int frameWidth = frameWidths[i];
            for (int j = 0; j < fovs.length; j++) {
                float fov = fovs[j];
                int width = PhotoSphereStitcher.canvasWidthFor(frameWidth, fov, 4096);
                Assert.assertEquals("width " + width + " is odd for " + frameWidth + " px at " + fov + "°", 0, width % 2);
                Assert.assertTrue("width " + width + " is unusably small", width >= 512);
            }
        }
    }

    @Test
    public void theCanvasHeightFollowsTheLatitudeSpan() {
        // A full sphere: 180° of latitude across 360° of longitude is a 2:1 canvas.
        Assert.assertEquals(2048, PhotoSphereStitcher.canvasHeightFor(4096, 360f, 180f));
        // A ring spanning 72° of latitude is one fifth as tall as it is wide.
        Assert.assertEquals(819, PhotoSphereStitcher.canvasHeightFor(4096, 360f, 72f));
        // A hemisphere (180° × 180°) would be square.
        Assert.assertEquals(4096, PhotoSphereStitcher.canvasHeightFor(4096, 180f, 180f));
    }

    @Test
    public void subsamplingBringsTheLongEdgeUnderTheLimit() {
        // 4:3 sensor at 12MP, decoded for a 1024px working size.
        Assert.assertEquals(4, PhotoSphereStitcher.sampleSizeFor(4000, 3000, 1024));
        Assert.assertTrue(4000 / 4 <= 1024);
    }

    @Test
    public void aFrameAlreadySmallEnoughIsDecodedWhole() {
        Assert.assertEquals(1, PhotoSphereStitcher.sampleSizeFor(1024, 768, 1024));
        Assert.assertEquals(1, PhotoSphereStitcher.sampleSizeFor(640, 480, 1024));
    }

    @Test
    public void theFactorIsAlwaysAPowerOfTwo() {
        int[] widths = new int[] {1080, 2000, 4000, 8000, 12000};
        for (int i = 0; i < widths.length; i++) {
            int width = widths[i];
            int sample = PhotoSphereStitcher.sampleSizeFor(width, width * 3 / 4, 1024);
            Assert.assertTrue(sample + " is not a power of two", sample > 0 && (sample & (sample - 1)) == 0);
            Assert.assertTrue("long edge still over the limit", width / sample <= 1024);
        }
    }

    @Test
    public void aFieldOfViewThatMatchesTheFrameIsLeftAlone() {
        // Portrait 4:3 frame with the portrait screen FOV.
        Assert.assertNull(PhotoSphereStitcher.correctFovOrientation(750, 1000, 52f, 66f));
        // Landscape 4:3 frame with the landscape FOV.
        Assert.assertNull(PhotoSphereStitcher.correctFovOrientation(1000, 750, 66f, 52f));
    }

    @Test
    public void aTransposedFieldOfViewIsSwappedBack() {
        // The axes describe the sensor when the frame is the display's (or the
        // reverse); the stitcher must not let a frame believe it spans the
        // wrong angle.
        float[] corrected = PhotoSphereStitcher.correctFovOrientation(750, 1000, 66f, 52f);
        Assert.assertNotNull(corrected);
        Assert.assertEquals(52f, corrected[0], 1e-3f);
        Assert.assertEquals(66f, corrected[1], 1e-3f);
    }

    @Test
    public void aLooselyEstimatedFieldOfViewIsNotTouched() {
        // 10% error on a real lens still implies near-square pixels; only a
        // genuine transposition (the square of the aspect ratio) trips the swap.
        Assert.assertNull(PhotoSphereStitcher.correctFovOrientation(750, 1000, 52f * 1.1f, 66f * 0.95f));
    }

    @Test
    public void progressOnlyReportsAFractionWhenItHasOne() {
        Assert.assertNull(new StitchProgress(StitchStage.Stitching).getFraction());
        Assert.assertEquals(0.25f, new StitchProgress(StitchStage.Reading, 11, 44).getFraction(), 1e-4f);
        Assert.assertEquals(1f, new StitchProgress(StitchStage.Reading, 44, 44).getFraction(), 1e-4f);
    }
}
