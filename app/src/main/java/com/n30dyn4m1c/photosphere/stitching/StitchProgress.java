package com.n30dyn4m1c.photosphere.stitching;

/**
 * How far along a stitch is.
 *
 * {@link StitchStage#Reading}, {@link StitchStage#Refining}, {@link StitchStage#Seaming} and
 * {@link StitchStage#Stitching} all report real progress — one frame, one edge,
 * one seam and one band of canvas at a time. The short stages either side
 * leave {@link #getFraction()} null, and the UI shows an indeterminate spinner for
 * those rather than a bar that lies.
 */
public final class StitchProgress {
    public static final StitchProgress Preparing = new StitchProgress(StitchStage.Preparing);

    public final StitchStage stage;
    public final int completed;
    public final int total;

    public StitchProgress(StitchStage stage) {
        this(stage, 0, 0);
    }

    public StitchProgress(StitchStage stage, int completed, int total) {
        this.stage = stage;
        this.completed = completed;
        this.total = total;
    }

    public Float getFraction() {
        if (total <= 0) return null;
        return clamp(completed / (float) total, 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
