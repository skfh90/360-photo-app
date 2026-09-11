package com.n30dyn4m1c.photosphere.stitching;

import java.util.List;

/**
 * Per-frame brightness gains that make the overlap regions agree.
 *
 * AE lock makes every frame of a session share one exposure, which removes the
 * big brightness jumps. What is left is the directional lighting AE cannot fix:
 * a frame shot into the sun and the frame beside it recorded against it, or a
 * frame that caught a window when its neighbour caught a shaded wall. Those
 * differences show up as brightness seams inside the blends.
 *
 * The classic remedy (Brown &amp; Lowe's gain compensation) is a least-squares fit
 * of per-image gains against the pairwise differences in their overlapping
 * regions. The graph of which frames overlap comes from the same pose graph
 * pose refinement builds; the per-frame "brightness" used here is the mean
 * luminance over the whole frame, which tracks the overall level a frame was
 * recorded at without projecting pixels between frames.
 *
 * The equation, in log-gain space, is that along an edge (i, j) the gains must
 * equalise the means: {@code g_i·m_i ≈ g_j·m_j}, or {@code l_i − l_j = log m_j − log m_i}.
 * The connected graph fixes gains up to a global factor, which is removed by
 * normalising the geometric mean of the gains to 1 so the stitch neither
 * brightens nor darkens the whole sphere.
 */
public final class ExposureCompensation {

    private static final int ITERATIONS = 12;

    /**
     * How far a single frame's gain may stray from neutral.
     *
     * The whole-frame mean is a coarse stand-in for the brightness of the
     * *shared* region, so a frame that happens to differ from its neighbour for
     * an honest reason — half of it is sky — pulls its own gain in a direction
     * the overlap never asked for. A long chain of those compounds. Clamping
     * keeps the compensation doing what it is for (shaving a seam) and stops it
     * doing what it is not (blowing out a frame).
     *
     * Set as a safety rail rather than as a policy: two-thirds of a stop is
     * more correction than an AE-locked session of one scene has any business
     * needing, so the clamp is inert on a healthy run and only bites when the
     * solve has run away.
     */
    public static final float MAX_GAIN = 1.6f;

    /** The reciprocal of {@link #MAX_GAIN}; a gain is clamped symmetrically in log space. */
    public static final float MIN_GAIN = 1f / MAX_GAIN;

    /** Below this a frame is blank and takes no part in the solve. */
    private static final double MIN_USABLE_LUMA = 1e-6;

    private ExposureCompensation() {}

    /**
     * Gains for frames whose mean luminance is {@code meanLuma}, solved over {@code edges}.
     *
     * {@code edges} lists which frame pairs overlap; the pairs may be given in either
     * order as length-2 {@code int[]} arrays. Frames with a zero or missing mean (a blank
     * frame) get gain 1 and take no part in the solve.
     */
    public static float[] solveGains(double[] meanLuma, List<int[]> edges) {
        int size = meanLuma.length;
        if (size == 0) return new float[0];

        // Adjacency, held as flat arrays rather than a map of pairs: the solve
        // sweeps every frame's neighbours a dozen times, and a scan of the whole
        // edge list per frame per sweep is quadratic in the session's size for
        // no reason.
        int[] degree = new int[size];
        boolean[] usable = new boolean[size];
        for (int i = 0; i < size; i++) usable[i] = meanLuma[i] > MIN_USABLE_LUMA;
        for (int e = 0; e < edges.size(); e++) {
            int[] edge = edges.get(e);
            int a = edge[0];
            int b = edge[1];
            if (a >= 0 && a < size && b >= 0 && b < size && a != b && usable[a] && usable[b]) {
                degree[a]++;
                degree[b]++;
            }
        }

        int[] offsets = new int[size + 1];
        for (int i = 0; i < size; i++) offsets[i + 1] = offsets[i] + degree[i];
        int[] neighbours = new int[offsets[size]];
        int[] cursor = new int[size];
        System.arraycopy(offsets, 0, cursor, 0, size);
        for (int e = 0; e < edges.size(); e++) {
            int[] edge = edges.get(e);
            int a = edge[0];
            int b = edge[1];
            if (a >= 0 && a < size && b >= 0 && b < size && a != b && usable[a] && usable[b]) {
                neighbours[cursor[a]++] = b;
                neighbours[cursor[b]++] = a;
            }
        }

        double[] logLuma = new double[size];
        for (int i = 0; i < size; i++) logLuma[i] = usable[i] ? Math.log(meanLuma[i]) : 0.0;
        double[] logGain = new double[size];

        // Gauss-Seidel over the edge equations: each frame's log-gain is the
        // average of what its neighbours imply, which is the normal equation of
        // the least-squares fit and converges in a dozen sweeps for a graph this
        // small. `d_ij = log m_j − log m_i` makes the edge equation
        // `l_i = l_j + d_ij`.
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            for (int i = 0; i < size; i++) {
                int start = offsets[i];
                int end = offsets[i + 1];
                if (end <= start) continue;
                double sum = 0.0;
                for (int slot = start; slot < end; slot++) {
                    int j = neighbours[slot];
                    sum += logGain[j] + logLuma[j] - logLuma[i];
                }
                logGain[i] = sum / (end - start);
            }
        }

        // Remove the gauge freedom among the frames the graph actually ties
        // together: the sphere's overall exposure is not ours to change, so the
        // connected gains are scaled to a geometric mean of one. Frames with no
        // overlap partner have no equation to satisfy and stay exactly neutral.
        double logSum = 0.0;
        int connectedCount = 0;
        for (int i = 0; i < size; i++) {
            if (offsets[i + 1] > offsets[i]) {
                logSum += logGain[i];
                connectedCount++;
            }
        }
        double offset = connectedCount > 0 ? logSum / connectedCount : 0.0;

        double maxLog = Math.log((double) MAX_GAIN);
        float[] gains = new float[size];
        for (int i = 0; i < size; i++) {
            if (offsets[i + 1] <= offsets[i]) {
                gains[i] = 1f;
            } else {
                gains[i] = (float) Math.exp(clamp(logGain[i] - offset, -maxLog, maxLog));
            }
        }
        return gains;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
