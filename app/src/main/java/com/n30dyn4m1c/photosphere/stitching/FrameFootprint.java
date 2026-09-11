package com.n30dyn4m1c.photosphere.stitching;

/**
 * Works out which part of the canvas a frame can paint.
 *
 * A frame covers a curved quadrilateral on the sphere, so the bounds are found
 * by walking its border rather than by transforming its four corners: at wide
 * fields of view the mid-edge of a frame reaches a higher latitude than either
 * corner does, and a corners-only box would clip the top of every frame.
 *
 * Longitude is unwrapped relative to the frame's own centre, which is what lets
 * a frame spanning the ±180° seam come back as one contiguous range.
 *
 * A frame that contains a pole is the special case: every longitude is inside
 * it, so no bounding range in longitude is meaningful and the footprint opens
 * out to the full width of the canvas.
 */
public final class FrameFootprint {

    /** Border samples per edge. Enough to catch the bulge at a 70° field of view. */
    private static final int SAMPLES_PER_EDGE = 24;

    private FrameFootprint() {}

    /**
     * Bounds of what {@code basis}/{@code intrinsics} can paint on a {@code canvasWidth} canvas.
     *
     * {@code marginPx} pads the result, covering the difference between the sampled
     * border and the true one between samples. The four span/centre numbers
     * describe the region of the sphere the canvas holds (see
     * {@link Equirectangular}); they default to the full sphere.
     */
    public static CanvasFootprint compute(
        CameraBasis basis,
        FrameIntrinsics intrinsics,
        int canvasWidth,
        int canvasHeight
    ) {
        return compute(basis, intrinsics, canvasWidth, canvasHeight, 2, 0.0, 360f, 0f, 180f, 0f);
    }

    public static CanvasFootprint compute(
        CameraBasis basis,
        FrameIntrinsics intrinsics,
        int canvasWidth,
        int canvasHeight,
        int marginPx
    ) {
        return compute(basis, intrinsics, canvasWidth, canvasHeight, marginPx, 0.0, 360f, 0f, 180f, 0f);
    }

    public static CanvasFootprint compute(
        CameraBasis basis,
        FrameIntrinsics intrinsics,
        int canvasWidth,
        int canvasHeight,
        int marginPx,
        double pivotRatio
    ) {
        return compute(
            basis, intrinsics, canvasWidth, canvasHeight, marginPx, pivotRatio, 360f, 0f, 180f, 0f
        );
    }

    public static CanvasFootprint compute(
        CameraBasis basis,
        FrameIntrinsics intrinsics,
        int canvasWidth,
        int canvasHeight,
        int marginPx,
        double pivotRatio,
        float longitudeSpanDegrees,
        float centerLongitudeDegrees,
        float latitudeSpanDegrees,
        float centerLatitudeDegrees
    ) {
        double centreLongitude = Equirectangular.longitudeOf(basis.forwardX, basis.forwardY);

        double minLongitude = Double.MAX_VALUE;
        double maxLongitude = -Double.MAX_VALUE;
        double minLatitude = Double.MAX_VALUE;
        double maxLatitude = -Double.MAX_VALUE;

        double lastColumn = intrinsics.widthPx - 1.0;
        double lastRow = intrinsics.heightPx - 1.0;
        double[] radial = intrinsics.radial;
        for (int step = 0; step <= SAMPLES_PER_EDGE; step++) {
            double fraction = (double) step / SAMPLES_PER_EDGE;
            double alongWidth = fraction * lastColumn;
            double alongHeight = fraction * lastRow;
            double[] sample0 = sampleBorder(alongWidth, 0.0, radial, intrinsics);
            double[] sample1 = sampleBorder(alongWidth, lastRow, radial, intrinsics);
            double[] sample2 = sampleBorder(0.0, alongHeight, radial, intrinsics);
            double[] sample3 = sampleBorder(lastColumn, alongHeight, radial, intrinsics);
            double[][] samples = new double[][] {sample0, sample1, sample2, sample3};
            for (int s = 0; s < samples.length; s++) {
                double[] ray = basis.toWorld(samples[s][0], samples[s][1], 1.0);
                double length = length(ray);
                // The canvas is indexed by directions from the *pivot*, so a border
                // ray has to be followed out to the scene and looked back along from
                // there. With no lever arm the two are the same direction.
                double[] world = SphericalGeometry.pivotDirection(
                    basis,
                    pivotRatio,
                    ray[0] / length,
                    ray[1] / length,
                    ray[2] / length
                );
                double latitude = Equirectangular.latitudeOf(world[2]);
                // Unwrap onto the branch nearest the frame centre, so a border point
                // just past +180° reads as slightly more than the centre rather than
                // as nearly a full turn less.
                double longitude = SphericalGeometry.unwrapNear(
                    Equirectangular.longitudeOf(world[0], world[1]),
                    centreLongitude
                );

                minLongitude = Math.min(minLongitude, longitude);
                maxLongitude = Math.max(maxLongitude, longitude);
                minLatitude = Math.min(minLatitude, latitude);
                maxLatitude = Math.max(maxLatitude, latitude);
            }
        }

        // A frame looking over a pole sees every longitude; the border walk finds
        // only the longitudes its edges happen to cross, which would cut the
        // frame in half. Test the poles directly instead.
        boolean coversNorthPole = containsDirection(basis, intrinsics, 0.0, 0.0, 1.0, pivotRatio);
        boolean coversSouthPole = containsDirection(basis, intrinsics, 0.0, 0.0, -1.0, pivotRatio);
        if (coversNorthPole) maxLatitude = 90.0;
        if (coversSouthPole) minLatitude = -90.0;

        int startRow = floorToInt(
            Equirectangular.rowFor(maxLatitude, canvasHeight, latitudeSpanDegrees, centerLatitudeDegrees)
        ) - marginPx;
        int endRow = ceilToInt(
            Equirectangular.rowFor(minLatitude, canvasHeight, latitudeSpanDegrees, centerLatitudeDegrees)
        ) + marginPx;
        int clampedStartRow = clamp(startRow, 0, canvasHeight);
        int clampedEndRow = clamp(endRow, 0, canvasHeight);

        if (coversNorthPole || coversSouthPole) {
            return new CanvasFootprint(
                0,
                canvasWidth,
                clampedStartRow,
                clampedEndRow - clampedStartRow
            );
        }

        int startColumn = floorToInt(
            Equirectangular.columnFor(
                minLongitude, canvasWidth, longitudeSpanDegrees, centerLongitudeDegrees
            )
        ) - marginPx;
        int endColumn = ceilToInt(
            Equirectangular.columnFor(
                maxLongitude, canvasWidth, longitudeSpanDegrees, centerLongitudeDegrees
            )
        ) + marginPx;
        int columnSpan = Math.min(endColumn - startColumn, canvasWidth);

        return new CanvasFootprint(
            startColumn,
            columnSpan,
            clampedStartRow,
            clampedEndRow - clampedStartRow
        );
    }

    /** True when a world direction projects inside the frame. */
    private static boolean containsDirection(
        CameraBasis basis,
        FrameIntrinsics intrinsics,
        double x,
        double y,
        double z,
        double pivotRatio
    ) {
        return SphericalGeometry.projectDirection(basis, intrinsics, x, y, z, pivotRatio) != null;
    }

    /**
     * The captured image's border pixels have passed through the lens. Before a
     * pixel can be turned into a ray it has to be un-distorted back to where the
     * pinhole model put it, or a barrel-distorted frame would report a footprint
     * narrower than the true extent it reaches — exactly the kind of cropped
     * edge that becomes a seam gap.
     */
    private static double[] sampleBorder(
        double column,
        double row,
        double[] radial,
        FrameIntrinsics intrinsics
    ) {
        double[] ideal;
        if (radial != null) {
            ideal = LensModel.undistortPixel(
                column, row, intrinsics.getCenterXPx(), intrinsics.getCenterYPx(), radial
            );
        } else {
            ideal = new double[] {column, row};
        }
        return new double[] {cameraXOf(ideal[0], intrinsics), cameraYOf(ideal[1], intrinsics)};
    }

    private static double cameraXOf(double column, FrameIntrinsics intrinsics) {
        return (column - intrinsics.getCenterXPx()) / intrinsics.focalXPx;
    }

    private static double cameraYOf(double row, FrameIntrinsics intrinsics) {
        return -(row - intrinsics.getCenterYPx()) / intrinsics.focalYPx;
    }

    private static double length(double[] vector) {
        return Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
    }

    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    private static int ceilToInt(double value) {
        return (int) Math.ceil(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
