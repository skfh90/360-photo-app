package com.n30dyn4m1c.photosphere.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.Arrays;

/**
 * Tracks device attitude from {@link Sensor#TYPE_ROTATION_VECTOR} and publishes it
 * through a {@link Listener} of {@link OrientationData}.
 *
 * <p>The rotation vector is a <em>fused</em> sensor (gyroscope + accelerometer +
 * magnetometer), so it gives an absolute, north-referenced attitude with no
 * drift and no manual filtering — the right input for stitching frames into a
 * sphere.
 *
 * <p>Events are delivered on a private background thread, so a high sampling rate
 * never competes with the UI. {@link #getOrientation} always holds the latest sample:
 * a subscriber arriving mid-capture immediately sees the current attitude rather
 * than waiting for the next event. {@link Listener#onOrientationChanged} is invoked
 * for each new sample.
 *
 * <p>The tracker holds an OS listener registration, so it must be driven by the
 * screen's lifecycle: call {@link #startListening} when the UI becomes visible and
 * {@link #stopListening} when it does not. Leaving the rotation vector registered in
 * the background keeps the gyro powered and drains the battery for nothing.
 *
 * <p>Instances are safe to start and stop from any thread, and can be restarted
 * after a stop.
 */
public class OrientationTracker implements SensorEventListener {

    private static final String TAG = "OrientationTracker";

    /** {@code getRotationMatrixFromVector} works with a 3x3 row-major matrix. */
    public static final int MATRIX_SIZE = 9;

    /** The rotation vector is a quaternion: {@code [x, y, z, w]}. */
    private static final int ROTATION_VECTOR_SIZE = 4;

    private static final String SENSOR_THREAD_NAME = "orientation-sensor";

    /**
     * Receives each published attitude sample, including accuracy-only updates.
     */
    public interface Listener {
        void onOrientationChanged(OrientationData orientation);
    }

    private final int samplingPeriodUs;
    private final OrientationReference reference;

    private final SensorManager sensorManager;
    private final Sensor rotationVectorSensor;

    private volatile OrientationData orientation = new OrientationData();
    private Listener listener;

    /**
     * Current display rotation, one of the {@code Surface.ROTATION_*} constants.
     *
     * <p>The sensor frame is fixed to the <em>chassis</em>, so the angles have to be
     * re-expressed in the frame the user actually sees. Keep this in sync with
     * {@code Display.getRotation()}.
     */
    public volatile int displayRotation = Surface.ROTATION_0;

    /**
     * Scratch buffers. Only ever touched on the sensor thread, which lets a
     * ~50 Hz stream run without allocating per event.
     */
    private final float[] rotationVectorValues = new float[ROTATION_VECTOR_SIZE];
    private final float[] rotationMatrix = new float[MATRIX_SIZE];
    private final float[] displayMatrix = new float[MATRIX_SIZE];
    private final float[] anglesRadians = new float[3];
    private final float[] basisScratch = new float[MATRIX_SIZE];

    private volatile OrientationAccuracy accuracy = OrientationAccuracy.Unknown;

    private final Object lock = new Object();

    /** Both guarded by {@link #lock}. */
    private boolean isListening = false;
    private HandlerThread sensorThread;

    public OrientationTracker(Context context) {
        this(context, SensorManager.SENSOR_DELAY_GAME, OrientationReference.Camera);
    }

    public OrientationTracker(Context context, int samplingPeriodUs) {
        this(context, samplingPeriodUs, OrientationReference.Camera);
    }

    /**
     * @param context any context; only the application context is retained.
     * @param samplingPeriodUs delivery rate hint, as accepted by
     *   {@link SensorManager#registerListener}. {@link SensorManager#SENSOR_DELAY_GAME} (~50 Hz)
     *   is smooth enough for a capture reticle without the cost of
     *   {@link SensorManager#SENSOR_DELAY_FASTEST}.
     * @param reference which physical axis the reported angles describe.
     */
    public OrientationTracker(
            Context context,
            int samplingPeriodUs,
            OrientationReference reference) {
        this.samplingPeriodUs = samplingPeriodUs;
        this.reference = reference != null ? reference : OrientationReference.Camera;
        this.sensorManager = context.getApplicationContext().getSystemService(SensorManager.class);
        this.rotationVectorSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                : null;
    }

    /**
     * False on devices without a fused rotation vector (no gyroscope, or a
     * stripped sensor HAL). Callers should degrade gracefully rather than
     * assume orientation is available.
     */
    public boolean isSensorAvailable() {
        return rotationVectorSensor != null;
    }

    /** Latest attitude. */
    public OrientationData getOrientation() {
        return orientation;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Listener getListener() {
        return listener;
    }

    public int getDisplayRotation() {
        return displayRotation;
    }

    public void setDisplayRotation(int displayRotation) {
        this.displayRotation = displayRotation;
    }

    /**
     * Subscribes to the rotation vector. Idempotent.
     *
     * @return true if the tracker is listening when this returns; false if the
     *   device has no rotation vector sensor or the framework refused the
     *   registration.
     */
    public boolean startListening() {
        SensorManager manager = sensorManager;
        Sensor sensor = rotationVectorSensor;
        if (manager == null || sensor == null) {
            return false;
        }

        synchronized (lock) {
            if (isListening) {
                return true;
            }

            // A dedicated thread keeps sensor delivery — and the matrix maths —
            // off the main thread, so the sampling rate cannot stutter the UI.
            HandlerThread thread = new HandlerThread(SENSOR_THREAD_NAME);
            thread.start();
            boolean registered = manager.registerListener(
                    this,
                    sensor,
                    samplingPeriodUs,
                    new Handler(thread.getLooper()));

            if (registered) {
                sensorThread = thread;
                isListening = true;
            } else {
                Log.w(TAG, "Rotation vector registration refused");
                thread.quitSafely();
            }
            return registered;
        }
    }

    /**
     * Unsubscribes and releases the sensor thread. Idempotent.
     *
     * <p>The last {@link OrientationData} stays published so the UI keeps rendering the
     * final pose instead of snapping back to zero.
     */
    public void stopListening() {
        synchronized (lock) {
            if (!isListening) {
                return;
            }

            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
            isListening = false;
            // quitSafely, not quit: lets an event already queued finish its
            // matrix conversion instead of tearing the looper out from under it.
            if (sensorThread != null) {
                sensorThread.quitSafely();
                sensorThread = null;
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }

        float[] raw = event.values;
        // A rotation vector is three elements plus an optional scalar; anything
        // shorter is not one, and a HAL that hands over a truncated event would
        // otherwise take the matrix helper into an array bound.
        if (raw.length < 3) {
            return;
        }

        // Some vendors append a fifth element (estimated heading accuracy); only
        // the leading quaternion is the rotation itself.
        float[] values;
        if (raw.length > ROTATION_VECTOR_SIZE) {
            System.arraycopy(raw, 0, rotationVectorValues, 0, ROTATION_VECTOR_SIZE);
            values = rotationVectorValues;
        } else {
            values = raw;
        }

        // Quaternion -> rotation matrix mapping device coordinates to the world
        // frame (X east, Y north, Z up). A malformed sample is worth skipping,
        // not crashing the sensor thread over.
        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Rejected a malformed rotation vector", e);
            return;
        }

        // The camera's axes in the world frame, published alongside the angles:
        // the stitcher consumes the pose as a rotation, and the dwell averaging
        // in OrientationMean needs the matrix because the angles alone collapse
        // at the zenith.
        float[] basis = cameraBasisMatrix(rotationMatrix, displayRotation, basisScratch);

        float[] angles;
        if (reference == OrientationReference.Screen) {
            angles = screenAnglesDegrees(rotationMatrix);
            if (angles == null) {
                return;
            }
        } else {
            angles = anglesFromCameraBasis(basis, anglesRadians);
        }

        // getOrientation's screen frame reports azimuth/pitch/roll about the
        // -Z/X/Y axes; the camera frame is read straight off the matrix instead.
        // Yaw and roll come from atan2 and are normalised defensively; pitch
        // comes from an asin and is already bounded to ±90°.
        publish(new OrientationData(
                angles[0],
                angles[1],
                angles[2],
                accuracyOf(event),
                event.timestamp,
                // A copy: the scratch buffer is reused by the next event.
                Arrays.copyOf(basis, basis.length)));
    }

    /**
     * The accuracy to publish with a sample.
     *
     * <p>{@link SensorEvent#accuracy} carries the current status on every event, while
     * {@link #onAccuracyChanged} only fires on a <em>change</em> — and a good many HALs never
     * fire it at all, including the first time. Reading the event is what stops
     * a device that simply never calls back from sitting at
     * {@link OrientationAccuracy#Unknown} forever, which the capture loop would see as
     * a sensor that never became trustworthy.
     *
     * <p>The callback still wins when it has spoken, because a vendor that bothers
     * to send it is the better authority on its own fusion. And the event can
     * only ever <em>raise</em> the reading: a HAL that never calls back and leaves the
     * field at its zero default would otherwise look identical to one declaring
     * its own output unreliable, and the capture loop would refuse to shoot on a
     * phone whose sensor is working perfectly well.
     */
    private OrientationAccuracy accuracyOf(SensorEvent event) {
        OrientationAccuracy reported = accuracy;
        if (reported != OrientationAccuracy.Unknown) {
            return reported;
        }
        OrientationAccuracy fromEvent = OrientationAccuracy.fromSensorAccuracy(event.accuracy);
        return fromEvent == OrientationAccuracy.Unreliable ? reported : fromEvent;
    }

    /**
     * Rotates {@code source} out of the chassis frame and into the frame the angles
     * are reported in, writing into one of the scratch buffers.
     *
     * <p>Only the {@link OrientationReference#Screen} path uses this: the display remap
     * plus a plain {@code getOrientation} <em>is</em> Android's own convention. The camera
     * reference is read straight off the device→world matrix instead — see
     * {@link #cameraAnglesDegrees}, which is the inverse of the axis construction the
     * capture and stitching code build their bases from.
     *
     * <p>The display remap rotates the chassis frame so the angles describe the
     * display the user is actually looking at: portrait and landscape put the
     * chassis X/Y axes in different places on screen, and without it, tilting a
     * landscape phone "up" would show up as roll.
     *
     * @return the matrix to read angles from, or null if a remap was rejected —
     *   which should not happen for these fixed axis pairs, but a bad matrix is
     *   worse than a skipped frame.
     */
    private float[] screenAnglesDegrees(float[] source) {
        DisplayAxes axes = DisplayAxes.forDisplayRotation(displayRotation);
        if (!SensorManager.remapCoordinateSystem(source, axes.axisX, axes.axisY, displayMatrix)) {
            Log.w(TAG, "Display remap rejected for rotation " + displayRotation);
            return null;
        }
        SensorManager.getOrientation(displayMatrix, anglesRadians);
        anglesRadians[0] = normalizeDegrees(toDegrees(anglesRadians[0]));
        anglesRadians[1] = toDegrees(anglesRadians[1]);
        anglesRadians[2] = normalizeDegrees(toDegrees(anglesRadians[2]));
        return anglesRadians;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracyValue) {
        if (sensor == null || sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }
        OrientationAccuracy mapped = OrientationAccuracy.fromSensorAccuracy(accuracyValue);
        this.accuracy = mapped;
        // Surface a calibration warning immediately rather than at the next event.
        publish(orientation.withAccuracy(mapped));
    }

    private void publish(OrientationData data) {
        this.orientation = data;
        Listener callback = listener;
        if (callback != null) {
            callback.onOrientationChanged(data);
        }
    }

    private static float toDegrees(float radians) {
        return (float) Math.toDegrees(radians);
    }

    /** Wraps {@code degrees} into {@code [-180, 180)}, so yaw crossing north stays continuous. */
    public static float normalizeDegrees(float degrees) {
        float value = degrees % 360f;
        if (value >= 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }

    /**
     * The rear camera's basis as a rotation matrix, read straight off the
     * device→world matrix {@code deviceToWorld}.
     *
     * <p>The columns are the camera's axes expressed in the world frame —
     * {@code [right, −up, forward]} — laid out exactly as {@code CameraBasis.toRotationMatrix}
     * (stitching) arranges them, so a basis written here can be handed to
     * {@code CameraBasis.fromRotationMatrix} without a transpose. The camera's own
     * (right, up, forward) triple is left-handed, so the up column is mirrored to
     * make the matrix a proper rotation: the dwell is averaged as rotations and
     * the refinement pipeline assumes proper ones, which an unmirrored column
     * would silently corrupt (a 90° error through the quaternion conversion).
     * The matrix is row-major with the device axes as its columns: column c is
     * the world coordinates of device axis c, so the camera looks along -Z.
     *
     * <p>{@code displayRotation} decides which device axis is the image's right/up, exactly
     * as in {@link #cameraAnglesDegrees} — a phone turned landscape keeps reporting where
     * it is pointing. {@link #anglesFromCameraBasis} is the inverse of the axis
     * construction {@code SphereProjection} and {@code CameraBasis.of} use, so both halves of
     * the pipeline describe the same frame.
     *
     * <p>The result is written into {@code out} (a scratch buffer) to keep a 50 Hz stream
     * allocation-free.
     */
    public static float[] cameraBasisMatrix(
            float[] deviceToWorld,
            int displayRotation,
            float[] out) {
        float fx = -deviceToWorld[2];
        float fy = -deviceToWorld[5];
        float fz = -deviceToWorld[8];

        // Image right/up in world, mapped from whichever device axes the display
        // rotation points to the right and the top of the screen.
        float rx;
        float ry;
        float rz;
        float ux;
        float uy;
        float uz;
        switch (displayRotation) {
            case Surface.ROTATION_90:
                rx = deviceToWorld[1];
                ry = deviceToWorld[4];
                rz = deviceToWorld[7];
                ux = -deviceToWorld[0];
                uy = -deviceToWorld[3];
                uz = -deviceToWorld[6];
                break;
            case Surface.ROTATION_180:
                rx = -deviceToWorld[0];
                ry = -deviceToWorld[3];
                rz = -deviceToWorld[6];
                ux = -deviceToWorld[1];
                uy = -deviceToWorld[4];
                uz = -deviceToWorld[7];
                break;
            case Surface.ROTATION_270:
                rx = -deviceToWorld[1];
                ry = -deviceToWorld[4];
                rz = -deviceToWorld[7];
                ux = deviceToWorld[0];
                uy = deviceToWorld[3];
                uz = deviceToWorld[6];
                break;
            default:
                rx = deviceToWorld[0];
                ry = deviceToWorld[3];
                rz = deviceToWorld[6];
                ux = deviceToWorld[1];
                uy = deviceToWorld[4];
                uz = deviceToWorld[7];
                break;
        }

        out[0] = rx;
        out[1] = -ux;
        out[2] = fx;
        out[3] = ry;
        out[4] = -uy;
        out[5] = fy;
        out[6] = rz;
        out[7] = -uz;
        out[8] = fz;
        return out;
    }

    /**
     * The rear camera's yaw/pitch/roll from a camera basis matrix in the
     * {@link #cameraBasisMatrix} layout, in this app's conventions.
     *
     * <p>The angles are read from the geometry directly rather than through
     * {@code getOrientation}'s azimuth/pitch/roll (which describe a flat screen, not a
     * camera, and are what a {@code remapCoordinateSystem} shortcut gets wrong):
     *
     * <ul>
     *   <li><b>forward</b> is where the lens points. Yaw is its compass bearing, elevation
     *       its height above the horizon (pitch is negated to match the app's
     *       "negative is up" convention).
     *   <li><b>up</b> is the top of the captured, display-upright image. Roll is the angle
     *       between that up and the unrolled-up for the same yaw/elevation.
     * </ul>
     *
     * <p>This is the <em>inverse</em> of the axis construction {@code CameraBasis.of} performs:
     * feeding it the basis that class builds from a pose must hand the same angles
     * back, or every frame lands on the sphere rotated — which is exactly the kind
     * of "aligned but all at odd angles" failure that no amount of stitching can
     * rescue. The result is written into {@code out} (a scratch buffer).
     */
    public static float[] anglesFromCameraBasis(float[] basis, float[] out) {
        double fx = basis[2];
        double fy = basis[5];
        double fz = basis[8];

        float yaw = (float) Math.toDegrees(Math.atan2(fx, fy));
        double elevation = Math.asin(coerceIn(fz, -1.0, 1.0));
        float pitch = (float) -Math.toDegrees(elevation);

        // The unrolled pair `CameraBasis.of` would build for this yaw/elevation,
        // measured against the actual up to recover the roll (see its `toPose`).
        double yawRad = Math.toRadians(yaw);
        double sinYaw = Math.sin(yawRad);
        double cosYaw = Math.cos(yawRad);
        double sinElevation = Math.sin(elevation);
        double cosElevation = Math.cos(elevation);
        double up0X = -sinYaw * sinElevation;
        double up0Y = -cosYaw * sinElevation;
        double up0Z = cosElevation;
        double rx = basis[0];
        double ry = basis[3];
        double rz = basis[6];
        // The stored matrix mirrors the up axis (`[right, −up, forward]` columns,
        // so rotation algebra sees a proper rotation); un-mirror it back to the
        // camera's own up before measuring the roll against it.
        double ux = -basis[1];
        double uy = -basis[4];
        double uz = -basis[7];
        double sinRoll = -(rx * up0X + ry * up0Y + rz * up0Z);
        double cosRoll = ux * up0X + uy * up0Y + uz * up0Z;

        out[0] = normalizeDegrees(yaw);
        out[1] = pitch;
        out[2] = normalizeDegrees((float) Math.toDegrees(Math.atan2(sinRoll, cosRoll)));
        return out;
    }

    /**
     * The rear camera's yaw/pitch/roll in this app's conventions, read straight off
     * the device→world rotation matrix {@code deviceToWorld}.
     *
     * <p>The composition of {@link #cameraBasisMatrix} and {@link #anglesFromCameraBasis}; kept
     * whole because the round-trip contract with {@code CameraBasis.of} is tested
     * against it directly. The result is written into {@code out} (a scratch buffer).
     */
    public static float[] cameraAnglesDegrees(
            float[] deviceToWorld,
            int displayRotation,
            float[] out) {
        float[] basis = new float[MATRIX_SIZE];
        cameraBasisMatrix(deviceToWorld, displayRotation, basis);
        return anglesFromCameraBasis(basis, out);
    }

    private static double coerceIn(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
