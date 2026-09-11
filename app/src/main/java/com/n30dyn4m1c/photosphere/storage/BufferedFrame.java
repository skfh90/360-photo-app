package com.n30dyn4m1c.photosphere.storage;

import com.n30dyn4m1c.photosphere.sensor.OrientationData;

import java.io.File;
import java.util.Arrays;

/**
 * One buffered frame: where the pixels live, and where the camera was pointing.
 *
 * <p>The angles are copied from {@link OrientationData} and keep its conventions —
 * {@link #yawDegrees} is the camera's compass bearing and {@link #pitchDegrees} is <b>negative
 * above the horizon</b>. They are held in memory as well as stamped into the
 * file's EXIF so the stitcher can order and pre-align frames without paying to
 * re-read forty-odd JPEG headers.
 */
public final class BufferedFrame {

    /** Position in the capture plan; also the file's sequence number. */
    public final int index;
    /** Full-resolution JPEG in the session's cache directory. */
    public final File file;
    /** Compass bearing of the camera at capture, -180°..180°. */
    public final float yawDegrees;
    /** Vertical aim at capture, -90°..90°, negative above the horizon. */
    public final float pitchDegrees;
    /** Side tilt at capture, -180°..180°. */
    public final float rollDegrees;
    /**
     * The camera's basis as a rotation matrix — the {@code [right, −up, forward]}
     * columns in the world frame, in the {@code CameraBasis.toRotationMatrix} layout.
     * Present for every frame the tracker captured, and what the stitcher
     * reconstructs the pose from: it carries the orientation exactly where the
     * Euler components collapse (a frame aimed at the zenith). Null for frames
     * recorded without a sensor sample, e.g. in tests.
     */
    public final float[] cameraBasis;
    /** Wall clock at capture, for ordering frames of equal index. */
    public final long capturedAtMillis;

    public BufferedFrame(
            int index,
            File file,
            float yawDegrees,
            float pitchDegrees,
            float rollDegrees,
            long capturedAtMillis) {
        this(index, file, yawDegrees, pitchDegrees, rollDegrees, null, capturedAtMillis);
    }

    public BufferedFrame(
            int index,
            File file,
            float yawDegrees,
            float pitchDegrees,
            float rollDegrees,
            float[] cameraBasis,
            long capturedAtMillis) {
        this.index = index;
        this.file = file;
        this.yawDegrees = yawDegrees;
        this.pitchDegrees = pitchDegrees;
        this.rollDegrees = rollDegrees;
        this.cameraBasis = cameraBasis;
        this.capturedAtMillis = capturedAtMillis;
    }

    public int getIndex() {
        return index;
    }

    public File getFile() {
        return file;
    }

    public float getYawDegrees() {
        return yawDegrees;
    }

    public float getPitchDegrees() {
        return pitchDegrees;
    }

    public float getRollDegrees() {
        return rollDegrees;
    }

    public float[] getCameraBasis() {
        return cameraBasis;
    }

    public long getCapturedAtMillis() {
        return capturedAtMillis;
    }

    /** Height above the horizon, positive up — the sign most people expect. */
    public float getElevationDegrees() {
        return -pitchDegrees;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BufferedFrame)) {
            return false;
        }
        BufferedFrame that = (BufferedFrame) o;
        if (index != that.index) {
            return false;
        }
        if (Float.compare(that.yawDegrees, yawDegrees) != 0) {
            return false;
        }
        if (Float.compare(that.pitchDegrees, pitchDegrees) != 0) {
            return false;
        }
        if (Float.compare(that.rollDegrees, rollDegrees) != 0) {
            return false;
        }
        if (capturedAtMillis != that.capturedAtMillis) {
            return false;
        }
        if (file != null ? !file.equals(that.file) : that.file != null) {
            return false;
        }
        return Arrays.equals(cameraBasis, that.cameraBasis);
    }

    @Override
    public int hashCode() {
        int result = index;
        result = 31 * result + (file != null ? file.hashCode() : 0);
        result = 31 * result + Float.floatToIntBits(yawDegrees);
        result = 31 * result + Float.floatToIntBits(pitchDegrees);
        result = 31 * result + Float.floatToIntBits(rollDegrees);
        result = 31 * result + Arrays.hashCode(cameraBasis);
        result = 31 * result + (int) (capturedAtMillis ^ (capturedAtMillis >>> 32));
        return result;
    }
}
