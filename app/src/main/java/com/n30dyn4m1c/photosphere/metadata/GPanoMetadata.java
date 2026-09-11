package com.n30dyn4m1c.photosphere.metadata;

/**
 * The Google Photo Sphere (GPano) properties that mark a JPEG as a 360° photo.
 *
 * <p>Every length is in pixels of the image the metadata is attached to. The
 * {@code CroppedArea*} group describes which part of the full sphere the pixels
 * actually cover: for a full equirectangular frame it is the whole image, which
 * is what {@link #forFullPano} produces. A partial capture keeps the full-pano
 * dimensions at the size the <em>complete</em> sphere would have been and places the
 * covered rectangle inside it — {@link #forSphereRegion} expresses a ring capture that
 * way, so a viewer renders the band the frames actually cover and limits
 * panning to it instead of showing the black wedges where nothing was shot.
 */
public final class GPanoMetadata {

    /** The only projection Google Photos, Facebook and friends will render. */
    public static final String PROJECTION_EQUIRECTANGULAR = "equirectangular";

    /** Width the complete sphere occupies. */
    public final int fullPanoWidthPixels;
    /** Height the complete sphere occupies; half {@link #fullPanoWidthPixels} at 2:1. */
    public final int fullPanoHeightPixels;
    /** Width of the region of the sphere this image holds. */
    public final int croppedAreaImageWidthPixels;
    /** Height of the region of the sphere this image holds. */
    public final int croppedAreaImageHeightPixels;
    /** Left edge of that region within the full sphere. */
    public final int croppedAreaLeftPixels;
    /** Top edge of that region within the full sphere. */
    public final int croppedAreaTopPixels;
    /** Projection the pixels are in. Viewers only understand {@link #PROJECTION_EQUIRECTANGULAR}. */
    public final String projectionType;
    /** Whether viewers should open this in a pannable sphere viewer. */
    public final boolean usePanoramaViewer;

    public GPanoMetadata(
            int fullPanoWidthPixels,
            int fullPanoHeightPixels,
            int croppedAreaImageWidthPixels,
            int croppedAreaImageHeightPixels,
            int croppedAreaLeftPixels,
            int croppedAreaTopPixels) {
        this(
                fullPanoWidthPixels,
                fullPanoHeightPixels,
                croppedAreaImageWidthPixels,
                croppedAreaImageHeightPixels,
                croppedAreaLeftPixels,
                croppedAreaTopPixels,
                PROJECTION_EQUIRECTANGULAR,
                true);
    }

    public GPanoMetadata(
            int fullPanoWidthPixels,
            int fullPanoHeightPixels,
            int croppedAreaImageWidthPixels,
            int croppedAreaImageHeightPixels,
            int croppedAreaLeftPixels,
            int croppedAreaTopPixels,
            String projectionType,
            boolean usePanoramaViewer) {
        if (!(fullPanoWidthPixels > 0 && fullPanoHeightPixels > 0)) {
            throw new IllegalArgumentException(
                    "full pano must have area, was "
                            + fullPanoWidthPixels + "x" + fullPanoHeightPixels);
        }
        if (!(croppedAreaImageWidthPixels > 0 && croppedAreaImageHeightPixels > 0)) {
            throw new IllegalArgumentException(
                    "cropped area must have area, was "
                            + croppedAreaImageWidthPixels + "x" + croppedAreaImageHeightPixels);
        }
        if (!(croppedAreaLeftPixels >= 0 && croppedAreaTopPixels >= 0)) {
            throw new IllegalArgumentException(
                    "cropped area origin must be inside the sphere, was "
                            + "(" + croppedAreaLeftPixels + ", " + croppedAreaTopPixels + ")");
        }
        if (croppedAreaLeftPixels + croppedAreaImageWidthPixels > fullPanoWidthPixels) {
            throw new IllegalArgumentException(
                    "cropped area runs past the right edge of the sphere");
        }
        if (croppedAreaTopPixels + croppedAreaImageHeightPixels > fullPanoHeightPixels) {
            throw new IllegalArgumentException(
                    "cropped area runs past the bottom edge of the sphere");
        }
        if (projectionType == null || projectionType.trim().isEmpty()) {
            throw new IllegalArgumentException("projectionType must not be blank");
        }
        this.fullPanoWidthPixels = fullPanoWidthPixels;
        this.fullPanoHeightPixels = fullPanoHeightPixels;
        this.croppedAreaImageWidthPixels = croppedAreaImageWidthPixels;
        this.croppedAreaImageHeightPixels = croppedAreaImageHeightPixels;
        this.croppedAreaLeftPixels = croppedAreaLeftPixels;
        this.croppedAreaTopPixels = croppedAreaTopPixels;
        this.projectionType = projectionType;
        this.usePanoramaViewer = usePanoramaViewer;
    }

    /**
     * Metadata for an image that <em>is</em> the whole sphere: the cropped area
     * covers all of it, anchored at the origin.
     */
    public static GPanoMetadata forFullPano(int imageWidth, int imageHeight) {
        return new GPanoMetadata(
                imageWidth,
                imageHeight,
                imageWidth,
                imageHeight,
                0,
                0);
    }

    /**
     * Metadata for an image that holds a <em>region</em> of the sphere, mapped
     * equirectangularly.
     *
     * <p>The image covers {@code longitudeSpanDegrees} of longitude centred on
     * {@code centerLongitudeDegrees} and {@code latitudeSpanDegrees} of latitude centred
     * on {@code centerLatitudeDegrees}. A viewer maps the full-pano dimensions to
     * the whole 360°×180° sphere and renders the cropped area inside it, so
     * a ring capture — one band all the way around the horizon — is expressed
     * as the matching horizontal slice of a full sphere, and panning is
     * limited to the band the frames actually covered. A full sphere passed
     * through here resolves to the same values as {@link #forFullPano}.
     */
    public static GPanoMetadata forSphereRegion(
            int imageWidth,
            int imageHeight,
            float longitudeSpanDegrees,
            float centerLongitudeDegrees,
            float latitudeSpanDegrees,
            float centerLatitudeDegrees) {
        if (longitudeSpanDegrees < 1f || longitudeSpanDegrees > 360f) {
            throw new IllegalArgumentException("longitude span: " + longitudeSpanDegrees);
        }
        if (latitudeSpanDegrees < 1f || latitudeSpanDegrees > 180f) {
            throw new IllegalArgumentException("latitude span: " + latitudeSpanDegrees);
        }
        int fullWidth = Math.round(imageWidth * 360f / longitudeSpanDegrees);
        int fullHeight = Math.round(imageHeight * 180f / latitudeSpanDegrees);
        int left = Math.round((centerLongitudeDegrees + 180f) / 360f * fullWidth)
                - imageWidth / 2;
        int top = Math.round(
                (90f - centerLatitudeDegrees - latitudeSpanDegrees / 2f) / 180f * fullHeight);
        return new GPanoMetadata(
                fullWidth,
                fullHeight,
                imageWidth,
                imageHeight,
                Math.max(left, 0),
                Math.max(top, 0));
    }

    public int getFullPanoWidthPixels() {
        return fullPanoWidthPixels;
    }

    public int getFullPanoHeightPixels() {
        return fullPanoHeightPixels;
    }

    public int getCroppedAreaImageWidthPixels() {
        return croppedAreaImageWidthPixels;
    }

    public int getCroppedAreaImageHeightPixels() {
        return croppedAreaImageHeightPixels;
    }

    public int getCroppedAreaLeftPixels() {
        return croppedAreaLeftPixels;
    }

    public int getCroppedAreaTopPixels() {
        return croppedAreaTopPixels;
    }

    public String getProjectionType() {
        return projectionType;
    }

    public boolean getUsePanoramaViewer() {
        return usePanoramaViewer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GPanoMetadata)) {
            return false;
        }
        GPanoMetadata that = (GPanoMetadata) o;
        return fullPanoWidthPixels == that.fullPanoWidthPixels
                && fullPanoHeightPixels == that.fullPanoHeightPixels
                && croppedAreaImageWidthPixels == that.croppedAreaImageWidthPixels
                && croppedAreaImageHeightPixels == that.croppedAreaImageHeightPixels
                && croppedAreaLeftPixels == that.croppedAreaLeftPixels
                && croppedAreaTopPixels == that.croppedAreaTopPixels
                && usePanoramaViewer == that.usePanoramaViewer
                && projectionType.equals(that.projectionType);
    }

    @Override
    public int hashCode() {
        int result = fullPanoWidthPixels;
        result = 31 * result + fullPanoHeightPixels;
        result = 31 * result + croppedAreaImageWidthPixels;
        result = 31 * result + croppedAreaImageHeightPixels;
        result = 31 * result + croppedAreaLeftPixels;
        result = 31 * result + croppedAreaTopPixels;
        result = 31 * result + projectionType.hashCode();
        result = 31 * result + (usePanoramaViewer ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "GPanoMetadata("
                + "fullPanoWidthPixels=" + fullPanoWidthPixels
                + ", fullPanoHeightPixels=" + fullPanoHeightPixels
                + ", croppedAreaImageWidthPixels=" + croppedAreaImageWidthPixels
                + ", croppedAreaImageHeightPixels=" + croppedAreaImageHeightPixels
                + ", croppedAreaLeftPixels=" + croppedAreaLeftPixels
                + ", croppedAreaTopPixels=" + croppedAreaTopPixels
                + ", projectionType=" + projectionType
                + ", usePanoramaViewer=" + usePanoramaViewer
                + ")";
    }
}
