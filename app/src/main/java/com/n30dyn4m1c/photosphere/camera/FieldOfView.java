package com.n30dyn4m1c.photosphere.camera;

/**
 * Angular extent of the preview, as it appears on screen.
 *
 * These are <em>screen</em> axes, not sensor axes: on a portrait phone the vertical
 * field of view is the wider of the two because the sensor's long edge runs down
 * the display.
 */
public final class FieldOfView {

    private final float horizontalDegrees;
    private final float verticalDegrees;

    public FieldOfView(float horizontalDegrees, float verticalDegrees) {
        if (horizontalDegrees < 1f || horizontalDegrees > 179f) {
            throw new IllegalArgumentException("horizontal fov out of range: " + horizontalDegrees);
        }
        if (verticalDegrees < 1f || verticalDegrees > 179f) {
            throw new IllegalArgumentException("vertical fov out of range: " + verticalDegrees);
        }
        this.horizontalDegrees = horizontalDegrees;
        this.verticalDegrees = verticalDegrees;
    }

    public float getHorizontalDegrees() {
        return horizontalDegrees;
    }

    public float getVerticalDegrees() {
        return verticalDegrees;
    }

    /** Swaps the axes, for turning a sensor-frame reading into a screen-frame one. */
    public FieldOfView transposed() {
        return new FieldOfView(verticalDegrees, horizontalDegrees);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FieldOfView)) return false;
        FieldOfView that = (FieldOfView) o;
        return Float.compare(that.horizontalDegrees, horizontalDegrees) == 0
                && Float.compare(that.verticalDegrees, verticalDegrees) == 0;
    }

    @Override
    public int hashCode() {
        int result = (horizontalDegrees != 0.0f ? Float.floatToIntBits(horizontalDegrees) : 0);
        result = 31 * result + (verticalDegrees != 0.0f ? Float.floatToIntBits(verticalDegrees) : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FieldOfView(horizontalDegrees=" + horizontalDegrees
                + ", verticalDegrees=" + verticalDegrees + ")";
    }
}
