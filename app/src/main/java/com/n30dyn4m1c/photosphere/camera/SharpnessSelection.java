package com.n30dyn4m1c.photosphere.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.List;

/**
 * Picks the sharpest of a burst of otherwise-identical frames.
 *
 * Every shot in a burst is captured at the same locked exposure, focus and
 * colour, so they differ only in what handheld motion did to them. The one to
 * keep is the sharpest, judged by the variance of the Laplacian — the classic
 * focus/sharpness metric: edges push the Laplacian response up, so a frame with
 * crisp edges scores higher than one blurred by shake.
 *
 * Frames are decoded downsampled before scoring. The two copies of a scene are
 * nearly identical, so a score only has to be right about <em>which</em> is sharper,
 * not about how sharp either one is; a coarse decode is enough and costs a few
 * milliseconds per frame.
 */
public final class SharpnessSelection {

    /** Long edge a frame is decoded to before scoring. */
    public static final int SCORE_MAX_DIMENSION = 320;

    private SharpnessSelection() {
    }

    /** The sharpest of {@code files}, decoded and scored; the first on a tie. */
    public static File pickSharpest(List<File> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("nothing to pick from");
        }
        if (files.size() == 1) {
            return files.get(0);
        }
        File best = files.get(0);
        float bestScore = laplacianVarianceOf(best);
        for (int i = 1; i < files.size(); i++) {
            File file = files.get(i);
            float score = laplacianVarianceOf(file);
            if (score > bestScore) {
                bestScore = score;
                best = file;
            }
        }
        return best;
    }

    /**
     * Variance of the Laplacian response over {@code pixels}, a 1-D array laid out
     * row-major as {@code width} × {@code height} ARGB ints.
     *
     * Pure, so it can be unit-tested without a device. Higher is sharper.
     */
    public static float laplacianVariance(int[] pixels, int width, int height) {
        if (pixels.length < width * height) {
            throw new IllegalArgumentException("pixel buffer is too small");
        }
        if (width < 3 || height < 3) {
            return 0f;
        }

        double sum = 0.0;
        double sumSquared = 0.0;
        int count = 0;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int i = row + x;
                double lap = luma(pixels[i - 1]) + luma(pixels[i + 1])
                        + luma(pixels[i - width]) + luma(pixels[i + width])
                        - 4.0 * luma(pixels[i]);
                sum += lap;
                sumSquared += lap * lap;
                count++;
            }
        }
        if (count == 0) {
            return 0f;
        }

        double mean = sum / count;
        return (float) ((sumSquared / count) - mean * mean);
    }

    private static double luma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    private static float laplacianVarianceOf(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return 0f;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSizeFor(
                bounds.outWidth,
                bounds.outHeight,
                SCORE_MAX_DIMENSION
        );
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) {
            return 0f;
        }
        // Read the dimensions out before recycling: a recycled bitmap's
        // accessors are not contractually defined, and scoring against whatever
        // they happen to return would silently pick the wrong frame.
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        return laplacianVariance(pixels, width, height);
    }

    /**
     * {@code BitmapFactory} only honours powers of two, so this returns one directly
     * rather than letting it round the value down and hand back something larger
     * than asked for.
     */
    private static int sampleSizeFor(int width, int height, int maxDimension) {
        if (maxDimension <= 0) {
            throw new IllegalArgumentException("maxDimension must be positive");
        }
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > maxDimension) {
            sample *= 2;
        }
        return sample;
    }
}
