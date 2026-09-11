package com.n30dyn4m1c.photosphere.camera;

/**
 * A target seen from the camera: its direction expressed in the camera's own
 * frame, where +X is to the right of the frame, +Y is up, and +Z is straight
 * down the lens.
 *
 * The vector is a unit vector, so {@link #getZ()} is the cosine of the angle off the optical
 * axis — which is all {@link #getAngularDistanceDegrees()} is.
 */
public final class TargetView {

    /**
     * Directions closer to the image plane than this are treated as behind
     * the camera: their projection races off to infinity and is useless as a
     * screen position.
     */
    public static final float MIN_FORWARD_COMPONENT = 1e-3f;

    private final float x;
    private final float y;
    private final float z;
    private final float angularDistanceDegrees;

    public TargetView(float x, float y, float z, float angularDistanceDegrees) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.angularDistanceDegrees = angularDistanceDegrees;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZ() {
        return z;
    }

    public float getAngularDistanceDegrees() {
        return angularDistanceDegrees;
    }

    /** False when the target sits behind the camera and cannot be projected. */
    public boolean isInFront() {
        return z > MIN_FORWARD_COMPONENT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TargetView)) return false;
        TargetView that = (TargetView) o;
        return Float.compare(that.x, x) == 0
                && Float.compare(that.y, y) == 0
                && Float.compare(that.z, z) == 0
                && Float.compare(that.angularDistanceDegrees, angularDistanceDegrees) == 0;
    }

    @Override
    public int hashCode() {
        int result = (x != 0.0f ? Float.floatToIntBits(x) : 0);
        result = 31 * result + (y != 0.0f ? Float.floatToIntBits(y) : 0);
        result = 31 * result + (z != 0.0f ? Float.floatToIntBits(z) : 0);
        result = 31 * result
                + (angularDistanceDegrees != 0.0f ? Float.floatToIntBits(angularDistanceDegrees) : 0);
        return result;
    }
}
