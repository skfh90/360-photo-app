package com.n30dyn4m1c.photosphere.camera;

import org.junit.Assert;
import org.junit.Test;

public class SharpnessSelectionTest {

    private int argb(int r, int g, int b) {
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private int[] flatPixels(int width, int height, int value) {
        int[] pixels = new int[width * height];
        int color = argb(value, value, value);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = color;
        }
        return pixels;
    }

    private int[] checkerboardPixels(int width, int height) {
        int dark = argb(0, 0, 0);
        int light = argb(255, 255, 255);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            if ((((i / width) + (i % width)) % 2) == 0) {
                pixels[i] = dark;
            } else {
                pixels[i] = light;
            }
        }
        return pixels;
    }

    @Test
    public void aSceneFullOfEdgesScoresHigherThanAFlatOne() {
        float sharp = SharpnessSelection.laplacianVariance(checkerboardPixels(32, 32), 32, 32);
        float flat = SharpnessSelection.laplacianVariance(flatPixels(32, 32, 128), 32, 32);

        Assert.assertTrue(sharp > flat);
    }

    @Test
    public void aPerfectlyFlatFrameScoresZero() {
        Assert.assertEquals(
                0f,
                SharpnessSelection.laplacianVariance(flatPixels(32, 32, 128), 32, 32),
                1e-6f);
    }

    @Test
    public void aFlatFrameScoresZeroWhateverItsBrightness() {
        Assert.assertEquals(
                0f,
                SharpnessSelection.laplacianVariance(flatPixels(32, 32, 12), 32, 32),
                1e-6f);
    }

    @Test
    public void framesTooSmallToScoreAreTreatedAsFlat() {
        Assert.assertEquals(
                0f,
                SharpnessSelection.laplacianVariance(new int[2 * 2], 2, 2),
                1e-6f);
    }
}
