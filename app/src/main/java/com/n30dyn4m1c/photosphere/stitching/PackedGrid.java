package com.n30dyn4m1c.photosphere.stitching;

/**
 * The sparse per-pixel label data the seam solver works on.
 *
 * Every pixel of the reduced label grid lists the frames that cover it, their
 * colours as sampled from those frames, and the cost of assigning the pixel to
 * each of them. The arrays are packed per node so the whole thing stays flat:
 * node {@code n} owns {@code nodeLabelCount[n]} consecutive entries in {@link #nodeLabels},
 * {@link #nodeData} and (three per entry) {@link #nodeColors}, starting at
 * {@code offsets[n]}. {@link #nodeMean} is the mean of the covering colours at each pixel,
 * which is what a frame *not* covering that pixel is said to have recorded
 * there — the fill value that keeps the seam cost a true metric (see
 * {@link SeamSolver}).
 */
public final class PackedGrid {
    public final int width;
    public final int height;
    public final int labelCount;
    public final int[] nodeLabelCount;
    public final int[] nodeLabels;
    public final float[] nodeData;
    public final float[] nodeColors;
    public final float[] nodeMean;
    public final int nodeCount;
    /** Where each node's packed entries begin; {@code offsets[nodeCount]} is the total. */
    public final int[] offsets;

    public PackedGrid(
        int width,
        int height,
        int labelCount,
        int[] nodeLabelCount,
        int[] nodeLabels,
        float[] nodeData,
        float[] nodeColors,
        float[] nodeMean
    ) {
        this.width = width;
        this.height = height;
        this.labelCount = labelCount;
        this.nodeLabelCount = nodeLabelCount;
        this.nodeLabels = nodeLabels;
        this.nodeData = nodeData;
        this.nodeColors = nodeColors;
        this.nodeMean = nodeMean;
        this.nodeCount = width * height;
        int[] computed = new int[nodeCount + 1];
        for (int n = 0; n < nodeCount; n++) computed[n + 1] = computed[n] + nodeLabelCount[n];
        this.offsets = computed;
    }
}
