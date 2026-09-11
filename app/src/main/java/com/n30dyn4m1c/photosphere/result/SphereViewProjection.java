package com.n30dyn4m1c.photosphere.result;

import com.n30dyn4m1c.photosphere.stitching.CameraBasis;
import com.n30dyn4m1c.photosphere.stitching.CameraPose;
import com.n30dyn4m1c.photosphere.stitching.Equirectangular;

/**
 * The projection a 360 viewer uses: a pinhole camera looking at the sphere,
 * sampling the equirectangular image the same way the stitcher painted it.
 *
 * Look angles are <em>viewer</em> conventions, not sensor ones: {@code lookYawDegrees} is
 * the compass bearing being looked at (0° = north, matching the canvas centre)
 * and {@code lookPitchDegrees} is elevation, <b>positive above the horizon</b>. That is
 * the opposite sign of {@link CameraPose#pitchDegrees}, which is what a drag-up
 * "look up" gesture wants.
 */
public final class SphereViewProjection {

    public static final float DEFAULT_FOV_DEGREES = 75f;
    public static final float MIN_FOV_DEGREES = 35f;
    public static final float MAX_FOV_DEGREES = 110f;

    private SphereViewProjection() {
    }

    /**
     * Vertical field of view that keeps square pixels for a view of {@code width} by
     * {@code height} at a horizontal field of view of {@code horizontalFovDegrees}.
     */
    public static float verticalFovDegrees(float horizontalFovDegrees, int width, int height) {
        if (width <= 0 || height <= 0) {
            return horizontalFovDegrees;
        }
        double tanHalfH = Math.tan(Math.toRadians(horizontalFovDegrees / 2.0));
        return (float) Math.toDegrees(2.0 * Math.atan(tanHalfH * height / width));
    }

    /**
     * Camera basis for a look direction. Built through {@link CameraBasis} so a pixel
     * in the viewer is the same world direction the stitcher used for that
     * bearing — the canvas centre is north, and elevation is up.
     */
    public static CameraBasis lookBasis(float lookYawDegrees, float lookPitchDegrees) {
        return CameraBasis.of(
                new CameraPose(
                        wrapDegrees(lookYawDegrees),
                        // Sensor pitch is negative above the horizon; the viewer is not.
                        -lookPitchDegrees,
                        0f
                )
        );
    }

    /**
     * Column-major 3×3 mapping camera (right, up, forward) into the world frame.
     *
     * The fragment shader multiplies this by {@code (ndc.x·tan½fovH, ndc.y·tan½fovV, 1)}
     * and gets the world direction that pixel looks at. Layout matches
     * {@link CameraBasis#toWorld}.
     */
    public static float[] cameraMatrix(CameraBasis basis) {
        return new float[] {
                (float) basis.rightX, (float) basis.rightY, (float) basis.rightZ,
                (float) basis.upX, (float) basis.upY, (float) basis.upZ,
                (float) basis.forwardX, (float) basis.forwardY, (float) basis.forwardZ
        };
    }

    /**
     * World direction the centre of a pixel at NDC ({@code ndcX}, {@code ndcY}) looks at.
     *
     * NDC is OpenGL's: x right, y up, both −1..1 at the view edges.
     */
    public static double[] viewDirection(
            double ndcX,
            double ndcY,
            float lookYawDegrees,
            float lookPitchDegrees,
            double tanHalfFovH,
            double tanHalfFovV
    ) {
        double[] world = lookBasis(lookYawDegrees, lookPitchDegrees)
                .toWorld(ndcX * tanHalfFovH, ndcY * tanHalfFovV, 1.0);
        double length = Math.sqrt(world[0] * world[0] + world[1] * world[1] + world[2] * world[2]);
        return new double[] {world[0] / length, world[1] / length, world[2] / length};
    }

    /**
     * Equirectangular texture coordinates for {@code direction}, or null if that
     * bearing sits outside {@code crop}.
     *
     * {@code u} runs with longitude (0 at −180°, 0.5 at north, 1 at +180°) and {@code v}
     * runs from the north pole down, matching {@link Equirectangular} and every
     * other GPano viewer.
     */
    public static float[] sampleUv(double[] direction, SphereViewCrop crop) {
        double longitude = Equirectangular.longitudeOf(direction[0], direction[1]);
        double latitude = Equirectangular.latitudeOf(direction[2]);
        float fullU = (float) ((longitude + 180.0) / 360.0);
        if (fullU >= 1f) {
            fullU -= 1f;
        }
        if (fullU < 0f) {
            fullU += 1f;
        }
        float fullV = (float) ((90.0 - latitude) / 180.0);
        float u = (fullU - crop.getLeft()) / crop.getWidth();
        float v = (fullV - crop.getTop()) / crop.getHeight();
        boolean uOk = crop.wrapsLongitude() || (u >= 0f && u <= 1f);
        if (!uOk || v < 0f || v > 1f) {
            return null;
        }
        return new float[] {u, v};
    }

    /**
     * How a drag of {@code panX}/{@code panY} pixels, on a view of {@code width}×{@code height} at
     * {@code horizontalFovDegrees}, should change the look.
     *
     * The scene is grabbed: dragging right pulls the sphere right, which turns
     * the look left. Compose's Y is down, so a finger moving down (positive
     * {@code panY}) pulls the scene down and the look goes up.
     */
    public static float[] lookDelta(
            float panX,
            float panY,
            int width,
            int height,
            float horizontalFovDegrees
    ) {
        if (width <= 0 || height <= 0) {
            return new float[] {0f, 0f};
        }
        float fovV = verticalFovDegrees(horizontalFovDegrees, width, height);
        return new float[] {
                -panX / width * horizontalFovDegrees,
                panY / height * fovV
        };
    }

    /**
     * Keeps the look inside {@code crop} so a ring capture cannot be spun into the
     * uncovered poles. When the band is narrower than the vertical field of
     * view the look sits on the band's centre and the shader fills the rest
     * with black.
     */
    public static float clampPitch(
            float lookPitchDegrees,
            float verticalFovDegrees,
            SphereViewCrop crop
    ) {
        float half = verticalFovDegrees / 2f;
        float min = crop.getMinLatitudeDegrees() + half;
        float max = crop.getMaxLatitudeDegrees() - half;
        if (min < max) {
            return coerceIn(lookPitchDegrees, min, max);
        }
        return (crop.getMinLatitudeDegrees() + crop.getMaxLatitudeDegrees()) / 2f;
    }

    public static float wrapDegrees(float degrees) {
        float value = degrees % 360f;
        if (value >= 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }

    private static float coerceIn(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
