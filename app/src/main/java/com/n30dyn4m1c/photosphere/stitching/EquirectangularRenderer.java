package com.n30dyn4m1c.photosphere.stitching;

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Paints frames onto an equirectangular canvas.
 *
 * Each output pixel is a direction on the sphere. For every frame that can see
 * that direction, the direction is rotated into the frame's own axes and divided
 * through by depth, which gives the pixel of the frame that looked at it — the
 * inverse of the projection the lens performed. {@code remap} then does the sampling.
 *
 * Where frames overlap, the result is a multi-band blend rather than whichever
 * frame was painted last. The feather weight becomes each frame's mask, and the
 * mask and the frame are each split into a Laplacian/Gaussian pyramid: fine
 * detail is faded across a narrow cross-fade, broad illumination across a wide
 * one, so a seam that survives one band does not survive the one that carried
 * it. See {@link MultibandBlender} for the maths.
 *
 * With a {@link SeamWeights} assignment the per-pixel feather is replaced by the seam
 * weights: the winning frame of each pixel contributes at full strength and
 * every other frame contributes nothing, except for a few pixels either side of
 * each cut where the loser's contribution ramps in — seam carving, with the
 * multi-band machinery left to handle the narrow transition. Outside an overlap
 * the weights are still all on one frame, so single-coverage detail comes
 * through untouched either way. See {@link SeamFinder}.
 *
 * <b>Memory.</b> A 4096-wide canvas needs 100 MB of float accumulator if it is
 * held all at once, which is exactly the kind of allocation that ends a stitch
 * on a mid-range phone. Every level is therefore built in horizontal bands:
 * only the band being accumulated exists in float, and each frame contributes
 * through the intersection of the band with its own footprint. The coarser
 * levels, which carry the wide cross-fade, live in mats a fraction of the
 * canvas. The total work is unchanged — every frame still touches each of its
 * pixels once at every level — but the peak is tens of megabytes rather than a
 * hundred.
 */
public final class EquirectangularRenderer {

    /** Rows accumulated at once. Sets the renderer's peak memory, with the width. */
    private static final int BAND_HEIGHT = 128;

    /** What a render produced, and how much of the sphere it reached. */
    public static final class Rendered {
        /** The finished {@code CV_8UC3} canvas. The caller owns it. */
        public final Mat canvas;
        /** Fraction of the canvas any frame reached, 0..1. */
        public final float coverage;

        public Rendered(Mat canvas, float coverage) {
            this.canvas = canvas;
            this.coverage = coverage;
        }
    }

    public interface ProgressListener {
        void onProgress(int completed, int total);
    }

    public interface CancelCheck {
        void checkCancelled();
    }

    private interface ColumnRun {
        void accept(int canvasColumn, int columnSpan);
    }

    private EquirectangularRenderer() {}

    /**
     * Renders {@code frames} into a {@code canvasWidth} × {@code canvasHeight} canvas.
     *
     * {@code gains}, when given, holds one brightness multiplier per frame, aligned by
     * position with {@code frames}; it is applied before the overlaps are blended so
     * the exposure compensation lands inside the blend rather than on top of
     * it. {@code onBandComplete} is called with the number of units finished so far
     * and the total, for progress reporting; {@code checkCancelled} is called at
     * every band and level boundary and may throw to abandon the render.
     *
     * {@code pivotRatio} is {@link PivotModel#getRatio()}: how far the lens sat from the axis the
     * user turned about, as a fraction of the scene's distance. Zero treats the
     * lens as the pivot, which is what a tripod gives and what a hand-held
     * capture never quite does.
     *
     * {@code seams}, when given, switches the overlaps from the wide cross-fade to the
     * seam-carved assignment (see {@link SeamWeights}): each pixel is painted by one
     * frame at full strength, and only a few pixels across each cut blend the
     * loser in. Without it the render behaves exactly as before.
     *
     * The four span/centre numbers describe the region of the sphere the canvas
     * holds (see {@link Equirectangular}); they default to a full 360°×180° sphere.
     * The canvas is expected to be sized to them — {@code canvasHeight} should be
     * {@code canvasWidth * latitudeSpan / longitudeSpan} for square pixels.
     */
    public static Rendered render(
        List<PreparedFrame> frames,
        int canvasWidth,
        int canvasHeight,
        float[] gains,
        SeamWeights seams,
        double pivotRatio,
        float longitudeSpanDegrees,
        float centerLongitudeDegrees,
        float latitudeSpanDegrees,
        float centerLatitudeDegrees,
        ProgressListener onBandComplete,
        CancelCheck checkCancelled
    ) {
        Mat canvas = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC3);
        long coveredPixels = 0L;
        // One set of scratch buffers for the whole render. A pole-covering frame
        // reaches the full canvas width, so a per-segment allocation would churn
        // tens of megabytes of float array per band across forty frames.
        SegmentScratch scratch = new SegmentScratch();

        // Progress is reported in roughly uniform units: one per coarse level,
        // one per band of the coarsest two levels' reconstruction. The single
        // level below, if there is one, just swaps the units.
        int levelCount = MultibandBlender.levelCountFor(canvasHeight);
        int levelOneBandCount = 0;
        if (levelCount >= 3) {
            int heightOne = MultibandBlender.levelSize(canvasWidth, canvasHeight, 1).height;
            levelOneBandCount = (heightOne + BAND_HEIGHT - 1) / BAND_HEIGHT;
        }
        int levelZeroBandCount = (canvasHeight + BAND_HEIGHT - 1) / BAND_HEIGHT;
        int progressTotal = (levelCount - 1) + levelOneBandCount + levelZeroBandCount;
        int progressDone = 0;

        // The coarse accumulator chain and the level-one accumulator are owned
        // at this scope, not inside the happy path, so a render that fails
        // midway can still release every pyramid mat the happy path had not
        // got to (see the catch below). Mat.release is idempotent: releasing
        // an accumulator the happy path already released — or one it aliases
        // (`levelOneAcc` is `acc` on a two-level canvas) — is safe.
        ArrayList<MultibandBlender.PyramidLevel> coarse =
            new ArrayList<MultibandBlender.PyramidLevel>(Math.max(levelCount - 1, 0));
        Mat acc = null;
        Mat levelOneAcc = null;

        try {
            if (levelCount <= 1) {
                // Degenerate canvas (shorter than the coarsest band): the
                // multi-band split has nowhere to go, so this is the plain
                // normalised mean the blend reduces to with a single level.
                for (int band = 0; band < levelZeroBandCount; band++) {
                    if (checkCancelled != null) checkCancelled.checkCancelled();
                    int bandTop = band * BAND_HEIGHT;
                    int bandHeight = Math.min(BAND_HEIGHT, canvasHeight - bandTop);
                    Mat color = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC3);
                    Mat weight = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC1);
                    try {
                        accumulateLevel(
                            frames, color, weight, canvasWidth, canvasHeight,
                            bandTop, bandHeight, 1, gains, seams, pivotRatio,
                            longitudeSpanDegrees, centerLongitudeDegrees,
                            latitudeSpanDegrees, centerLatitudeDegrees, scratch
                        );
                        coveredPixels += Core.countNonZero(weight);
                        Mat normalized = MultibandBlender.normalizeColor(color, weight);
                        Mat region = canvas.submat(new Rect(0, bandTop, canvasWidth, bandHeight));
                        try {
                            normalized.convertTo(region, CvType.CV_8UC3);
                        } finally {
                            region.release();
                        }
                        normalized.release();
                    } finally {
                        color.release();
                        weight.release();
                    }
                    progressDone++;
                    if (onBandComplete != null) onBandComplete.onProgress(progressDone, progressTotal);
                }
            } else {
                // -- Level accumulation --------------------------------------
                // Levels 1..L accumulate over their whole (small) canvas. Level
                // 0 accumulates band by band, later, once its coarser content
                // has been rebuilt.
                for (int level = 1; level < levelCount; level++) {
                    if (checkCancelled != null) checkCancelled.checkCancelled();
                    MultibandBlender.LevelSize size =
                        MultibandBlender.levelSize(canvasWidth, canvasHeight, level);
                    int width = size.width;
                    int height = size.height;
                    Mat color = Mat.zeros(height, width, CvType.CV_32FC3);
                    Mat weight = Mat.zeros(height, width, CvType.CV_32FC1);
                    accumulateLevel(
                        frames, color, weight, width, height,
                        0, height, 1 << level, gains, seams, pivotRatio,
                        longitudeSpanDegrees, centerLongitudeDegrees,
                        latitudeSpanDegrees, centerLatitudeDegrees, scratch
                    );
                    coarse.add(new MultibandBlender.PyramidLevel(color, weight));
                    progressDone++;
                    if (onBandComplete != null) onBandComplete.onProgress(progressDone, progressTotal);
                }

                // -- Coarse reconstruction ------------------------------------
                // Levels L down to 2 rebuild whole-canvas; they are small, and
                // the bands they expand into are a fraction of the canvas.
                acc = MultibandBlender.normalizeColor(
                    coarse.get(levelCount - 2).color,
                    coarse.get(levelCount - 2).weight
                );
                for (int level = levelCount - 2; level >= 2; level--) {
                    if (checkCancelled != null) checkCancelled.checkCancelled();
                    // The accumulator from the previous pass (or the
                    // initialisation above) is always set here; the compiler
                    // cannot see that through the loop, so the rebuild and the
                    // release work from a non-null local.
                    Mat current = acc;
                    if (current == null) {
                        throw new IllegalStateException("coarse accumulator missing at level " + level);
                    }
                    MultibandBlender.LevelSize size =
                        MultibandBlender.levelSize(canvasWidth, canvasHeight, level);
                    MultibandBlender.LevelSize coarseSize =
                        MultibandBlender.levelSize(canvasWidth, canvasHeight, level + 1);
                    Mat next = MultibandBlender.reconstructBand(
                        coarse.get(level - 1).color,
                        coarse.get(level - 1).weight,
                        current,
                        coarse.get(level).color,
                        coarse.get(level).weight,
                        size.width,
                        size.height,
                        coarseSize.width,
                        coarseSize.height,
                        0,
                        size.height
                    );
                    current.release();
                    acc = next;
                    // The level this iteration just folded in (level 3 or up) is
                    // spent; level 2 is still needed by the level-1 bands, and
                    // level 1 by the canvas.
                    coarse.get(level).release();
                }
                // acc is now level 2's reconstruction; coarse[1] holds level
                // 2's colour sum and coarse[0] level 1's sums. A canvas that
                // split into only two levels has no level 2, and acc already is
                // level 1's reconstruction.

                // -- Level-1 reconstruction, banded --------------------------
                MultibandBlender.LevelSize levelOneSize =
                    MultibandBlender.levelSize(canvasWidth, canvasHeight, 1);
                int levelOneWidth = levelOneSize.width;
                int levelOneHeight = levelOneSize.height;
                if (levelCount >= 3) {
                    MultibandBlender.LevelSize levelTwoSize =
                        MultibandBlender.levelSize(canvasWidth, canvasHeight, 2);
                    int levelTwoWidth = levelTwoSize.width;
                    int levelTwoHeight = levelTwoSize.height;
                    levelOneAcc = Mat.zeros(levelOneHeight, levelOneWidth, CvType.CV_32FC3);
                    for (int band = 0; band < levelOneBandCount; band++) {
                        if (checkCancelled != null) checkCancelled.checkCancelled();
                        int bandTop = band * BAND_HEIGHT;
                        int bandHeight = Math.min(BAND_HEIGHT, levelOneHeight - bandTop);
                        Mat colorBand = coarse.get(0).color.submat(
                            new Rect(0, bandTop, levelOneWidth, bandHeight)
                        );
                        Mat weightBand = coarse.get(0).weight.submat(
                            new Rect(0, bandTop, levelOneWidth, bandHeight)
                        );
                        Mat target = levelOneAcc.submat(
                            new Rect(0, bandTop, levelOneWidth, bandHeight)
                        );
                        Mat reconstructed = MultibandBlender.reconstructBand(
                            colorBand,
                            weightBand,
                            acc,
                            coarse.get(1).color,
                            coarse.get(1).weight,
                            levelOneWidth,
                            levelOneHeight,
                            levelTwoWidth,
                            levelTwoHeight,
                            bandTop,
                            bandHeight
                        );
                        try {
                            reconstructed.copyTo(target);
                        } finally {
                            reconstructed.release();
                            colorBand.release();
                            weightBand.release();
                            target.release();
                        }
                        progressDone++;
                        if (onBandComplete != null) onBandComplete.onProgress(progressDone, progressTotal);
                    }
                    // The level-2 content has done its job; level 1's weight sum
                    // is still needed by the level-0 bands below.
                    acc.release();
                    coarse.get(1).release();
                } else {
                    // Two levels only: acc already is level 1's reconstruction.
                    levelOneAcc = acc;
                }

                // -- Level-0 reconstruction, banded, into the canvas ----------
                // Exactly one of the branches above built the level-one
                // accumulator; from here it is owned by the level-0 bands and
                // released once they are done.
                Mat levelOne = levelOneAcc;
                if (levelOne == null) {
                    throw new IllegalStateException("level-one accumulator was not built");
                }
                for (int band = 0; band < levelZeroBandCount; band++) {
                    if (checkCancelled != null) checkCancelled.checkCancelled();
                    int bandTop = band * BAND_HEIGHT;
                    int bandHeight = Math.min(BAND_HEIGHT, canvasHeight - bandTop);
                    Mat color = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC3);
                    Mat weight = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC1);
                    try {
                        accumulateLevel(
                            frames, color, weight, canvasWidth, canvasHeight,
                            bandTop, bandHeight, 1, gains, seams, pivotRatio,
                            longitudeSpanDegrees, centerLongitudeDegrees,
                            latitudeSpanDegrees, centerLatitudeDegrees, scratch
                        );
                        coveredPixels += Core.countNonZero(weight);
                        Mat reconstructed = MultibandBlender.reconstructBand(
                            color,
                            weight,
                            levelOne,
                            coarse.get(0).color,
                            coarse.get(0).weight,
                            canvasWidth,
                            canvasHeight,
                            levelOneWidth,
                            levelOneHeight,
                            bandTop,
                            bandHeight
                        );
                        Mat region = canvas.submat(new Rect(0, bandTop, canvasWidth, bandHeight));
                        try {
                            reconstructed.convertTo(region, CvType.CV_8UC3);
                        } finally {
                            region.release();
                        }
                        reconstructed.release();
                    } finally {
                        color.release();
                        weight.release();
                    }
                    progressDone++;
                    if (onBandComplete != null) onBandComplete.onProgress(progressDone, progressTotal);
                }
                levelOne.release();
                coarse.get(0).color.release();
                coarse.get(0).weight.release();
            }
        } catch (Throwable e) {
            // The happy path releases the pyramid progressively as it rebuilds
            // it; on a failure it has released only the levels it got past, so
            // release whatever is still held. Mat.release is idempotent, and
            // `levelOneAcc` may alias `acc` on a two-level canvas, so
            // releasing any of these twice is safe.
            for (int i = 0; i < coarse.size(); i++) coarse.get(i).release();
            if (acc != null) acc.release();
            if (levelOneAcc != null) levelOneAcc.release();
            canvas.release();
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            if (e instanceof Error) throw (Error) e;
            throw new RuntimeException(e);
        }

        long total = (long) canvasWidth * canvasHeight;
        return new Rendered(canvas, (float) ((double) coveredPixels / total));
    }

    /**
     * Accumulates every frame that reaches one band of one pyramid level into
     * {@code colorSum} and {@code weightSum}.
     *
     * {@code canvasWidth}/{@code canvasHeight} are the *level's* canvas size and
     * {@code bandTop}/{@code bandHeight} the band within it (the whole canvas for levels
     * above the finest). {@code sourceScale} is {@code 2^level}: at level 0 it is 1 and
     * the frames are sampled whole; above that each frame is downsampled to a
     * quarter the area first, so the warp lands on the level's coarse Gaussian
     * rather than a decimated copy of the full-res one.
     */
    private static void accumulateLevel(
        List<PreparedFrame> frames,
        Mat colorSum,
        Mat weightSum,
        int canvasWidth,
        int canvasHeight,
        int bandTop,
        int bandHeight,
        int sourceScale,
        float[] gains,
        SeamWeights seams,
        double pivotRatio,
        float longitudeSpanDegrees,
        float centerLongitudeDegrees,
        float latitudeSpanDegrees,
        float centerLatitudeDegrees,
        SegmentScratch scratch
    ) {
        double divisor = (double) sourceScale;
        int interpolation = sourceScale == 1 ? Imgproc.INTER_LANCZOS4 : Imgproc.INTER_LINEAR;
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            PreparedFrame frame = frames.get(frameIndex);
            CanvasFootprint footprint = scaleFootprint(frame.footprint, sourceScale);
            if (footprint.isEmpty()) continue;

            int rowStart = Math.max(bandTop, footprint.startRow);
            int rowEnd = Math.min(bandTop + bandHeight, footprint.startRow + footprint.rowSpan);
            if (rowEnd <= rowStart) continue;

            boolean ownsSource = sourceScale > 1;
            Mat source;
            if (ownsSource) {
                Mat downsampled = new Mat();
                Imgproc.resize(
                    frame.image, downsampled,
                    new Size(
                        (double) Math.max(frame.intrinsics.widthPx / sourceScale, 1),
                        (double) Math.max(frame.intrinsics.heightPx / sourceScale, 1)
                    ),
                    0.0, 0.0, Imgproc.INTER_AREA
                );
                source = downsampled;
            } else {
                source = frame.image;
            }
            try {
                final PreparedFrame frameRef = frame;
                final int frameIndexRef = frameIndex;
                final Mat sourceRef = source;
                final float gain = (gains != null && frameIndex >= 0 && frameIndex < gains.length)
                    ? gains[frameIndex] : 1f;
                forEachColumnRun(
                    footprint,
                    canvasWidth,
                    longitudeSpanDegrees,
                    new ColumnRun() {
                        public void accept(int canvasColumn, int columnSpan) {
                            accumulateSegment(
                                frameRef, frameIndexRef, sourceRef, divisor, interpolation,
                                colorSum, weightSum, canvasWidth, canvasHeight, bandTop,
                                rowStart, rowEnd - rowStart, canvasColumn, columnSpan,
                                gain, seams, pivotRatio,
                                longitudeSpanDegrees, centerLongitudeDegrees,
                                latitudeSpanDegrees, centerLatitudeDegrees, scratch
                            );
                        }
                    }
                );
            } finally {
                if (ownsSource) source.release();
            }
        }
    }

    /**
     * Projects one rectangle of canvas back through a frame and adds it in.
     *
     * The two trigonometric factors of a direction separate — longitude varies
     * only across columns and latitude only down rows — so the per-column parts
     * are computed once for the whole rectangle and the inner loop is left with
     * a couple of multiplies per pixel.
     *
     * {@code source} is the frame's image, either whole ({@code sourceDivisor} 1) or
     * downsampled by {@code sourceDivisor} — the map coordinates are divided by it so
     * they land in the right pixels either way. The feather weight is measured
     * against the *ideal* (pinhole) pixel at full resolution, which is
     * scale-invariant.
     */
    private static void accumulateSegment(
        PreparedFrame frame,
        int frameIndex,
        Mat source,
        double sourceDivisor,
        int interpolation,
        Mat colorSum,
        Mat weightSum,
        int canvasWidth,
        int canvasHeight,
        int bandTop,
        int rowStart,
        int rowCount,
        int columnStart,
        int columnCount,
        float gain,
        SeamWeights seams,
        double pivotRatio,
        float longitudeSpanDegrees,
        float centerLongitudeDegrees,
        float latitudeSpanDegrees,
        float centerLatitudeDegrees,
        SegmentScratch scratch
    ) {
        if (rowCount <= 0 || columnCount <= 0) return;

        CameraBasis basis = frame.basis;
        FrameIntrinsics intrinsics = frame.intrinsics;
        double centreX = intrinsics.getCenterXPx();
        double centreY = intrinsics.getCenterYPx();
        double maxColumn = intrinsics.widthPx - 1.0;
        double maxRow = intrinsics.heightPx - 1.0;
        double[] radial = intrinsics.radial;

        scratch.prepare(rowCount, columnCount);

        // Longitude is periodic, so a column that was unwrapped past the seam
        // gives the same direction as the column it wraps onto — the canvas
        // column can be used directly here. On a region canvas this holds too:
        // only [forEachColumnRun] decides whether the seam wraps or clips.
        double[] forwardHorizontal = scratch.forwardHorizontal;
        double[] rightHorizontal = scratch.rightHorizontal;
        double[] upHorizontal = scratch.upHorizontal;
        for (int offset = 0; offset < columnCount; offset++) {
            double longitude = Math.toRadians(
                Equirectangular.longitudeDegrees(
                    columnStart + offset,
                    canvasWidth,
                    longitudeSpanDegrees,
                    centerLongitudeDegrees
                )
            );
            double sinLongitude = Math.sin(longitude);
            double cosLongitude = Math.cos(longitude);
            forwardHorizontal[offset] = sinLongitude * basis.forwardX + cosLongitude * basis.forwardY;
            rightHorizontal[offset] = sinLongitude * basis.rightX + cosLongitude * basis.rightY;
            upHorizontal[offset] = sinLongitude * basis.upX + cosLongitude * basis.upY;
        }

        float[] mapX = scratch.mapX;
        float[] mapY = scratch.mapY;
        float[] weights = scratch.weights;
        boolean anyCovered = false;

        for (int rowOffset = 0; rowOffset < rowCount; rowOffset++) {
            double latitude = Math.toRadians(
                Equirectangular.latitudeDegrees(
                    rowStart + rowOffset,
                    canvasHeight,
                    latitudeSpanDegrees,
                    centerLatitudeDegrees
                )
            );
            double cosLatitude = Math.cos(latitude);
            double sinLatitude = Math.sin(latitude);
            int rowBase = rowOffset * columnCount;

            for (int columnOffset = 0; columnOffset < columnCount; columnOffset++) {
                // The canvas direction is a unit vector from the pivot, and the
                // lens sits `pivotRatio` of the way out along its own optical
                // axis (see PivotModel). The lever arm is parallel to forward, so
                // it drops out of the two lateral components and shortens the
                // depth alone — the whole parallax correction is this subtraction.
                double depth = cosLatitude * forwardHorizontal[columnOffset]
                    + sinLatitude * basis.forwardZ - pivotRatio;
                int index = rowBase + columnOffset;
                if (depth <= SphericalGeometry.MIN_DEPTH) {
                    // Behind the camera. A map coordinate outside the source
                    // makes remap emit the border colour, and the zero weight
                    // keeps it out of the blend regardless.
                    mapX[index] = -1f;
                    mapY[index] = -1f;
                    weights[index] = 0f;
                    continue;
                }

                double lateral = cosLatitude * rightHorizontal[columnOffset]
                    + sinLatitude * basis.rightZ;
                double vertical = cosLatitude * upHorizontal[columnOffset] + sinLatitude * basis.upZ;

                // The lens model in two steps: the ideal pinhole pixel, then the
                // radial push to where the lens actually recorded it. The ideal
                // pixel is what the feather measures against — it is proportional
                // to the angle off the optical axis — while the distorted pixel
                // is what `remap` samples.
                double idealColumn = centreX + intrinsics.focalXPx * lateral / depth;
                double idealRow = centreY - intrinsics.focalYPx * vertical / depth;
                double sourceColumn = idealColumn;
                double sourceRow = idealRow;
                if (radial != null) {
                    double x = idealColumn - centreX;
                    double y = idealRow - centreY;
                    double factor = LensModel.radialFactor(radial, x * x + y * y);
                    sourceColumn = centreX + x * factor;
                    sourceRow = centreY + y * factor;
                }
                if (sourceColumn < 0.0 || sourceColumn > maxColumn
                    || sourceRow < 0.0 || sourceRow > maxRow) {
                    mapX[index] = -1f;
                    mapY[index] = -1f;
                    weights[index] = 0f;
                    continue;
                }

                mapX[index] = (float) (sourceColumn / sourceDivisor);
                mapY[index] = (float) (sourceRow / sourceDivisor);
                float weight;
                if (seams != null) {
                    // Seam carving: the winner paints at full strength, the
                    // loser only within a few pixels of the cut. The canvas
                    // row/column are the level's own coordinates, which the
                    // seam grid maps back from.
                    weight = seams.weightFor(
                        frameIndex,
                        rowStart + rowOffset,
                        columnStart + columnOffset,
                        (int) sourceDivisor
                    );
                } else {
                    weight = (float) SphericalGeometry.featherWeight(
                        idealColumn, idealRow, centreX, centreY
                    );
                }
                weights[index] = weight;
                if (weight > 0f) anyCovered = true;
            }
        }

        if (!anyCovered) return;

        Mat mapXMat = new Mat(rowCount, columnCount, CvType.CV_32FC1);
        Mat mapYMat = new Mat(rowCount, columnCount, CvType.CV_32FC1);
        Mat weightMat = new Mat(rowCount, columnCount, CvType.CV_32FC1);
        Mat warped = new Mat();
        Mat warpedFloat = new Mat();
        Mat weight3 = new Mat();
        try {
            // The scratch arrays are sized to the largest segment seen so far
            // and so are usually longer than this one. `Mat.put` clamps the copy
            // to the matrix's own element count, which is exactly the leading
            // `rowCount * columnCount` entries these loops just filled.
            mapXMat.put(0, 0, mapX);
            mapYMat.put(0, 0, mapY);
            weightMat.put(0, 0, weights);

            // Lanczos on the finest level, linear above it: the canvas is now
            // often rendered close to the frames' own resolution, and the
            // sharper kernel keeps the edge detail that a linear filter would
            // soften. Coarse levels sample an already-downsampled source, where
            // linear is all there is to keep.
            Imgproc.remap(
                source,
                warped,
                mapXMat,
                mapYMat,
                interpolation,
                Core.BORDER_CONSTANT,
                new Scalar(0.0, 0.0, 0.0)
            );
            warped.convertTo(warpedFloat, CvType.CV_32FC3);
            List<Mat> weightChannels = new ArrayList<Mat>(3);
            weightChannels.add(weightMat);
            weightChannels.add(weightMat);
            weightChannels.add(weightMat);
            Core.merge(weightChannels, weight3);
            Core.multiply(warpedFloat, weight3, warpedFloat);
            if (gain != 1f) {
                Core.multiply(
                    warpedFloat,
                    new Scalar((double) gain, (double) gain, (double) gain),
                    warpedFloat
                );
            }

            Rect target = new Rect(columnStart, rowStart - bandTop, columnCount, rowCount);
            Mat colorRegion = colorSum.submat(target);
            Mat weightRegion = weightSum.submat(target);
            try {
                Core.add(colorRegion, warpedFloat, colorRegion);
                Core.add(weightRegion, weightMat, weightRegion);
            } finally {
                colorRegion.release();
                weightRegion.release();
            }
        } finally {
            mapXMat.release();
            mapYMat.release();
            weightMat.release();
            warped.release();
            warpedFloat.release();
            weight3.release();
        }
    }

    /**
     * A footprint in level-{@code scale} (power-of-two) canvas coordinates.
     *
     * The level-0 footprint is divided by {@code scale} with the bounds pushed out a
     * row and a column, so the coarse warp never misses a border the sampled
     * footprint was one pixel short of — the projection's own bounds check
     * trims any overreach.
     */
    public static CanvasFootprint scaleFootprint(CanvasFootprint footprint, int scale) {
        if (scale <= 1) return footprint;
        int startColumn = Math.floorDiv(footprint.startColumn, scale) - 1;
        int endColumn = Math.floorDiv(footprint.startColumn + footprint.columnSpan - 1, scale) + 2;
        int startRow = Math.floorDiv(footprint.startRow, scale) - 1;
        int endRow = Math.floorDiv(footprint.startRow + footprint.rowSpan - 1, scale) + 2;
        return new CanvasFootprint(
            startColumn,
            endColumn - startColumn,
            startRow,
            endRow - startRow
        );
    }

    /**
     * Splits a footprint's column range into runs of contiguous canvas columns.
     *
     * A footprint that crosses the ±180° seam is one unbroken band of longitude
     * but two blocks of canvas, so {@code block} is called twice for it and once for
     * everything else. That seam is only periodic when the canvas holds a full
     * turn of longitude: a region canvas (fewer than 360° of it) has an edge
     * that is a real cut, so a footprint running past either edge is *clipped*
     * there instead — the far side is outside the captured region and stays
     * black. The span never exceeds the canvas width, so a full-turn canvas
     * never produces more than two runs.
     */
    private static void forEachColumnRun(
        CanvasFootprint footprint,
        int canvasWidth,
        float longitudeSpanDegrees,
        ColumnRun block
    ) {
        int span = Math.min(footprint.columnSpan, canvasWidth);
        if (span <= 0) return;
        if (longitudeSpanDegrees >= 360f) {
            int first = SphericalGeometry.wrapColumn(footprint.startColumn, canvasWidth);
            int firstSpan = Math.min(span, canvasWidth - first);
            block.accept(first, firstSpan);
            int remainder = span - firstSpan;
            if (remainder > 0) block.accept(0, remainder);
        } else {
            int first = Math.max(footprint.startColumn, 0);
            int last = Math.min(footprint.startColumn + span, canvasWidth);
            if (last > first) block.accept(first, last - first);
        }
    }

    /**
     * Grow-only working buffers for one segment's projection.
     *
     * A render walks every frame across every band and level, and each visit
     * needs three float arrays the size of the rectangle it is painting plus
     * three the width of it. Allocating those per visit is tens of megabytes of
     * short-lived array per band on a wide canvas — enough garbage to stall a
     * stitch on a mid-range phone. The buffers are only ever grown, so after
     * the first few segments the render allocates nothing at all.
     *
     * Confined to the rendering thread; a render is single-threaded by
     * construction, and one scratch is created per {@link #render} call.
     */
    private static final class SegmentScratch {
        float[] mapX = new float[0];
        float[] mapY = new float[0];
        float[] weights = new float[0];
        double[] forwardHorizontal = new double[0];
        double[] rightHorizontal = new double[0];
        double[] upHorizontal = new double[0];

        void prepare(int rowCount, int columnCount) {
            int pixels = rowCount * columnCount;
            if (mapX.length < pixels) {
                mapX = new float[pixels];
                mapY = new float[pixels];
                weights = new float[pixels];
            }
            if (forwardHorizontal.length < columnCount) {
                forwardHorizontal = new double[columnCount];
                rightHorizontal = new double[columnCount];
                upHorizontal = new double[columnCount];
            }
        }
    }
}
