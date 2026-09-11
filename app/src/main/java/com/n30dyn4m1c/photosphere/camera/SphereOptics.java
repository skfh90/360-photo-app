package com.n30dyn4m1c.photosphere.camera;

import com.n30dyn4m1c.photosphere.stitching.RadialDistortion;

/**
 * The optics of the rear camera, as this device reports them.
 *
 * Carries both halves the pipeline needs: the on-screen {@link FieldOfView} for
 * laying out targets and placing markers, and the lens's radial
 * {@link RadialDistortion} so the stitcher can correct frame edges. {@code null} distortion
 * means the camera gave no calibration — the stitcher then assumes a pinhole.
 */
public final class SphereOptics {

    private final FieldOfView fieldOfView;
    private final RadialDistortion radialDistortion;
    /**
     * Clockwise rotation, in degrees, that turns a raw (sensor-native) frame
     * into the display-upright portrait frame the {@link #getFieldOfView()} describes —
     * the same rotation CameraX records in each still's EXIF {@code ORIENTATION}
     * tag. The stitcher uses it as the fallback when a frame's EXIF rotation
     * is missing or was lost in a metadata rewrite, so the frame still lands
     * upright against its pose.
     */
    private final int portraitRotationDegrees;

    public SphereOptics(
            FieldOfView fieldOfView,
            RadialDistortion radialDistortion,
            int portraitRotationDegrees
    ) {
        this.fieldOfView = fieldOfView;
        this.radialDistortion = radialDistortion;
        this.portraitRotationDegrees = portraitRotationDegrees;
    }

    public FieldOfView getFieldOfView() {
        return fieldOfView;
    }

    public RadialDistortion getRadialDistortion() {
        return radialDistortion;
    }

    public int getPortraitRotationDegrees() {
        return portraitRotationDegrees;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SphereOptics)) return false;
        SphereOptics that = (SphereOptics) o;
        if (portraitRotationDegrees != that.portraitRotationDegrees) return false;
        if (fieldOfView != null ? !fieldOfView.equals(that.fieldOfView) : that.fieldOfView != null) {
            return false;
        }
        return radialDistortion != null
                ? radialDistortion.equals(that.radialDistortion)
                : that.radialDistortion == null;
    }

    @Override
    public int hashCode() {
        int result = fieldOfView != null ? fieldOfView.hashCode() : 0;
        result = 31 * result + (radialDistortion != null ? radialDistortion.hashCode() : 0);
        result = 31 * result + portraitRotationDegrees;
        return result;
    }
}
