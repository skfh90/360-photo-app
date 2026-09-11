package com.n30dyn4m1c.photosphere.camera;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Log;
import android.util.SizeF;
import android.view.Surface;

import com.n30dyn4m1c.photosphere.stitching.RadialDistortion;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the on-screen field of view from the camera's optics.
 *
 * Two independent estimates are made and then reconciled, because either one
 * alone will quietly mislead on some phone:
 *
 * - <b>From the calibration.</b> {@code LENS_INTRINSIC_CALIBRATION} reports the focal
 *   length in pixels, which with the array it is quoted against gives the exact
 *   angle each edge subtends. It is quoted against the <em>pre-correction</em> active
 *   array, not the plain active array — using the wrong one is a small error,
 *   and using an array from a different sensor mode is a factor-of-two one.
 * - <b>From the geometry.</b> Physical sensor size over focal length. Coarser, but
 *   it cannot be quoted against the wrong crop, which makes it the tie-breaker.
 *
 * If they disagree by more than {@link #MAX_ESTIMATE_DISAGREEMENT} the calibration is
 * discarded. That check is the whole point of doing the work twice: a field of
 * view overestimated by a third spaces the capture plan a third too wide, and
 * the first sign of it is a run that will not stitch.
 *
 * Picking the focal length is its own trap on a logical multi-camera, which
 * lists one per physical lens. Taking whichever comes first can hand back the
 * ultrawide's 2.2 mm while the session streams the main lens — a field of view
 * nearly twice the truth, targets spaced nearly twice too far apart, and frames
 * that do not overlap at all. {@link #selectFocalLengthMm} picks by what the profile
 * asked for instead.
 *
 * Two corrections then land the result on the screen: the stream's aspect ratio
 * crops the sensor's angles down to what is really being read out, and the
 * sensor's mounting rotation relative to the display decides which of the two
 * runs across the viewport. The preview's {@code FILL_CENTER} scaling is handled
 * downstream by {@link SphereProjection#focalLengthPx}, which takes the field of view
 * as the <em>uncropped</em> maximum.
 *
 * The same query also reads {@code LENS_DISTORTION} (API 28+), the lens's Brown-Conrady
 * coefficients at the active-array scale, so the stitcher can straighten frame
 * edges instead of treating the lens as a pinhole.
 */
public final class CameraOptics {

    private static final String TAG = "CameraOptics";

    /**
     * Field of view of a mid-range phone's main camera, in the sensor's own
     * (landscape) frame. Used when the camera refuses to describe itself.
     */
    private static final FieldOfView DEFAULT_SENSOR_FIELD_OF_VIEW = new FieldOfView(66f, 52f);

    /**
     * Diagonal field of view a phone's <em>main</em> rear camera has, near enough.
     *
     * Main cameras cluster hard around a 24 mm-equivalent lens; ultrawides are past
     * 100° on the diagonal and telephotos well under 50°. That separation is wide
     * enough to pick the main lens out of a list of focal lengths by nothing more
     * than which one lands closest to here.
     */
    private static final float MAIN_LENS_DIAGONAL_FOV_DEGREES = 78f;

    /**
     * How far the two independent field-of-view estimates may disagree before the
     * calibration is treated as untrustworthy. A quarter is far more than lens
     * variation and far less than the factor-of-two a crop mismatch produces.
     */
    private static final float MAX_ESTIMATE_DISAGREEMENT = 1.25f;

    private CameraOptics() {
    }

    public static SphereOptics estimateSphereOptics(
            Context context,
            int displayRotation,
            SphereDeviceProfile profile
    ) {
        return estimateSphereOptics(context, displayRotation, profile, null, 0f);
    }

    public static SphereOptics estimateSphereOptics(
            Context context,
            int displayRotation,
            SphereDeviceProfile profile,
            String cameraId
    ) {
        return estimateSphereOptics(context, displayRotation, profile, cameraId, 0f);
    }

    public static SphereOptics estimateSphereOptics(
            Context context,
            int displayRotation,
            SphereDeviceProfile profile,
            String cameraId,
            float streamAspectRatio
    ) {
        FieldOfView sensorFieldOfView = DEFAULT_SENSOR_FIELD_OF_VIEW;
        // Portrait-first phones mount the sensor rotated a quarter turn; assume that
        // when the camera cannot be queried, since it matches nearly all hardware.
        int sensorOrientation = 90;
        RadialDistortion radialDistortion = null;

        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager != null) {
            try {
                // Defaults to the lens the profile would bind, so the estimate is
                // usable before the camera provider has resolved anything.
                String id = cameraId != null
                        ? cameraId
                        : SphereCaptureProfile.captureBackCameraId(context, profile);
                if (id != null) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer reportedOrientation =
                            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    sensorOrientation = reportedOrientation != null ? reportedOrientation : 90;

                    Rect array = characteristics.get(
                            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
                    );
                    if (array == null) {
                        array = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    }
                    SizeF physicalSize =
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                    Float focalLengthMm = selectFocalLengthMm(
                            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
                            diagonalMm(physicalSize),
                            profile.getPreferWidestCamera()
                    );

                    FieldOfView fromIntrinsics = null;
                    if (array != null) {
                        float[] intrinsic =
                                characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
                        if (intrinsic != null
                                && intrinsic.length >= 2
                                && intrinsic[0] > 0f
                                && intrinsic[1] > 0f) {
                            fromIntrinsics = new FieldOfView(
                                    fieldOfViewDegrees(array.width(), intrinsic[0]),
                                    fieldOfViewDegrees(array.height(), intrinsic[1])
                            );
                        }
                    }
                    FieldOfView fromGeometry = null;
                    if (physicalSize != null && focalLengthMm != null) {
                        fromGeometry = new FieldOfView(
                                fieldOfViewDegrees(physicalSize.getWidth(), focalLengthMm),
                                fieldOfViewDegrees(physicalSize.getHeight(), focalLengthMm)
                        );
                    }

                    sensorFieldOfView = reconcileFieldOfView(fromIntrinsics, fromGeometry);
                    if (fromIntrinsics != null && fromGeometry != null
                            && sensorFieldOfView != fromIntrinsics) {
                        // Worth a warning of its own: this is the case that used to
                        // space the capture plan past any overlap at all, and it is
                        // the first thing to look for in a run that would not stitch.
                        Log.w(
                                TAG,
                                "Camera " + id + " calibration claims "
                                        + fromIntrinsics.getHorizontalDegrees()
                                        + "° where its sensor geometry says "
                                        + fromGeometry.getHorizontalDegrees()
                                        + "°; trusting the geometry"
                        );
                    }
                    if (array != null) {
                        sensorFieldOfView = croppedToStreamAspect(
                                sensorFieldOfView,
                                array.width() / (float) array.height(),
                                streamAspectRatio
                        );
                    }
                    Log.i(
                            TAG,
                            "Camera " + id + ": intrinsics=" + fromIntrinsics
                                    + " geometry=" + fromGeometry
                                    + " stream=" + streamAspectRatio
                                    + " -> " + sensorFieldOfView
                    );

                    // LENS_DISTORTION's coefficients act on coordinates normalized
                    // by the focal length, so they carry no resolution of their own
                    // — the stitcher rescales them against whatever frame it is
                    // looking at. Only the three radial terms are taken; the two
                    // tangential ones that follow are negligible on a phone lens.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        float[] kappa = characteristics.get(CameraCharacteristics.LENS_DISTORTION);
                        if (kappa != null && kappa.length >= 3) {
                            RadialDistortion distortion = new RadialDistortion(new double[] {
                                    kappa[0],
                                    kappa[1],
                                    kappa[2]
                            });
                            if (distortion.isSignificant()) {
                                radialDistortion = distortion;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // A camera that will not describe itself still previews fine; the
                // markers just ride on the default optics.
                Log.w(TAG, "Falling back to default field of view", e);
            }
        }

        int relativeRotation =
                ((sensorOrientation - rotationToDegrees(displayRotation)) % 360 + 360) % 360;
        FieldOfView fieldOfView = (relativeRotation % 180 == 90)
                ? sensorFieldOfView.transposed()
                : sensorFieldOfView;
        return new SphereOptics(
                fieldOfView,
                radialDistortion,
                // The rotation CameraX records in each still's EXIF — the clockwise turn
                // that makes the sensor's native frame display-upright at the current
                // display rotation. The stitcher's fallback when a frame loses that tag.
                relativeRotation
        );
    }

    /**
     * The focal length of the lens that will actually be streaming.
     *
     * A physical camera lists one. A logical multi-camera lists one per lens it
     * fronts, in no useful order, and the pipeline has to pick the one matching the
     * lens the session bound:
     *
     * - A profile that asked for the widest lens gets the shortest focal length.
     * - Otherwise the main lens is wanted, and it is identified by its diagonal
     *   field of view landing nearest {@link #MAIN_LENS_DIAGONAL_FOV_DEGREES}.
     * - Without a sensor size ({@code sensorDiagonalMm} at zero) there is no field of view
     *   to compare, so the <em>longest</em> focal length wins by default. It is the
     *   asymmetry that decides it: guessing too narrow costs a few extra frames,
     *   guessing too wide spaces the plan past the overlap the stitcher needs and
     *   loses the whole run.
     */
    public static Float selectFocalLengthMm(
            float[] focalLengthsMm,
            float sensorDiagonalMm,
            boolean preferWidest
    ) {
        List<Float> candidates = new ArrayList<Float>();
        if (focalLengthsMm != null) {
            for (int i = 0; i < focalLengthsMm.length; i++) {
                float focal = focalLengthsMm[i];
                if (focal > 0f && !candidates.contains(focal)) {
                    candidates.add(focal);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (preferWidest) {
            return minFloat(candidates);
        }
        if (sensorDiagonalMm <= 0f) {
            return maxFloat(candidates);
        }

        Float best = null;
        float bestScore = 0f;
        for (int i = 0; i < candidates.size(); i++) {
            float focal = candidates.get(i);
            float score = Math.abs(
                    fieldOfViewDegrees(sensorDiagonalMm, focal) - MAIN_LENS_DIAGONAL_FOV_DEGREES
            );
            if (best == null || score < bestScore) {
                best = focal;
                bestScore = score;
            }
        }
        return best;
    }

    /** Corner-to-corner extent of a sensor, or 0 when it will not say. */
    private static float diagonalMm(SizeF size) {
        if (size == null) {
            return 0f;
        }
        float width = size.getWidth();
        float height = size.getHeight();
        if (width <= 0f || height <= 0f) {
            return 0f;
        }
        return (float) Math.sqrt(width * width + height * height);
    }

    /**
     * Settles the two field-of-view estimates against each other.
     *
     * The calibration is preferred when the geometry agrees with it, because it is
     * the more precise of the two. Past {@link #MAX_ESTIMATE_DISAGREEMENT} the pair cannot
     * both be describing this lens, and the geometry is what survives: a focal
     * length in millimetres over a sensor size in millimetres has no crop to be
     * quoted against and therefore no way to be off by a factor.
     *
     * Deliberately free of any framework call, logging included, so the decision
     * can be exercised in a local unit test. The caller says what was discarded.
     */
    public static FieldOfView reconcileFieldOfView(
            FieldOfView fromIntrinsics,
            FieldOfView fromGeometry
    ) {
        if (fromIntrinsics == null) {
            return fromGeometry != null ? fromGeometry : DEFAULT_SENSOR_FIELD_OF_VIEW;
        }
        if (fromGeometry == null) {
            return fromIntrinsics;
        }

        float ratio = fromIntrinsics.getHorizontalDegrees() / fromGeometry.getHorizontalDegrees();
        boolean agrees = ratio <= MAX_ESTIMATE_DISAGREEMENT
                && ratio >= 1f / MAX_ESTIMATE_DISAGREEMENT;
        return agrees ? fromIntrinsics : fromGeometry;
    }

    /**
     * Narrows a sensor-array field of view to the stream being read off it.
     *
     * Camera2 derives a stream of a different shape by cropping the array, not by
     * squeezing it — the focal length is unchanged and the axis that does not fit
     * simply loses its ends. A 16:9 preview off a 4:3 sensor therefore sees a good
     * 25% less across one axis than the array does, and an overlay drawn from the
     * array's angles would place every marker too close to the centre of a frame
     * that will not actually reach it.
     *
     * Both ratios are {@code width / height} in the sensor's own frame. A stream ratio of
     * zero (unknown) leaves the field of view alone.
     */
    public static FieldOfView croppedToStreamAspect(
            FieldOfView sensor,
            float sensorAspectRatio,
            float streamAspectRatio
    ) {
        if (sensorAspectRatio <= 0f || streamAspectRatio <= 0f) {
            return sensor;
        }
        float tanHorizontal = tanHalf(sensor.getHorizontalDegrees());
        float tanVertical = tanHalf(sensor.getVerticalDegrees());
        if (streamAspectRatio >= sensorAspectRatio) {
            // Wider than the array: the full width is kept and the height is cut.
            return new FieldOfView(
                    sensor.getHorizontalDegrees(),
                    degreesFromTanHalf(tanHorizontal / streamAspectRatio)
            );
        }
        return new FieldOfView(
                degreesFromTanHalf(tanVertical * streamAspectRatio),
                sensor.getVerticalDegrees()
        );
    }

    /**
     * Angle subtended by an extent of {@code extent} (pixels or millimetres) behind a lens
     * of {@code focal} in the same units.
     */
    private static float fieldOfViewDegrees(float extent, float focal) {
        return degreesFromTanHalf(extent / (2f * focal));
    }

    private static float tanHalf(float degrees) {
        return (float) Math.tan(Math.toRadians(degrees / 2.0));
    }

    private static float degreesFromTanHalf(float tangent) {
        return coerceIn((float) Math.toDegrees(2.0 * Math.atan(tangent)), 1f, 179f);
    }

    /** {@code Surface.ROTATION_*} as the number of degrees the display is turned by. */
    private static int rotationToDegrees(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private static float minFloat(List<Float> values) {
        float min = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) < min) {
                min = values.get(i);
            }
        }
        return min;
    }

    private static float maxFloat(List<Float> values) {
        float max = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) > max) {
                max = values.get(i);
            }
        }
        return max;
    }

    private static float coerceIn(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
