package com.n30dyn4m1c.photosphere.camera;

import android.os.Build;

import com.n30dyn4m1c.photosphere.stitching.PivotModel;

/**
 * Per-device tuning, decided once per phone.
 *
 * The generic defaults suit a mid-range device; this profile is the single
 * place a specific phone is tuned. It picks which lens to capture with, caps
 * the still-capture size to what the stitch can actually use, sets how many
 * frames each target is burst before the sharpest is kept, and decides how
 * much resolution and sharpening the stitch ends with.
 *
 * The device detection keys off the model, so a new flagship is a few lines
 * here rather than a sprinkling of {@code if (Build.MODEL == …)} across the camera
 * and stitching code.
 */
public final class SphereDeviceProfile {

    /**
     * Whether to prefer the back camera with the widest field of view.
     *
     * True trades sharpness for speed — a wide lens shoots the sphere in far
     * fewer frames. False uses the main camera, which is the sharper lens.
     */
    private final boolean preferWidestCamera;
    /** Long edge the still captures are capped to, in pixels (12 MP 4:3 = 4000). */
    private final int captureMaxLongEdgePx;
    /** Frames each target is burst before the sharpest is kept. */
    private final int burstPerTarget;
    /** Long edge each frame is decoded to before stitching. */
    private final int stitchMaxInputDimension;
    /** Width cap for the finished sphere; the canvas is always half as tall. */
    private final int stitchMaxOutputWidth;
    /** Unsharp-mask strength on the finished canvas, 0 = off. */
    private final float unsharpAmount;
    /**
     * How the phone is assumed to be swung around: how far the lens sits from
     * the axis the user turns about, and how far away the scene is taken to be.
     * See {@link PivotModel} — this is the correction for pivoting around your body
     * rather than around the camera.
     */
    private final PivotModel pivot;

    /**
     * The default: a mid-range phone with one usable back camera. The wider
     * lens is preferred because every extra frame is capture time and
     * sensor drift, and the stitch runs at the compact 1024 → 4096 profile
     * that a mid-range chip and memory envelope can chew through.
     */
    private static final SphereDeviceProfile DEFAULT = new SphereDeviceProfile(
            true,
            4000,
            2,
            1024,
            4096,
            0f,
            PivotModel.HandheldBodySwivel
    );

    /**
     * Samsung Galaxy S23 / S23+ / S23 Ultra.
     *
     * Tuned for sharpness, because the hardware earns it:
     *
     * - <b>The 50 MP main is the capture lens</b> ({@code preferWidestCamera} is
     *   false). It is the sharpest camera on the phone — better glass, OIS,
     *   and a much larger sensor than the ultrawide — so a sphere on the
     *   main is notably crisper even at the same output size. It costs more
     *   frames (~33 instead of ~11), which is the price of sharpness.
     * - <b>Stills are capped at the 12 MP binned output</b>, which writes fast
     *   and is far more than the stitch reads; 50 MP stills would only slow
     *   the burst.
     * - <b>The stitch runs at 2000 → 6144.</b> Frames are decoded at a 2000 px
     *   long edge and the sphere is rendered up to 6144 wide (Google's
     *   Photo Sphere standard is 5376), so the main camera's detail reaches
     *   the finished photo instead of being thrown away at 4096.
     * - <b>Burst three and sharpen lightly.</b> Three frames per target make a
     *   sharp survivor more likely, and a gentle unsharp mask lifts the
     *   finished canvas without haloing the parts of the sphere that were
     *   never shot.
     */
    private static final SphereDeviceProfile SAMSUNG_S23 = new SphereDeviceProfile(
            false,
            4000,
            3,
            2000,
            6144,
            0.3f,
            PivotModel.HandheldBodySwivel
    );

    public SphereDeviceProfile(
            boolean preferWidestCamera,
            int captureMaxLongEdgePx,
            int burstPerTarget,
            int stitchMaxInputDimension,
            int stitchMaxOutputWidth,
            float unsharpAmount,
            PivotModel pivot
    ) {
        this.preferWidestCamera = preferWidestCamera;
        this.captureMaxLongEdgePx = captureMaxLongEdgePx;
        this.burstPerTarget = burstPerTarget;
        this.stitchMaxInputDimension = stitchMaxInputDimension;
        this.stitchMaxOutputWidth = stitchMaxOutputWidth;
        this.unsharpAmount = unsharpAmount;
        this.pivot = pivot;
    }

    public boolean getPreferWidestCamera() {
        return preferWidestCamera;
    }

    public int getCaptureMaxLongEdgePx() {
        return captureMaxLongEdgePx;
    }

    public int getBurstPerTarget() {
        return burstPerTarget;
    }

    public int getStitchMaxInputDimension() {
        return stitchMaxInputDimension;
    }

    public int getStitchMaxOutputWidth() {
        return stitchMaxOutputWidth;
    }

    public float getUnsharpAmount() {
        return unsharpAmount;
    }

    public PivotModel getPivot() {
        return pivot;
    }

    /** The profile for the device this process is running on. */
    public static SphereDeviceProfile forDevice() {
        return isSamsungGalaxyS23() ? SAMSUNG_S23 : DEFAULT;
    }

    private static boolean isSamsungGalaxyS23() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER)
                && (startsWith(Build.MODEL, "SM-S911")
                || startsWith(Build.MODEL, "SM-S916")
                || startsWith(Build.MODEL, "SM-S918"));
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SphereDeviceProfile)) return false;
        SphereDeviceProfile that = (SphereDeviceProfile) o;
        if (preferWidestCamera != that.preferWidestCamera) return false;
        if (captureMaxLongEdgePx != that.captureMaxLongEdgePx) return false;
        if (burstPerTarget != that.burstPerTarget) return false;
        if (stitchMaxInputDimension != that.stitchMaxInputDimension) return false;
        if (stitchMaxOutputWidth != that.stitchMaxOutputWidth) return false;
        if (Float.compare(that.unsharpAmount, unsharpAmount) != 0) return false;
        return pivot != null ? pivot.equals(that.pivot) : that.pivot == null;
    }

    @Override
    public int hashCode() {
        int result = preferWidestCamera ? 1 : 0;
        result = 31 * result + captureMaxLongEdgePx;
        result = 31 * result + burstPerTarget;
        result = 31 * result + stitchMaxInputDimension;
        result = 31 * result + stitchMaxOutputWidth;
        result = 31 * result + (unsharpAmount != 0.0f ? Float.floatToIntBits(unsharpAmount) : 0);
        result = 31 * result + (pivot != null ? pivot.hashCode() : 0);
        return result;
    }
}
