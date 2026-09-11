package com.n30dyn4m1c.photosphere.stitching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * The seam-carving front end: decides which frame paints each output pixel.
 *
 * The renderer's cross-fade lets every overlapping frame contribute to a pixel,
 * which is what softens detail along the seams. Seam carving instead asks the
 * graph-cut in {@link SeamSolver} to assign each pixel to the single frame that
 * agrees best with its neighbours, then paints with a near-hard selection that
 * only cross-fades a few pixels across each cut — see {@link SeamWeights} and
 * {@link SeamFeather}.
 *
 * The assignment is decided at a reduced resolution ({@link #LABEL_GRID_WIDTH} across
 * the canvas), for three reasons. A seam only needs to be located to within a
 * few output pixels, so the solve can run on a grid a fraction of the size; the
 * graph-cut's cost scales with that grid rather than with the full canvas; and
 * the feather is then computed where it is wide enough to matter. Each grid
 * pixel's direction is projected back into every frame through the same lens
 * model the renderer samples through, the sample's colour is read from the
 * frame's pixels, and the {@link PackedGrid} the solver needs is built directly.
 *
 * This file is the only OpenCV-dependent part of seam carving; {@link SeamSolver},
 * {@link SeamWeights} and {@link SeamFeather} are pure Java and JVM-tested.
 */
public final class SeamFinder {

    /** Label-grid width for a full canvas; seams are decided at ~this resolution. */
    private static final int LABEL_GRID_WIDTH = 512;

    /**
     * Long edge frames are downsampled to before their pixels are read for the
     * seam costs.
     *
     * The seam data only needs the *colour* each frame recorded at a pixel, and
     * the grid decides those pixels at ~{@link #LABEL_GRID_WIDTH} across the canvas —
     * there is no detail worth carrying at the frames' full resolution. Working
     * from a ~512-long-edge copy keeps the sampled colours identical to within
     * an average while cutting the memory of holding every frame's pixels to a
     * few megabytes; the full-res copies would otherwise roughly double the
     * peak of an already memory-tight stitch.
     */
    private static final int WORK_LONG_EDGE = 512;

    /**
     * λ scaling the pairwise seam cost against the per-pixel data costs.
     *
     * The seam cost is the sum of the two colour differences across a cut edge,
     * so with colours in 0..255 an edge that cuts through genuine agreement
     * costs a few tens and one that cuts through a ghost costs hundreds. λ
     * around ten makes the cut follow agreement while still letting the data
     * term break ties in the interiors of overlaps.
     */
    public static final double SMOOTH_LAMBDA = 10.0;

    /**
     * How much a sample sitting on its frame's border raises that frame's cost.
     *
     * A frame is least trustworthy at its own edge — radial distortion and
     * vignetting live there — so the data term nudges pixels away from
     * assigning a frame the very edge of its own coverage. The value is large
     * enough to matter against a consensus difference but far smaller than a
     * genuine colour disagreement, so it only moves seams that had nowhere
     * better to go.
     */
    public static final double BORDER_PENALTY = 1000.0;

    /** Full-resolution half-width of the cross-fade across a seam, in pixels. */
    private static final int FEATHER_FULL_RES_PIXELS = 12;

    /** α-expansion passes over the labels; two almost always converges. */
    private static final int MAX_PASSES = 2;

    public interface ProgressListener {
        void onProgress(int completed, int total);
    }

    public interface CancelCheck {
        void checkCancelled();
    }

    private SeamFinder() {}

    /**
     * Computes the per-pixel frame assignment for one render.
     *
     * {@code frames} are the prepared frames with their corrected poses and lens
     * models. {@code gains} carries the per-frame exposure compensation so the
     * sampled colours already agree the way the renderer will blend them — the
     * seam is found in the world the renderer is about to paint. The result is
     * a {@link SeamWeights} at the reduced label resolution, for
     * {@link EquirectangularRenderer} to look up while it accumulates.
     */
    public static SeamWeights computeSeams(
        List<PreparedFrame> frames,
        int canvasWidth,
        int canvasHeight,
        float[] gains,
        double pivotRatio,
        float longitudeSpanDegrees,
        float centerLongitudeDegrees,
        float latitudeSpanDegrees,
        float centerLatitudeDegrees,
        ProgressListener onProgress,
        CancelCheck checkCancelled
    ) {
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            throw new IllegalArgumentException("canvas must have extent");
        }
        int scale = Math.max(1, (canvasWidth + LABEL_GRID_WIDTH - 1) / LABEL_GRID_WIDTH);
        int gridWidth = (canvasWidth + scale - 1) / scale;
        int gridHeight = (canvasHeight + scale - 1) / scale;

        // Each frame's pixels are read through one flat copy rather than a
        // per-sample Mat.get: the projection back into the frame runs millions
        // of times and a byte buffer indexed by hand is far cheaper than a JNI
        // call per pixel. The copy is made from a small downsampled version so
        // holding every frame at once costs a few megabytes, not the hundreds a
        // full-resolution working set would.
        ArrayList<WorkingFrame> sampled = new ArrayList<WorkingFrame>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            sampled.add(downsampleAndRead(frames.get(i).image));
        }

        int nodeCount = gridWidth * gridHeight;
        int[] nodeLabelCount = new int[nodeCount];
        float[] nodeMean = new float[nodeCount * 3];

        // Grow-only flat packing for the sparse labels; a long capture touches
        // each pixel two or three times, so the arrays start sized for that and
        // only grow if an overlap is denser than expected.
        int capacity = Math.max(nodeCount * 4, 16);
        int[] packedLabels = new int[capacity];
        float[] packedData = new float[capacity];
        float[] packedColors = new float[capacity * 3];
        int packedSize = 0;

        int[] tempCover = new int[frames.size()];
        float[] tempColor = new float[frames.size() * 3];
        double[] tempCol = new double[frames.size()];
        double[] tempRow = new double[frames.size()];
        double[] scratch = new double[5];

        int totalProgress = gridHeight + frames.size() * MAX_PASSES;
        int progressDone = 0;

        for (int gridRow = 0; gridRow < gridHeight; gridRow++) {
            if (checkCancelled != null) checkCancelled.checkCancelled();
            int centerRow = gridRow * scale + scale / 2;
            double latitude = Math.toRadians(
                Equirectangular.latitudeDegrees(
                    centerRow, canvasHeight, latitudeSpanDegrees, centerLatitudeDegrees
                )
            );
            double cosLatitude = Math.cos(latitude);
            double sinLatitude = Math.sin(latitude);
            for (int gridCol = 0; gridCol < gridWidth; gridCol++) {
                int centerCol = gridCol * scale + scale / 2;
                double longitude = Math.toRadians(
                    Equirectangular.longitudeDegrees(
                        centerCol, canvasWidth, longitudeSpanDegrees, centerLongitudeDegrees
                    )
                );
                double sinLongitude = Math.sin(longitude);
                double cosLongitude = Math.cos(longitude);
                double dirX = sinLongitude * cosLatitude;
                double dirY = cosLongitude * cosLatitude;
                double dirZ = sinLatitude;

                int count = 0;
                for (int fi = 0; fi < frames.size(); fi++) {
                    WorkingFrame working = sampled.get(fi);
                    if (!sampleInto(
                        frames.get(fi),
                        working.pixels,
                        working.width,
                        working.height,
                        working.scale,
                        dirX,
                        dirY,
                        dirZ,
                        pivotRatio,
                        scratch
                    )) {
                        continue;
                    }
                    float gain = (gains != null && fi >= 0 && fi < gains.length) ? gains[fi] : 1f;
                    tempCover[count] = fi;
                    tempColor[count * 3] = (float) (scratch[0] * gain);
                    tempColor[count * 3 + 1] = (float) (scratch[1] * gain);
                    tempColor[count * 3 + 2] = (float) (scratch[2] * gain);
                    tempCol[count] = scratch[3];
                    tempRow[count] = scratch[4];
                    count++;
                }

                int node = gridRow * gridWidth + gridCol;
                if (count == 0) continue;
                nodeLabelCount[node] = count;

                float meanR = 0f;
                float meanG = 0f;
                float meanB = 0f;
                for (int k = 0; k < count; k++) {
                    meanR += tempColor[k * 3];
                    meanG += tempColor[k * 3 + 1];
                    meanB += tempColor[k * 3 + 2];
                }
                meanR /= count;
                meanG /= count;
                meanB /= count;
                int meanBase = node * 3;
                nodeMean[meanBase] = meanR;
                nodeMean[meanBase + 1] = meanG;
                nodeMean[meanBase + 2] = meanB;

                for (int k = 0; k < count; k++) {
                    if (packedSize >= capacity) {
                        capacity *= 2;
                        packedLabels = Arrays.copyOf(packedLabels, capacity);
                        packedData = Arrays.copyOf(packedData, capacity);
                        packedColors = Arrays.copyOf(packedColors, capacity * 3);
                    }
                    float r = tempColor[k * 3];
                    float g = tempColor[k * 3 + 1];
                    float b = tempColor[k * 3 + 2];
                    float dr = r - meanR;
                    float dg = g - meanG;
                    float db = b - meanB;
                    double data = (double) (dr * dr + dg * dg + db * db);
                    data += borderPenalty(frames.get(tempCover[k]).intrinsics, tempCol[k], tempRow[k]);
                    packedLabels[packedSize] = tempCover[k];
                    packedData[packedSize] = (float) data;
                    int colorBase = packedSize * 3;
                    packedColors[colorBase] = r;
                    packedColors[colorBase + 1] = g;
                    packedColors[colorBase + 2] = b;
                    packedSize++;
                }
            }
            progressDone++;
            if (onProgress != null) onProgress.onProgress(progressDone, totalProgress);
        }

        if (packedSize == 0) {
            int[] emptyLabels = new int[nodeCount];
            int[] emptyLosers = new int[nodeCount];
            Arrays.fill(emptyLabels, -1);
            Arrays.fill(emptyLosers, -1);
            return new SeamWeights(
                gridWidth, gridHeight, scale,
                emptyLabels,
                emptyLosers,
                new float[nodeCount]
            );
        }

        PackedGrid grid = new PackedGrid(
            gridWidth,
            gridHeight,
            frames.size(),
            nodeLabelCount,
            packedLabels,
            packedData,
            packedColors,
            nodeMean
        );
        final int[] progressHolder = new int[] {progressDone};
        final ProgressListener listener = onProgress;
        final int total = totalProgress;
        int[] labels = SeamSolver.solve(
            grid,
            SMOOTH_LAMBDA,
            MAX_PASSES,
            new Runnable() {
                public void run() {
                    progressHolder[0]++;
                    if (listener != null) listener.onProgress(progressHolder[0], total);
                }
            }
        );
        int halfWidth = Math.max(1, FEATHER_FULL_RES_PIXELS / scale);
        SeamFeather.Result feather = SeamFeather.derive(labels, gridWidth, gridHeight, halfWidth);
        return new SeamWeights(gridWidth, gridHeight, scale, labels, feather.loserLabel, feather.loserWeight);
    }

    /**
     * A frame's pixels, downsampled to a small working size for the seam costs.
     *
     * {@link #scale} divides a full-resolution frame pixel to reach this copy, and
     * {@link #width}/{@link #height} are this copy's own extent — both kept so the sampling
     * loop can project through the full-resolution lens model and read through
     * the small buffer in one step.
     */
    private static final class WorkingFrame {
        final byte[] pixels;
        final int width;
        final int height;
        final double scale;

        WorkingFrame(byte[] pixels, int width, int height, double scale) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.scale = scale;
        }
    }

    /**
     * Downsamples {@code image} so its long edge is at most {@link #WORK_LONG_EDGE} and
     * copies the result out as one flat BGR byte array.
     *
     * {@code CV_8UC3} storage is BGR and the copy is the whole image, so sampling a
     * pixel later is one array read plus an index — the access pattern the
     * seam loop actually needs. The downsampled Mat is released once its bytes
     * are in hand.
     */
    private static WorkingFrame downsampleAndRead(Mat image) {
        int sourceWidth = image.cols();
        int sourceHeight = image.rows();
        int longest = Math.max(sourceWidth, sourceHeight);
        double scale = longest / (double) WORK_LONG_EDGE;
        int width;
        int height;
        byte[] bytes;
        if (scale <= 1.0) {
            width = sourceWidth;
            height = sourceHeight;
            bytes = new byte[sourceWidth * sourceHeight * image.channels()];
            image.get(0, 0, bytes);
        } else {
            width = Math.max(1, (int) Math.round(sourceWidth / scale));
            height = Math.max(1, (int) Math.round(sourceHeight / scale));
            Mat small = new Mat();
            try {
                Imgproc.resize(
                    image, small,
                    new Size((double) width, (double) height),
                    0.0, 0.0, Imgproc.INTER_AREA
                );
                bytes = new byte[width * height * small.channels()];
                small.get(0, 0, bytes);
            } finally {
                small.release();
            }
        }
        return new WorkingFrame(bytes, width, height, scale);
    }

    /**
     * Samples the pixel of {@code frame} that looks in direction ({@code x}, {@code y}, {@code z}).
     *
     * The direction is rotated into the frame's axes, divided through by depth
     * and pushed through the lens's radial distortion — the same projection
     * {@code projectDirection} performs, inlined here so the millions of calls
     * allocate nothing. The resulting full-resolution pixel is divided by
     * {@code scale} to land in the downsampled {@code bytes}. {@code out} carries back the RGB
     * sample in 0..2 and the full-resolution pixel position in 3..4 (for the
     * caller's border penalty, which is scale-invariant).
     */
    private static boolean sampleInto(
        PreparedFrame frame,
        byte[] bytes,
        int width,
        int height,
        double scale,
        double x,
        double y,
        double z,
        double pivotRatio,
        double[] out
    ) {
        CameraBasis basis = frame.basis;
        FrameIntrinsics intrinsics = frame.intrinsics;
        double depth = basis.depthOf(x, y, z) - pivotRatio;
        if (depth <= SphericalGeometry.MIN_DEPTH) return false;
        double centreX = intrinsics.getCenterXPx();
        double centreY = intrinsics.getCenterYPx();
        double idealColumn = centreX + intrinsics.focalXPx * basis.lateralOf(x, y, z) / depth;
        double idealRow = centreY - intrinsics.focalYPx * basis.verticalOf(x, y, z) / depth;
        double column = idealColumn;
        double row = idealRow;
        double[] radial = intrinsics.radial;
        if (radial != null) {
            double dx = idealColumn - centreX;
            double dy = idealRow - centreY;
            double factor = LensModel.radialFactor(radial, dx * dx + dy * dy);
            column = centreX + dx * factor;
            row = centreY + dy * factor;
        }
        int col = (int) Math.round(column / scale);
        int rowInt = (int) Math.round(row / scale);
        if (col < 0 || col >= width || rowInt < 0 || rowInt >= height) return false;

        int index = (rowInt * width + col) * 3;
        out[0] = (bytes[index + 2] & 0xff);
        out[1] = (bytes[index + 1] & 0xff);
        out[2] = (bytes[index] & 0xff);
        out[3] = column;
        out[4] = row;
        return true;
    }

    /**
     * How much a sample sitting at ({@code column}, {@code row}) on the frame's border
     * raises that frame's data cost, in 0..{@link #BORDER_PENALTY}.
     *
     * Measured against the frame's own optical centre the same way the render
     * feather is, so the penalty is 0 on the optical axis and 1 at the corners.
     */
    private static double borderPenalty(FrameIntrinsics intrinsics, double column, double row) {
        double across = 1.0 - Math.abs(column - intrinsics.getCenterXPx()) / intrinsics.getCenterXPx();
        double down = 1.0 - Math.abs(row - intrinsics.getCenterYPx()) / intrinsics.getCenterYPx();
        if (across <= 0.0 || down <= 0.0) return BORDER_PENALTY;
        return BORDER_PENALTY * (1.0 - across * down);
    }
}
