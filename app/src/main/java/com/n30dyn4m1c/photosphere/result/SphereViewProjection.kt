package com.n30dyn4m1c.photosphere.result

import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.stitching.CameraBasis
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.Equirectangular
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the stitched image sits on the full 360°×180° sphere.
 *
 * The JPEG the stitcher writes is the *cropped* region: a full sphere covers
 * the whole canvas, a horizon ring is a horizontal band. The viewer maps a
 * look direction onto that region the same way GPano metadata does, and paints
 * black where the run never reached.
 *
 * Each component is a fraction of the full pano, 0..1.
 */
data class SphereViewCrop(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f) { "crop origin $left, $top" }
        require(width > 0f && height > 0f) { "crop size ${width}x$height" }
        require(left + width <= 1.0001f && top + height <= 1.0001f) {
            "crop runs off the sphere: $left+$width, $top+$height"
        }
    }

    /** True when the image wraps all the way around in longitude. */
    val wrapsLongitude: Boolean get() = width >= 0.99f

    /** Highest latitude the image covers, degrees, positive up. */
    val maxLatitudeDegrees: Float get() = 90f - top * 180f

    /** Lowest latitude the image covers, degrees, positive up. */
    val minLatitudeDegrees: Float get() = 90f - (top + height) * 180f

    companion object {
        val Full = SphereViewCrop(left = 0f, top = 0f, width = 1f, height = 1f)

        fun from(gpano: GPanoMetadata): SphereViewCrop {
            val fullW = gpano.fullPanoWidthPixels.toFloat()
            val fullH = gpano.fullPanoHeightPixels.toFloat()
            return SphereViewCrop(
                left = gpano.croppedAreaLeftPixels / fullW,
                top = gpano.croppedAreaTopPixels / fullH,
                width = gpano.croppedAreaImageWidthPixels / fullW,
                height = gpano.croppedAreaImageHeightPixels / fullH,
            )
        }
    }
}

/**
 * The projection a 360 viewer uses: a pinhole camera looking at the sphere,
 * sampling the equirectangular image the same way the stitcher painted it.
 *
 * Look angles are *viewer* conventions, not sensor ones: [lookYawDegrees] is
 * the compass bearing being looked at (0° = north, matching the canvas centre)
 * and [lookPitchDegrees] is elevation, **positive above the horizon**. That is
 * the opposite sign of [CameraPose.pitchDegrees], which is what a drag-up
 * "look up" gesture wants.
 */
object SphereViewProjection {

    const val DEFAULT_FOV_DEGREES = 75f
    const val MIN_FOV_DEGREES = 35f
    const val MAX_FOV_DEGREES = 110f

    /**
     * Vertical field of view that keeps square pixels for a view of [width] by
     * [height] at a horizontal field of view of [horizontalFovDegrees].
     */
    fun verticalFovDegrees(horizontalFovDegrees: Float, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return horizontalFovDegrees
        val tanHalfH = tan(Math.toRadians(horizontalFovDegrees / 2.0))
        return Math.toDegrees(2.0 * atan(tanHalfH * height / width)).toFloat()
    }

    /**
     * Camera basis for a look direction. Built through [CameraBasis] so a pixel
     * in the viewer is the same world direction the stitcher used for that
     * bearing — the canvas centre is north, and elevation is up.
     */
    fun lookBasis(lookYawDegrees: Float, lookPitchDegrees: Float): CameraBasis =
        CameraBasis.of(
            CameraPose(
                yawDegrees = wrapDegrees(lookYawDegrees),
                // Sensor pitch is negative above the horizon; the viewer is not.
                pitchDegrees = -lookPitchDegrees,
                rollDegrees = 0f,
            )
        )

    /**
     * Column-major 3×3 mapping camera (right, up, forward) into the world frame.
     *
     * The fragment shader multiplies this by `(ndc.x·tan½fovH, ndc.y·tan½fovV, 1)`
     * and gets the world direction that pixel looks at. Layout matches
     * [CameraBasis.toWorld].
     */
    fun cameraMatrix(basis: CameraBasis): FloatArray = floatArrayOf(
        basis.rightX.toFloat(), basis.rightY.toFloat(), basis.rightZ.toFloat(),
        basis.upX.toFloat(), basis.upY.toFloat(), basis.upZ.toFloat(),
        basis.forwardX.toFloat(), basis.forwardY.toFloat(), basis.forwardZ.toFloat(),
    )

    /**
     * World direction the centre of a pixel at NDC ([ndcX], [ndcY]) looks at.
     *
     * NDC is OpenGL's: x right, y up, both −1..1 at the view edges.
     */
    fun viewDirection(
        ndcX: Double,
        ndcY: Double,
        lookYawDegrees: Float,
        lookPitchDegrees: Float,
        tanHalfFovH: Double,
        tanHalfFovV: Double,
    ): DoubleArray {
        val world = lookBasis(lookYawDegrees, lookPitchDegrees)
            .toWorld(ndcX * tanHalfFovH, ndcY * tanHalfFovV, 1.0)
        val length = sqrt(world[0] * world[0] + world[1] * world[1] + world[2] * world[2])
        return doubleArrayOf(world[0] / length, world[1] / length, world[2] / length)
    }

    /**
     * Equirectangular texture coordinates for [direction], or null if that
     * bearing sits outside [crop].
     *
     * `u` runs with longitude (0 at −180°, 0.5 at north, 1 at +180°) and `v`
     * runs from the north pole down, matching [Equirectangular] and every
     * other GPano viewer.
     */
    fun sampleUv(direction: DoubleArray, crop: SphereViewCrop): Pair<Float, Float>? {
        val longitude = Equirectangular.longitudeOf(direction[0], direction[1])
        val latitude = Equirectangular.latitudeOf(direction[2])
        var fullU = ((longitude + 180.0) / 360.0).toFloat()
        if (fullU >= 1f) fullU -= 1f
        if (fullU < 0f) fullU += 1f
        val fullV = ((90.0 - latitude) / 180.0).toFloat()
        val u = (fullU - crop.left) / crop.width
        val v = (fullV - crop.top) / crop.height
        val uOk = if (crop.wrapsLongitude) true else u in 0f..1f
        if (!uOk || v < 0f || v > 1f) return null
        return u to v
    }

    /**
     * How a drag of [panX]/[panY] pixels, on a view of [width]×[height] at
     * [horizontalFovDegrees], should change the look.
     *
     * The scene is grabbed: dragging right pulls the sphere right, which turns
     * the look left. Compose's Y is down, so a finger moving down (positive
     * [panY]) pulls the scene down and the look goes up.
     */
    fun lookDelta(
        panX: Float,
        panY: Float,
        width: Int,
        height: Int,
        horizontalFovDegrees: Float,
    ): Pair<Float, Float> {
        if (width <= 0 || height <= 0) return 0f to 0f
        val fovV = verticalFovDegrees(horizontalFovDegrees, width, height)
        return -panX / width * horizontalFovDegrees to panY / height * fovV
    }

    /**
     * Keeps the look inside [crop] so a ring capture cannot be spun into the
     * uncovered poles. When the band is narrower than the vertical field of
     * view the look sits on the band's centre and the shader fills the rest
     * with black.
     */
    fun clampPitch(
        lookPitchDegrees: Float,
        verticalFovDegrees: Float,
        crop: SphereViewCrop,
    ): Float {
        val half = verticalFovDegrees / 2f
        val min = crop.minLatitudeDegrees + half
        val max = crop.maxLatitudeDegrees - half
        return if (min < max) {
            lookPitchDegrees.coerceIn(min, max)
        } else {
            (crop.minLatitudeDegrees + crop.maxLatitudeDegrees) / 2f
        }
    }

    fun wrapDegrees(degrees: Float): Float {
        var value = degrees % 360f
        if (value >= 180f) value -= 360f
        if (value < -180f) value += 360f
        return value
    }
}
