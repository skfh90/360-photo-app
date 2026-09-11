package com.n30dyn4m1c.photosphere.stitching;

/**
 * Turns a solved label map into the narrow seam feather.
 *
 * Each grid pixel gets the *nearest* pixel painted by a different frame and a
 * weight for it that starts at half on the boundary and falls to zero over
 * {@code halfWidth} pixels — the "fading only a few pixels across the cut" of the
 * README's seam carving. The winner always holds weight 1; only the losing
 * frame's contribution ramps, so the blend inside the feather goes smoothly
 * from one frame to the other and stays pure either side.
 *
 * The scan is deliberately a square window rather than a distance transform:
 * the feather is a few pixels wide, so the O(window) work per pixel over the
 * small label grid is far cheaper than a transform over the full canvas.
 */
public final class SeamFeather {

    private SeamFeather() {}

    /**
     * Losing-frame identity and blend weight for each grid pixel.
     *
     * Replaces the Kotlin {@code Pair<IntArray, FloatArray>} return.
     */
    public static final class Result {
        public final int[] loserLabel;
        public final float[] loserWeight;

        public Result(int[] loserLabel, float[] loserWeight) {
            this.loserLabel = loserLabel;
            this.loserWeight = loserWeight;
        }
    }

    public static Result derive(int[] labelMap, int gridWidth, int gridHeight, int halfWidth) {
        int size = gridWidth * gridHeight;
        int[] loserLabel = new int[size];
        for (int i = 0; i < size; i++) loserLabel[i] = -1;
        float[] loserWeight = new float[size];
        for (int r = 0; r < gridHeight; r++) {
            for (int c = 0; c < gridWidth; c++) {
                int index = r * gridWidth + c;
                int winner = labelMap[index];
                if (winner < 0) continue;

                int bestLabel = -1;
                int bestDistance = halfWidth + 1;
                int minRow = Math.max(0, r - halfWidth);
                int maxRow = Math.min(gridHeight - 1, r + halfWidth);
                int minCol = Math.max(0, c - halfWidth);
                int maxCol = Math.min(gridWidth - 1, c + halfWidth);
                for (int nr = minRow; nr <= maxRow; nr++) {
                    for (int nc = minCol; nc <= maxCol; nc++) {
                        int neighbour = labelMap[nr * gridWidth + nc];
                        if (neighbour < 0 || neighbour == winner) continue;
                        int distance = Math.max(Math.abs(nr - r), Math.abs(nc - c));
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            bestLabel = neighbour;
                        }
                    }
                }

                if (bestLabel >= 0) {
                    loserLabel[index] = bestLabel;
                    // Half on the boundary, zero just past the feather, linear
                    // in between — one divide per boundary pixel, cheap.
                    loserWeight[index] = 0.5f * (1f - bestDistance / (halfWidth + 1f));
                }
            }
        }
        return new Result(loserLabel, loserWeight);
    }
}
