package com.n30dyn4m1c.photosphere.sensor;

import com.n30dyn4m1c.photosphere.stitching.RotationMath;

import java.util.ArrayList;
import java.util.List;

/**
 * The mean of a run of orientation samples, for a steadier pose at the shutter.
 *
 * The alignment gate only fires after the aim has held on a target for a few
 * hundred milliseconds, which means the pose that finally gets stamped onto the
 * frame is the <em>last</em> sensor sample of a deliberately still dwell. Averaging the
 * dwell's samples instead takes out the sample-to-sample sensor jitter, which is
 * exactly the noise that shows up as softness in the stitch overlaps. The mean
 * is equal-weight because the whole window is a single deliberate stop.
 *
 * When every sample carries its full camera basis ({@link OrientationData#getCameraBasis()}),
 * the mean is taken over the <b>rotations</b> themselves — a quaternion mean — and
 * the reported angles are re-derived from the result. Averaging the Euler
 * components instead would smear a pose aimed near the zenith: there yaw and
 * roll collapse into each other, each sample splits them differently, and the
 * averaged components land the frame rotated about its own axis. Samples
 * without a basis fall back to the component mean, which is exact away from
 * the zenith.
 */
public final class OrientationMean {

    private OrientationMean() {
    }

    public static OrientationData meanOrientation(List<OrientationData> samples) {
        List<OrientationData> valid = new ArrayList<OrientationData>();
        for (int i = 0; i < samples.size(); i++) {
            OrientationData sample = samples.get(i);
            if (sample.getTimestampNanos() > 0L) {
                valid.add(sample);
            }
        }
        OrientationData reference;
        if (!valid.isEmpty()) {
            reference = valid.get(valid.size() - 1);
        } else if (!samples.isEmpty()) {
            reference = samples.get(samples.size() - 1);
        } else {
            reference = new OrientationData();
        }
        if (valid.isEmpty()) {
            return reference;
        }

        // Every sample of the dwell carries the camera basis: average the
        // rotations and re-derive the angles from the result, so the pose the
        // stitcher reconstructs and the pose reported here agree exactly.
        List<float[]> bases = new ArrayList<float[]>();
        for (int i = 0; i < valid.size(); i++) {
            float[] basis = valid.get(i).getCameraBasis();
            if (basis != null) {
                bases.add(basis);
            }
        }
        if (bases.size() == valid.size()) {
            float[] basis = meanCameraBasis(bases);
            float[] angles = anglesFromCameraBasis(basis, new float[3]);
            return new OrientationData(
                    angles[0],
                    angles[1],
                    angles[2],
                    reference.getAccuracy(),
                    reference.getTimestampNanos(),
                    basis
            );
        }

        List<Float> yaws = new ArrayList<Float>(valid.size());
        List<Float> pitches = new ArrayList<Float>(valid.size());
        List<Float> rolls = new ArrayList<Float>(valid.size());
        for (int i = 0; i < valid.size(); i++) {
            OrientationData sample = valid.get(i);
            yaws.add(sample.getYawDegrees());
            pitches.add(sample.getPitchDegrees());
            rolls.add(sample.getRollDegrees());
        }
        return new OrientationData(
                normalizeDegrees(meanAngleDegrees(yaws)),
                meanAngleDegrees(pitches),
                normalizeDegrees(meanAngleDegrees(rolls)),
                reference.getAccuracy(),
                reference.getTimestampNanos(),
                null
        );
    }

    /**
     * The mean rotation of a dwell's camera bases, in the {@code cameraBasisMatrix}
     * layout.
     *
     * The bases are close — the gate only fires on a deliberate stop — so the
     * linear quaternion mean in {@link RotationMath#weightedMeanRotation} (signs aligned
     * to the first sample, then a renormalised sum) is the Karcher mean to first
     * order: exactly what a few degrees of sensor jitter need, and well-defined
     * where the Euler components are not.
     */
    private static float[] meanCameraBasis(List<float[]> bases) {
        List<double[]> matrices = new ArrayList<double[]>(bases.size());
        List<Double> weights = new ArrayList<Double>(bases.size());
        for (int i = 0; i < bases.size(); i++) {
            float[] basis = bases.get(i);
            double[] matrix = new double[9];
            for (int j = 0; j < 9; j++) {
                matrix[j] = basis[j];
            }
            matrices.add(matrix);
            weights.add(1.0);
        }
        double[] mean = RotationMath.weightedMeanRotation(matrices, weights);
        float[] result = new float[9];
        for (int i = 0; i < 9; i++) {
            result[i] = (float) mean[i];
        }
        return result;
    }

    /**
     * The arithmetic mean of angles, unwrapped so a crossing of ±180° does not smear
     * -179° and +179° into 0°.
     *
     * Angles are measured as deviations from the first value, which works for any
     * range — yaw and roll wrap at ±180°, pitch is bounded at ±90° and never wraps.
     */
    public static float meanAngleDegrees(List<Float> values) {
        if (values.isEmpty()) {
            return 0f;
        }
        float reference = values.get(0);
        double sum = 0.0;
        for (int i = 0; i < values.size(); i++) {
            float deviation = values.get(i) - reference;
            while (deviation > 180f) {
                deviation -= 360f;
            }
            while (deviation < -180f) {
                deviation += 360f;
            }
            sum += deviation;
        }
        return reference + (float) (sum / values.size());
    }

    /**
     * The rear camera's yaw/pitch/roll from a camera basis matrix in the
     * cameraBasisMatrix layout, in this app's conventions.
     */
    private static float[] anglesFromCameraBasis(float[] basis, float[] out) {
        double fx = basis[2];
        double fy = basis[5];
        double fz = basis[8];

        float yaw = (float) Math.toDegrees(Math.atan2(fx, fy));
        double elevation = Math.asin(coerceIn(fz, -1.0, 1.0));
        float pitch = (float) -Math.toDegrees(elevation);

        // The unrolled pair CameraBasis.of would build for this yaw/elevation,
        // measured against the actual up to recover the roll (see its toPose).
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
        // The stored matrix mirrors the up axis ([right, −up, forward] columns,
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

    private static double coerceIn(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Wraps {@code degrees} into {@code [-180, 180)}, so yaw crossing north stays continuous. */
    private static float normalizeDegrees(float degrees) {
        float value = degrees % 360f;
        if (value >= 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }
}
