package com.n30dyn4m1c.photosphere.stitching;

import java.util.List;

/**
 * What feature-based pose refinement ended with.
 *
 * {@link #bases} holds one refined camera basis per input frame — the sensor pose for
 * any frame the content did not support — and {@link #gains} the per-frame brightness
 * gains that equalise the overlaps. {@link #matchedEdges} is how many pose-graph edges
 * were actually measured against image content; zero means the sensor did all
 * the work and the output is a plain orientation-driven stitch.
 *
 * {@link #focalScales} holds one focal-length multiplier per frame, aligned by position
 * with {@link #bases}: 1.0 when the measured field of view needs no correction, or
 * when nothing matched and there was no content to measure against. The
 * stitcher applies it to each frame's intrinsics before rendering.
 */
public final class RefinementResult {
    public final List<CameraBasis> bases;
    public final float[] gains;
    public final int matchedEdges;
    public final double[] focalScales;

    public RefinementResult(
        List<CameraBasis> bases,
        float[] gains,
        int matchedEdges,
        double[] focalScales
    ) {
        this.bases = bases;
        this.gains = gains;
        this.matchedEdges = matchedEdges;
        this.focalScales = focalScales;
    }
}
