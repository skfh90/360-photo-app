package com.n30dyn4m1c.photosphere.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.util.Size;

import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;

/**
 * How one sphere is captured, decided once per device.
 *
 * A photosphere's seams are exposure and colour seams: if every frame re-runs
 * the camera's auto-exposure and auto-white-balance on its own, three frames of
 * a room and one of a window come back in three different brightnesses and
 * colour temperatures, and no amount of blending can hide that. So the session
 * holds them all — AE lock pins the ISO and shutter speed the HAL converged on
 * when the preview started, AWB lock pins the colour temperature, and focus is
 * locked on the scene (by tap, or the first scene's centre) so nothing
 * re-focuses mid-sweep. This is the same model GCam's Photosphere uses: lock
 * exposure and focus, then sweep.
 *
 * The one rule the locks observe: they are applied only <em>after</em> the 3A has
 * converged on the scene the phone is pointed at. Locking from the stream's
 * first frame pins the exposure at the HAL's stream-start defaults — a short
 * exposure and low ISO chosen for a bright scene — which leaves a dark scene
 * permanently black: the viewfinder never brightens and every frame records a
 * black image. The session starts unlocked, waits for AE/AWB to converge (the
 * exposure ramps up over the first moments in low light), and locks to the
 * values they settled on. See {@link #applyStillImageOptions} and
 * PhotoSphereCameraScreen's convergence callback.
 *
 * The one thing that varies per device is <em>how</em> focus is fixed:
 *
 * - {@link FocusMode#FOCUS_POINT} — the default for any lens that can focus. The
 *   session runs {@code AF_MODE_AUTO}, and the user taps the viewfinder to aim focus
 *   at a scene region; the sweep converges there and the lens holds that
 *   distance for the rest of the session, exactly like a regular camera's
 *   tap-to-focus lock. Until the first tap the lens holds the centre of the
 *   first scene it saw. This is what keeps frames sharp: focus is <em>locked on
 *   what the scene actually is</em> rather than parked at infinity, which reads as
 *   blur on any scene with something nearer than the horizon.
 * - {@link FocusMode#FIXED_FOCUS} — the lens physically has no focus mechanism
 *   ({@code LENS_INFO_MINIMUM_FOCUS_DISTANCE} is 0); {@code AF_MODE_OFF} is all there is.
 *
 * Still-image size selection lives on the CameraX use-case builders (CameraX 1.1
 * {@code setTargetAspectRatio} / {@code setTargetResolution}), not here. This class only
 * sets Camera2 {@link CaptureRequest} keys through {@link Camera2Interop.Extender},
 * which exists in CameraX 1.1.0.
 */
public final class SphereCaptureProfile {

    /** Smallest sensor a "wide" lens may have before it is a depth or macro toy. */
    private static final long MIN_WIDE_SENSOR_PIXELS = 6_000_000L;

    private final FocusMode focusMode;
    private final boolean aeLockSupported;
    private final boolean awbLockSupported;
    private final boolean opticalStabilizationSupported;

    public SphereCaptureProfile(
            FocusMode focusMode,
            boolean aeLockSupported,
            boolean awbLockSupported,
            boolean opticalStabilizationSupported
    ) {
        this.focusMode = focusMode;
        this.aeLockSupported = aeLockSupported;
        this.awbLockSupported = awbLockSupported;
        this.opticalStabilizationSupported = opticalStabilizationSupported;
    }

    public FocusMode getFocusMode() {
        return focusMode;
    }

    public boolean getAeLockSupported() {
        return aeLockSupported;
    }

    public boolean getAwbLockSupported() {
        return awbLockSupported;
    }

    public boolean getOpticalStabilizationSupported() {
        return opticalStabilizationSupported;
    }

    /**
     * The focus strategy for a lens.
     *
     * {@code minimumFocusDistance} is {@code LENS_INFO_MINIMUM_FOCUS_DISTANCE}: 0 means the
     * lens is fixed-focus and has nothing to lock; anything else means it can
     * focus, and tap-to-focus is the way to lock it on the scene.
     */
    public static FocusMode resolveFocusMode(Float minimumFocusDistance) {
        if (minimumFocusDistance != null && minimumFocusDistance <= 0f) {
            return FocusMode.FIXED_FOCUS;
        }
        return FocusMode.FOCUS_POINT;
    }

    /**
     * Whether AE/AWB locks are worth setting.
     *
     * The HAL reports support via {@code CONTROL_AE_LOCK_AVAILABLE}/{@code CONTROL_AWB_LOCK_AVAILABLE}.
     * Missing means the capability is unknown, not absent — every shipping camera
     * supports these, so an unknown characteristic is treated as supported (an
     * unsupported key is simply ignored by the HAL).
     */
    public static boolean resolveLockSupport(Boolean available) {
        return available == null || available;
    }

    /** Whether the lens offers hardware optical stabilisation. */
    public static boolean resolveOpticalStabilization(int[] stabilizationModes) {
        if (stabilizationModes == null) {
            return false;
        }
        for (int i = 0; i < stabilizationModes.length; i++) {
            if (stabilizationModes[i] == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                return true;
            }
        }
        return false;
    }

    /**
     * The capture profile for this device's rear camera.
     *
     * Deliberately does not throw: a camera that will not describe itself gets the
     * most conservative profile (locks everything, tap-to-focus on the first scene)
     * rather than failing capture.
     */
    public static SphereCaptureProfile resolveSphereCaptureProfile(
            Context context,
            SphereDeviceProfile profile
    ) {
        CameraCharacteristics characteristics = null;
        try {
            characteristics = backCameraCharacteristics(context, profile);
        } catch (Exception ignored) {
        }
        Float minimumFocusDistance = characteristics == null
                ? null
                : characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Boolean aeLock = characteristics == null
                ? null
                : characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
        Boolean awbLock = characteristics == null
                ? null
                : characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
        int[] oisModes = characteristics == null
                ? null
                : characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        return new SphereCaptureProfile(
                resolveFocusMode(minimumFocusDistance),
                resolveLockSupport(aeLock),
                resolveLockSupport(awbLock),
                resolveOpticalStabilization(oisModes)
        );
    }

    /**
     * The back camera that captures the widest field of view, or null if none can
     * be found.
     *
     * A photosphere is captured faster the wider each frame is, so the widest
     * back-facing lens wins — on a Galaxy S23 that is the 12 MP ultrawide rather
     * than the 50 MP main, which roughly halves the number of frames a sphere
     * needs. Logical multi-cameras are skipped (they report their widest physical
     * lens but are not individually bindable), and depth/macro sensors are
     * excluded by requiring a real sensor behind the lens.
     */
    public static String widestBackCameraId(Context context) {
        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager == null) {
            return null;
        }
        String bestId = null;
        float bestFocal = Float.MAX_VALUE;
        String[] ids;
        try {
            ids = manager.getCameraIdList();
        } catch (Exception e) {
            return null;
        }
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            CameraCharacteristics characteristics;
            try {
                characteristics = manager.getCameraCharacteristics(id);
            } catch (Exception e) {
                continue;
            }
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && !characteristics.getPhysicalCameraIds().isEmpty()) {
                continue;
            }
            float[] focals = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            Float focal = minOrNull(focals);
            if (focal == null) {
                continue;
            }
            Size array = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (array == null) {
                continue;
            }
            if ((long) array.getWidth() * array.getHeight() < MIN_WIDE_SENSOR_PIXELS) {
                continue;
            }
            if (focal < bestFocal) {
                bestFocal = focal;
                bestId = id;
            }
        }
        return bestId;
    }

    /** The ID of the default back camera (the main lens), or null if none. */
    public static String defaultBackCameraId(Context context) {
        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager == null) {
            return null;
        }
        String[] ids;
        try {
            ids = manager.getCameraIdList();
        } catch (Exception e) {
            return null;
        }
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            try {
                Integer facing = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * The back camera to capture with, honouring the device profile's preference.
     *
     * Sharpness-first profiles pick the main lens; speed-first profiles pick the
     * widest one. Either way, a missing or unbindable preference falls back to the
     * main camera.
     */
    public static String captureBackCameraId(Context context, SphereDeviceProfile profile) {
        if (profile.getPreferWidestCamera()) {
            String widest = widestBackCameraId(context);
            return widest != null ? widest : defaultBackCameraId(context);
        }
        return defaultBackCameraId(context);
    }

    /** Characteristics of the chosen rear camera, or null if none can be queried. */
    private static CameraCharacteristics backCameraCharacteristics(
            Context context,
            SphereDeviceProfile profile
    ) {
        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager == null) {
            return null;
        }
        String id = captureBackCameraId(context, profile);
        if (id == null) {
            return null;
        }
        try {
            return manager.getCameraCharacteristics(id);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Applies the focus strategy to a use case's requests.
     *
     * This is deliberately applied to <em>both</em> the Preview and ImageCapture builders:
     * the preview is the session's repeating request and the stills must agree with
     * it. AE and AWB locks are <em>not</em> set here — see {@link #applyStillImageOptions} for
     * where they live and why they are deferred.
     */
    @SuppressLint("UnsafeOptInUsageError")
    @ExperimentalCamera2Interop
    public static void applySphereCaptureOptions(
            Camera2Interop.Extender<?> extender,
            SphereCaptureProfile profile
    ) {
        switch (profile.focusMode) {
            case FIXED_FOCUS:
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                );
                break;
            case FOCUS_POINT:
                // AUTO + a tap trigger is how a regular camera locks focus on an
                // area: the sweep converges and the lens holds that distance until
                // the next trigger, so no frame re-focuses mid-sweep.
                extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO
                );
                break;
            default:
                break;
        }
    }

    /**
     * Applies options that belong on the still-image requests only.
     *
     * OIS is the lens steadying the shot; it belongs on the capture, not the
     * preview. AE and AWB locks belong here too, but only as a safety net: the
     * session locks them on the repeating request once the 3A has converged, and a
     * still fired mid-convergence must not re-meter on its own — the lock pins it
     * to whatever the converging repeating request has reached, so a premature
     * capture cannot walk away from the exposure the viewfinder is showing. The
     * initial convergence itself is left to run, because locking the very first
     * request freezes the exposure at the HAL's stream-start defaults and a dark
     * scene would never brighten.
     */
    @SuppressLint("UnsafeOptInUsageError")
    @ExperimentalCamera2Interop
    public static void applyStillImageOptions(
            Camera2Interop.Extender<?> extender,
            SphereCaptureProfile profile
    ) {
        // AE lock holds the ISO and shutter speed, AWB lock holds the colour
        // temperature; together they keep every frame of the sphere identical in
        // brightness and tint.
        if (profile.aeLockSupported) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true);
        }
        if (profile.awbLockSupported) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true);
        }
        if (profile.opticalStabilizationSupported) {
            extender.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            );
        }
    }

    private static Float minOrNull(float[] values) {
        if (values == null || values.length == 0) {
            return null;
        }
        float min = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i] < min) {
                min = values[i];
            }
        }
        return min;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SphereCaptureProfile)) return false;
        SphereCaptureProfile that = (SphereCaptureProfile) o;
        return aeLockSupported == that.aeLockSupported
                && awbLockSupported == that.awbLockSupported
                && opticalStabilizationSupported == that.opticalStabilizationSupported
                && focusMode == that.focusMode;
    }

    @Override
    public int hashCode() {
        int result = focusMode != null ? focusMode.hashCode() : 0;
        result = 31 * result + (aeLockSupported ? 1 : 0);
        result = 31 * result + (awbLockSupported ? 1 : 0);
        result = 31 * result + (opticalStabilizationSupported ? 1 : 0);
        return result;
    }
}
