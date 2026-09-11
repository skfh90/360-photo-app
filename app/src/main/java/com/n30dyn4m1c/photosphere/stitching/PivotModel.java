package com.n30dyn4m1c.photosphere.stitching;

/**
 * How far the lens sits from the axis the user actually turns about.
 *
 * A panorama assumes every frame was shot from one point — the lens's entrance
 * pupil. Nobody shoots one that way. Holding a phone up and swivelling <em>your
 * body</em> puts the camera on the end of a lever roughly a third of a metre long,
 * so between the first frame and the one 90° later the lens has moved half a
 * metre sideways. That is translation, not rotation, and it is what shows up as
 * "the frames don't line up" on near objects while the far ones sit fine.
 *
 * The geometry of it is simple enough to correct for. The camera is not
 * anywhere: it is at {@code leverArm × forward}, always ahead of the pivot along its
 * own optical axis, because that is what holding a phone out in front of you
 * and turning does. Everything the pipeline projects is a direction <em>from the
 * pivot</em>, so a scene point taken to sit {@link #getSceneDistanceMeters()} away is
 * {@code sceneDistance × direction}, and the ray the camera saw it along is that minus
 * the lever arm.
 *
 * Only the ratio of the two lengths survives that subtraction — see {@link #getRatio()} —
 * which is why neither number has to be measured precisely. It also collapses to
 * something almost free: because the lever arm points straight down the optical
 * axis, it cancels out of the two lateral components entirely and leaves the
 * depth as the only term to correct.
 *
 * The default is deliberately cautious. A ratio that is too large bends frames
 * apart just as surely as parallax bends them together, so the nominal scene
 * distance is set well beyond a typical room: the correction then takes most of
 * the error out of a close scene and adds barely a degree to a distant one,
 * which is comfortably inside what PoseRefiner absorbs.
 */
public final class PivotModel {

    /** Beyond this the model does more harm than the parallax it corrects. */
    public static final double MAX_RATIO = 0.25;

    /** The lens is the pivot: a tripod, or a phone turned about its own camera. */
    public static final PivotModel None = new PivotModel(0f, 1f);

    /**
     * A phone held out in front of a standing person who turns on the spot.
     *
     * 0.35 m is chest-to-lens with the elbows in; 10 m is a nominal scene
     * distance chosen long rather than typical, for the reason in the class
     * docs.
     */
    public static final PivotModel HandheldBodySwivel = new PivotModel(0.35f, 10f);

    /** Distance from the turning axis to the lens, in metres. */
    public final float leverArmMeters;
    /** Distance the scene is assumed to sit at, in metres. */
    public final float sceneDistanceMeters;
    /**
     * {@code leverArm / sceneDistance} — the only quantity the projection needs.
     *
     * Clamped to {@link #MAX_RATIO}: past that the correction is larger than the pose
     * error it is meant to remove, and a scene closer than a few lever arms
     * cannot be stitched into a sphere at all, however it is projected.
     */
    public final double ratio;

    public PivotModel(float leverArmMeters, float sceneDistanceMeters) {
        if (leverArmMeters < 0f) {
            throw new IllegalArgumentException("lever arm must not be negative, was " + leverArmMeters);
        }
        if (sceneDistanceMeters <= 0f) {
            throw new IllegalArgumentException("scene distance must be positive, was " + sceneDistanceMeters);
        }
        this.leverArmMeters = leverArmMeters;
        this.sceneDistanceMeters = sceneDistanceMeters;
        double raw = ((double) leverArmMeters) / ((double) sceneDistanceMeters);
        this.ratio = coerceIn(raw, 0.0, MAX_RATIO);
    }

    public float getLeverArmMeters() {
        return leverArmMeters;
    }

    public float getSceneDistanceMeters() {
        return sceneDistanceMeters;
    }

    public double getRatio() {
        return ratio;
    }

    private static double coerceIn(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PivotModel)) return false;
        PivotModel that = (PivotModel) o;
        return Float.compare(that.leverArmMeters, leverArmMeters) == 0
                && Float.compare(that.sceneDistanceMeters, sceneDistanceMeters) == 0;
    }

    @Override
    public int hashCode() {
        int result = (leverArmMeters != 0.0f ? Float.floatToIntBits(leverArmMeters) : 0);
        result = 31 * result
                + (sceneDistanceMeters != 0.0f ? Float.floatToIntBits(sceneDistanceMeters) : 0);
        return result;
    }
}
