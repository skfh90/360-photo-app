package com.n30dyn4m1c.photosphere.stitching;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Burt–Adelson multi-band (Laplacian pyramid) blending, in the streaming form
 * the renderer's banded canvas requires.
 *
 * <b>Why not the feathered mean.</b> A cross-fade resolves an overlap by fading
 * detail across the whole width of it, so a seam that is even a pixel off
 * shows up twice: the transition is so wide that both copies of an edge are
 * visible inside it. Multi-band blending splits every frame into frequency
 * bands and blends those bands with *progressively wider* masks — a narrow
 * mask for the fine bands, a wide one for the coarse bands. Fine detail is
 * faded across the few pixels the frames genuinely disagree on, while broad
 * illumination is faded across the whole overlap. A seam disappears because at
 * every scale the transition is narrower than the detail it carries.
 *
 * <b>The maths.</b> Each frame is decomposed into a Laplacian pyramid
 * {@code L_l = G_l − EXPAND(G_{l+1})} ending in a coarse Gaussian {@code G_L}, and its
 * feather mask into a Gaussian pyramid {@code M_l}. Every band is blended by the
 * normalised weighted mean, and the bands are added back from coarse to fine:
 *
 * <pre>
 * acc_L = Σ G_i,L·M_i,L / Σ M_i,L
 * acc_l = EXPAND(acc_{l+1}) + Σ L_i,l·M_i,l / Σ M_i,l
 * </pre>
 *
 * {@code acc_0} is the finished blend. For a single frame the recurrence collapses
 * to the pyramid reconstruction identity {@code G_0 = G_L + Σ EXPAND(L_l)} — the
 * frame itself — so a lone frame comes through untouched; in an overlap each
 * band is the feathered mean *restricted to one band*, which is exactly the
 * property that makes multi-band work.
 *
 * <b>What this file owns.</b> The pyramid arithmetic: how many levels a canvas
 * warrants, the EXPAND step, the normalised division, and {@link #reconstructBand},
 * the core recurrence evaluated over one horizontal band. It never touches a
 * frame's pixels — the renderer accumulates each level's weighted colour and
 * weight sums, hands them here to be rebuilt, and paints the result. The
 * reconstruction is banded too, so the peak memory of a blend is a few
 * band-sized buffers plus the half-resolution accumulators; the coarse levels
 * that carry the low-frequency cross-fade live in mats a small fraction of the
 * canvas.
 */
public final class MultibandBlender {

    /**
     * Most pyramid levels a canvas is split into. Each extra level is another
     * full pass over the frames at a quarter of the size of the last, so
     * beyond ~6 levels the wide cross-fade gains nothing while the passes keep
     * costing. Six levels over a 2048-tall canvas leave a 64-row coarse
     * Gaussian, which is already deep into "broad illumination" territory.
     */
    public static final int MAX_LEVELS = 6;

    /**
     * Added to the weight sum before the normalised division, so an uncovered
     * pixel divides 0 by ε rather than 0 by 0. Small enough not to move real
     * pixels, whose weights are orders of magnitude larger.
     */
    public static final double WEIGHT_EPSILON = 1e-6;

    /**
     * Width and height of one pyramid level. Replaces the Kotlin {@code Pair<Int, Int>}.
     */
    public static final class LevelSize {
        public final int width;
        public final int height;

        public LevelSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    /**
     * One level's weighted colour sum and its mask sum — the two things
     * {@link #reconstructBand} needs to rebuild that level's band.
     */
    public static final class PyramidLevel {
        public final Mat color;
        public final Mat weight;

        public PyramidLevel(Mat color, Mat weight) {
            this.color = color;
            this.weight = weight;
        }

        /** Releases both mats. Safe to call again; releasing is idempotent. */
        public void release() {
            color.release();
            weight.release();
        }
    }

    /**
     * The layout one band's reconstruction uses. All rects derived from it are
     * in bounds by construction: {@code coarseTop + coarseRows ≤ coarseHeight} and
     * {@code localTop + bandRows = upRows}.
     */
    public static final class BandGeometry {
        public final int bandRows;
        public final int coarseTop;
        public final int coarseRows;
        public final int localTop;
        public final int upRows;

        public BandGeometry(int bandRows, int coarseTop, int coarseRows, int localTop, int upRows) {
            this.bandRows = bandRows;
            this.coarseTop = coarseTop;
            this.coarseRows = coarseRows;
            this.localTop = localTop;
            this.upRows = upRows;
        }
    }

    private MultibandBlender() {}

    /**
     * How many bands a {@code canvasHeight}-tall canvas splits into (levels {@code 0..n-1}).
     *
     * The coarsest band should still be a meaningful Gaussian of the frames —
     * about 8 rows tall — so the count is {@code log2(height / 8)} capped at
     * {@link #MAX_LEVELS}. A canvas shorter than 8 rows keeps a single level, which
     * degrades to the feathered mean the renderer fell back to.
     */
    public static int levelCountFor(int canvasHeight) {
        if (canvasHeight <= 0) return 1;
        int height = canvasHeight;
        int levels = 1;
        while (height > 8 && levels < MAX_LEVELS) {
            height /= 2;
            levels++;
        }
        return levels;
    }

    /** Width and height of pyramid level {@code level} of a {@code width}×{@code height} canvas. */
    public static LevelSize levelSize(int width, int height, int level) {
        int shift = Math.max(level, 0);
        return new LevelSize(Math.max(width >> shift, 1), Math.max(height >> shift, 1));
    }

    /**
     * The rectangles one band's reconstruction touches, so the Mat work stays
     * in bounds.
     *
     * A band of canvas rows {@code [bandTop, bandTop + bandRows)} is rebuilt from
     * the coarser level's rows that cover it, {@code [coarseTop, coarseTop + coarseRows)},
     * upsampled to {@code upRows} rows. {@code localTop} is where the band starts inside
     * the upsampled region.
     *
     * The upsampled region is sized to *exactly* {@code localTop} + {@code bandRows} rows,
     * not the naive {@code 2 × coarseRows}: a canvas height that is not a power of
     * two gives odd level heights, where the last band's rows reach further
     * than a clean ×2 of its coarse rows (the topmost fine rows all read the
     * last coarse row, clamped). Growing the region keeps every crop in bounds
     * and matches the clamped full-level upsample at the edge — a too-small
     * region throws a native {@code CvException} inside {@code submat}.
     *
     * Returns null when the band is empty or reaches nothing in the coarser
     * level.
     */
    public static BandGeometry bandGeometry(
        int bandTop,
        int bandHeight,
        int canvasHeight,
        int coarseHeight
    ) {
        int bandRows = Math.min(bandHeight, canvasHeight - bandTop);
        if (bandRows <= 0) return null;
        int coarseTop = clamp(bandTop / 2 - 1, 0, coarseHeight);
        int coarseBottom = clamp((bandTop + bandRows) / 2 + 1, coarseTop, coarseHeight);
        int coarseRows = coarseBottom - coarseTop;
        if (coarseRows <= 0) return null;
        int localTop = bandTop - coarseTop * 2;
        return new BandGeometry(bandRows, coarseTop, coarseRows, localTop, localTop + bandRows);
    }

    /**
     * The normalised image {@code color / (weight + ε)}, with the single-channel
     * weight broadcast to the colour's three channels.
     *
     * This is the whole of the coarsest level's blend, and the final step of
     * every other level's recurrence. The ε guards the uncovered pixels.
     */
    public static Mat normalizeColor(Mat color, Mat weight) {
        Mat weightWithEpsilon = new Mat();
        Mat weight3 = new Mat();
        Mat out = new Mat();
        try {
            Core.add(weight, new Scalar(WEIGHT_EPSILON), weightWithEpsilon);
            List<Mat> channels = new ArrayList<Mat>(3);
            channels.add(weightWithEpsilon);
            channels.add(weightWithEpsilon);
            channels.add(weightWithEpsilon);
            Core.merge(channels, weight3);
            Core.divide(color, weight3, out);
        } finally {
            weightWithEpsilon.release();
            weight3.release();
        }
        return out;
    }

    /**
     * Reconstructs one horizontal band of a pyramid level.
     *
     * {@code colorBand} and {@code weightBand} are this level's weighted sums over the
     * band's rows: a band-height Mat for the finest level, or a submat of the
     * full level elsewhere. {@code coarseAcc}, {@code coarseColor} and {@code coarseWeight} are
     * the next-coarser level's *reconstructed* image, colour sum and weight
     * sum, all full-canvas at that level. The band occupies canvas rows
     * {@code [bandTop, bandTop + bandHeight)}.
     *
     * The recurrence, in the streaming form the memory budget demands:
     *
     * <pre>
     * acc_l = EXPAND(acc_{l+1}) + (PWarp_l − EXPAND(PWarp_{l+1})·r_l) / (WSum_l + ε)
     * r_l   = WSum_l / (EXPAND(WSum_{l+1}) + ε)
     * </pre>
     *
     * with {@code PWarp_l = Σ W_i,l·M_i,l} and {@code WSum_l = Σ M_i,l} the renderer's
     * accumulators. The per-frame Laplacian sum {@code Σ L_i,l·M_i,l} is recovered
     * from them rather than summed per frame — that would hold a whole pyramid
     * per frame — and the {@code r_l} factor is what keeps that recovery honest at a
     * frame's border. Without it, the upsampled coarse content {@code EXPAND(PWarp_{l+1})}
     * stays nonzero one pixel past where the fine mask has already gone to
     * zero (the fine and coarse masks only coincide approximately), and a pixel
     * the frames just stop covering divides a nonzero band by ~ε. Scaling the
     * upsampled term by {@code WSum_l / EXPAND(WSum_{l+1})} makes the numerator vanish
     * exactly where {@code WSum_l} does — the ratio is ≈1 wherever the masks are
     * smooth and tends to zero with the fine mask — so uncovered pixels fall
     * back to the already-reconstructed coarser content instead of dividing by
     * nothing. The division then needs only the ε guard for genuinely blank
     * canvas, exactly as OpenCV's {@code MultiBandBlender} handles its own weight sums.
     *
     * Returns the reconstructed band, a {@code CV_32FC3} Mat {@code [bandHeight]} rows
     * tall that the caller owns.
     */
    public static Mat reconstructBand(
        Mat colorBand,
        Mat weightBand,
        Mat coarseAcc,
        Mat coarseColor,
        Mat coarseWeight,
        int canvasWidth,
        int canvasHeight,
        int coarseWidth,
        int coarseHeight,
        int bandTop,
        int bandHeight
    ) {
        int bandRows = Math.min(bandHeight, canvasHeight - bandTop);
        if (bandRows <= 0) return new Mat();

        // This level's rows come from the coarser level's rows that cover
        // them — [bandTop/2 − 1, (bandTop+bandRows)/2 + 1] — upsampled back.
        BandGeometry geometry = bandGeometry(bandTop, bandHeight, canvasHeight, coarseHeight);
        if (geometry == null) return new Mat();
        Mat coarseAccRegion = coarseAcc.submat(
            new Rect(0, geometry.coarseTop, coarseWidth, geometry.coarseRows)
        );
        Mat coarseColorRegion = coarseColor.submat(
            new Rect(0, geometry.coarseTop, coarseWidth, geometry.coarseRows)
        );
        Mat coarseWeightRegion = coarseWeight.submat(
            new Rect(0, geometry.coarseTop, coarseWidth, geometry.coarseRows)
        );
        Mat accUp = new Mat();
        Mat colorUp = new Mat();
        Mat weightUp = new Mat();
        Mat ratio = new Mat();
        Mat scaledColorUp = new Mat();
        Mat ratio3 = new Mat();
        Mat p = new Mat();
        Mat normalized = new Mat();
        Mat weightWithEpsilon = new Mat();
        Mat weight3 = new Mat();
        Mat result = new Mat();
        try {
            Size upSize = new Size((double) canvasWidth, (double) geometry.upRows);
            Imgproc.resize(coarseAccRegion, accUp, upSize, 0.0, 0.0, Imgproc.INTER_LINEAR);
            Imgproc.resize(coarseColorRegion, colorUp, upSize, 0.0, 0.0, Imgproc.INTER_LINEAR);
            Imgproc.resize(coarseWeightRegion, weightUp, upSize, 0.0, 0.0, Imgproc.INTER_LINEAR);
            Rect bandRegion = new Rect(0, geometry.localTop, canvasWidth, geometry.bandRows);
            Mat colorUpBand = colorUp.submat(bandRegion);
            Mat accUpBand = accUp.submat(bandRegion);
            Mat weightUpBand = weightUp.submat(bandRegion);
            try {
                // The band of the Laplacian sum: PWarp_l − EXPAND(PWarp_{l+1})·r_l.
                Core.add(weightUpBand, new Scalar(WEIGHT_EPSILON), ratio);
                Core.divide(weightBand, ratio, ratio);
                List<Mat> ratioChannels = new ArrayList<Mat>(3);
                ratioChannels.add(ratio);
                ratioChannels.add(ratio);
                ratioChannels.add(ratio);
                Core.merge(ratioChannels, ratio3);
                Core.multiply(colorUpBand, ratio3, scaledColorUp);
                Core.subtract(colorBand, scaledColorUp, p);
                Core.add(weightBand, new Scalar(WEIGHT_EPSILON), weightWithEpsilon);
                List<Mat> weightChannels = new ArrayList<Mat>(3);
                weightChannels.add(weightWithEpsilon);
                weightChannels.add(weightWithEpsilon);
                weightChannels.add(weightWithEpsilon);
                Core.merge(weightChannels, weight3);
                Core.divide(p, weight3, normalized);
                // Fold the already-reconstructed coarser content back in.
                Core.add(accUpBand, normalized, result);
            } finally {
                colorUpBand.release();
                accUpBand.release();
                weightUpBand.release();
            }
        } finally {
            coarseAccRegion.release();
            coarseColorRegion.release();
            coarseWeightRegion.release();
            accUp.release();
            colorUp.release();
            weightUp.release();
            ratio.release();
            scaledColorUp.release();
            ratio3.release();
            p.release();
            normalized.release();
            weightWithEpsilon.release();
            weight3.release();
        }
        return result;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
