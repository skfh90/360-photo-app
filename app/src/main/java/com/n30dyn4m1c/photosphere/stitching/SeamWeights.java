package com.n30dyn4m1c.photosphere.stitching;

/**
 * Which frame paints each pixel, at a reduced "label resolution".
 *
 * The renderer paints one frame per pixel; this is the map that says which. It
 * lives at a fraction of the canvas resolution — every entry covers a {@code scale}×
 * {@code scale} block of output pixels — because a seam only needs to be decided to
 * within a few output pixels, and the reduced grid keeps the graph-cut solve
 * and the feathering cheap.
 *
 * {@link #labelMap} holds the winning frame per grid pixel (-1 where nothing covers
 * the sphere). The seam itself is never a hard knife edge: {@link #loserLabel} and
 * {@link #loserWeight} name, for grid pixels within a feather of a boundary, the frame
 * on the other side and how much of it the blend should keep — 0.5 on the
 * boundary itself, ramping to nothing a few pixels away. Everywhere else the
 * winner paints alone at full strength, which is the sharpness seam carving
 * exists for.
 */
public final class SeamWeights {
    public final int gridWidth;
    public final int gridHeight;
    public final int scale;
    public final int[] labelMap;
    public final int[] loserLabel;
    public final float[] loserWeight;

    public SeamWeights(
        int gridWidth,
        int gridHeight,
        int scale,
        int[] labelMap,
        int[] loserLabel,
        float[] loserWeight
    ) {
        if (gridWidth <= 0 || gridHeight <= 0) {
            throw new IllegalArgumentException("seam grid must have extent");
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("seam scale must be positive");
        }
        int size = gridWidth * gridHeight;
        if (labelMap.length != size || loserLabel.length != size || loserWeight.length != size) {
            throw new IllegalArgumentException("seam arrays must match the grid");
        }
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
        this.scale = scale;
        this.labelMap = labelMap;
        this.loserLabel = loserLabel;
        this.loserWeight = loserWeight;
    }

    /**
     * The blend weight of {@code frameIndex} at one pixel of a pyramid level.
     *
     * {@code levelRow}/{@code levelCol} are coordinates in the level's own canvas (the
     * renderer's banded accumulation), so {@code sourceScale} ({@code 2^level}) brings them
     * back to level-0 pixels before the grid lookup.
     */
    public float weightFor(int frameIndex, int levelRow, int levelCol, int sourceScale) {
        int row0 = levelRow * sourceScale;
        int col0 = levelCol * sourceScale;
        int gridRow = clamp(row0 / scale, 0, gridHeight - 1);
        int gridCol = clamp(col0 / scale, 0, gridWidth - 1);
        int index = gridRow * gridWidth + gridCol;
        int winner = labelMap[index];
        if (winner == frameIndex) return 1f;
        if (loserLabel[index] == frameIndex) return loserWeight[index];
        return 0f;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
