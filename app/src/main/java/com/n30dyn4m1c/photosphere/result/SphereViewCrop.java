package com.n30dyn4m1c.photosphere.result;

import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata;

/**
 * Where the stitched image sits on the full 360°×180° sphere.
 *
 * The JPEG the stitcher writes is the <em>cropped</em> region: a full sphere covers
 * the whole canvas, a horizon ring is a horizontal band. The viewer maps a
 * look direction onto that region the same way GPano metadata does, and paints
 * black where the run never reached.
 *
 * Each component is a fraction of the full pano, 0..1.
 */
public final class SphereViewCrop {

    public static final SphereViewCrop Full = new SphereViewCrop(0f, 0f, 1f, 1f);

    private final float left;
    private final float top;
    private final float width;
    private final float height;

    public SphereViewCrop(float left, float top, float width, float height) {
        if (left < 0f || left > 1f || top < 0f || top > 1f) {
            throw new IllegalArgumentException("crop origin " + left + ", " + top);
        }
        if (width <= 0f || height <= 0f) {
            throw new IllegalArgumentException("crop size " + width + "x" + height);
        }
        if (left + width > 1.0001f || top + height > 1.0001f) {
            throw new IllegalArgumentException(
                    "crop runs off the sphere: " + left + "+" + width + ", " + top + "+" + height
            );
        }
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
    }

    public float getLeft() {
        return left;
    }

    public float getTop() {
        return top;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    /** True when the image wraps all the way around in longitude. */
    public boolean wrapsLongitude() {
        return width >= 0.99f;
    }

    public boolean getWrapsLongitude() {
        return wrapsLongitude();
    }

    /** Highest latitude the image covers, degrees, positive up. */
    public float getMaxLatitudeDegrees() {
        return 90f - top * 180f;
    }

    /** Lowest latitude the image covers, degrees, positive up. */
    public float getMinLatitudeDegrees() {
        return 90f - (top + height) * 180f;
    }

    public static SphereViewCrop from(GPanoMetadata gpano) {
        float fullW = gpano.getFullPanoWidthPixels();
        float fullH = gpano.getFullPanoHeightPixels();
        return new SphereViewCrop(
                gpano.getCroppedAreaLeftPixels() / fullW,
                gpano.getCroppedAreaTopPixels() / fullH,
                gpano.getCroppedAreaImageWidthPixels() / fullW,
                gpano.getCroppedAreaImageHeightPixels() / fullH
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SphereViewCrop)) return false;
        SphereViewCrop that = (SphereViewCrop) o;
        return Float.compare(that.left, left) == 0
                && Float.compare(that.top, top) == 0
                && Float.compare(that.width, width) == 0
                && Float.compare(that.height, height) == 0;
    }

    @Override
    public int hashCode() {
        int result = (left != 0.0f ? Float.floatToIntBits(left) : 0);
        result = 31 * result + (top != 0.0f ? Float.floatToIntBits(top) : 0);
        result = 31 * result + (width != 0.0f ? Float.floatToIntBits(width) : 0);
        result = 31 * result + (height != 0.0f ? Float.floatToIntBits(height) : 0);
        return result;
    }
}
