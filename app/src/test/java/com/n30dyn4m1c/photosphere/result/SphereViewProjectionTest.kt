package com.n30dyn4m1c.photosphere.result

import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.stitching.Equirectangular
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/**
 * The 360 viewer has to sample the same sphere the stitcher painted, or a
 * look that should show north shows a seam instead. These cases pin the
 * mapping down without spinning up OpenGL.
 */
class SphereViewProjectionTest {

    @Test
    fun `looking north at the screen centre samples the middle of a full pano`() {
        val uv = centreUv(lookYawDegrees = 0f, lookPitchDegrees = 0f)

        assertNotNull(uv)
        assertEquals(0.5f, uv!!.first, 1e-4f)
        assertEquals(0.5f, uv.second, 1e-4f)
    }

    @Test
    fun `looking east samples three-quarters of the way across the canvas`() {
        // Longitude 90° is (90 + 180) / 360 = 0.75, the same column Equirectangular
        // would pick for a frame shot facing east.
        val uv = centreUv(lookYawDegrees = 90f, lookPitchDegrees = 0f)

        assertEquals(0.75f, uv!!.first, 1e-4f)
        assertEquals(0.5f, uv.second, 1e-4f)
    }

    @Test
    fun `looking west samples a quarter of the way across the canvas`() {
        val uv = centreUv(lookYawDegrees = -90f, lookPitchDegrees = 0f)

        assertEquals(0.25f, uv!!.first, 1e-4f)
    }

    @Test
    fun `looking up moves the sample toward the top of the image`() {
        val horizon = centreUv(lookYawDegrees = 0f, lookPitchDegrees = 0f)!!.second
        val up = centreUv(lookYawDegrees = 0f, lookPitchDegrees = 30f)!!.second

        assertTrue("looking up should decrease v (north pole is v=0), was $up vs $horizon", up < horizon)
    }

    @Test
    fun `the look direction matches Equirectangular_direction at the same bearing`() {
        val yaw = 40.0
        val pitch = 15.0
        val dir = SphereViewProjection.viewDirection(
            ndcX = 0.0,
            ndcY = 0.0,
            lookYawDegrees = yaw.toFloat(),
            lookPitchDegrees = pitch.toFloat(),
            tanHalfFovH = 1.0,
            tanHalfFovV = 1.0,
        )
        val expected = Equirectangular.direction(yaw, pitch)

        assertEquals(expected[0], dir[0], 1e-6)
        assertEquals(expected[1], dir[1], 1e-6)
        assertEquals(expected[2], dir[2], 1e-6)
    }

    @Test
    fun `a ring crop rejects a look at the zenith`() {
        val crop = SphereViewCrop.from(
            GPanoMetadata.forSphereRegion(
                imageWidth = 4096,
                imageHeight = 1024,
                longitudeSpanDegrees = 360f,
                centerLongitudeDegrees = 0f,
                latitudeSpanDegrees = 80f,
                centerLatitudeDegrees = 0f,
            )
        )
        val dir = SphereViewProjection.viewDirection(
            ndcX = 0.0,
            ndcY = 0.0,
            lookYawDegrees = 0f,
            lookPitchDegrees = 90f,
            tanHalfFovH = 0.1,
            tanHalfFovV = 0.1,
        )

        assertNull(SphereViewProjection.sampleUv(dir, crop))
    }

    @Test
    fun `a ring crop still samples the horizon`() {
        val crop = SphereViewCrop.from(
            GPanoMetadata.forSphereRegion(
                imageWidth = 4096,
                imageHeight = 1024,
                longitudeSpanDegrees = 360f,
                centerLongitudeDegrees = 0f,
                latitudeSpanDegrees = 80f,
                centerLatitudeDegrees = 0f,
            )
        )
        val uv = SphereViewProjection.sampleUv(
            SphereViewProjection.viewDirection(
                ndcX = 0.0,
                ndcY = 0.0,
                lookYawDegrees = 0f,
                lookPitchDegrees = 0f,
                tanHalfFovH = 0.1,
                tanHalfFovV = 0.1,
            ),
            crop,
        )

        assertNotNull(uv)
        assertEquals(0.5f, uv!!.first, 1e-3f)
        assertEquals(0.5f, uv.second, 1e-3f)
    }

    @Test
    fun `GPano full pano is a full-sphere crop`() {
        val crop = SphereViewCrop.from(GPanoMetadata.forFullPano(4096, 2048))

        assertEquals(SphereViewCrop.Full, crop)
        assertTrue(crop.wrapsLongitude)
        assertEquals(90f, crop.maxLatitudeDegrees, 0.01f)
        assertEquals(-90f, crop.minLatitudeDegrees, 0.01f)
    }

    @Test
    fun `dragging right looks left so the scene follows the finger`() {
        val (dYaw, dPitch) = SphereViewProjection.lookDelta(
            panX = 100f,
            panY = 0f,
            width = 1000,
            height = 500,
            horizontalFovDegrees = 80f,
        )

        assertEquals(-8f, dYaw, 1e-4f)
        assertEquals(0f, dPitch, 1e-4f)
    }

    @Test
    fun `dragging down looks up so the scene follows the finger`() {
        val (_, dPitch) = SphereViewProjection.lookDelta(
            panX = 0f,
            panY = 50f,
            width = 1000,
            height = 500,
            horizontalFovDegrees = 80f,
        )

        assertTrue("downward drag should raise the look, was $dPitch", dPitch > 0f)
    }

    @Test
    fun `pitch is clamped inside a ring so the look cannot leave the band`() {
        val crop = SphereViewCrop(left = 0f, top = 0.3f, width = 1f, height = 0.4f)
        val clamped = SphereViewProjection.clampPitch(
            lookPitchDegrees = 80f,
            verticalFovDegrees = 20f,
            crop = crop,
        )

        assertTrue(clamped < 80f)
        assertTrue(clamped <= crop.maxLatitudeDegrees - 10f + 1e-3f)
        assertTrue(clamped >= crop.minLatitudeDegrees + 10f - 1e-3f)
    }

    @Test
    fun `wrapDegrees keeps a spin through south continuous`() {
        assertEquals(-180f, SphereViewProjection.wrapDegrees(180f), 0f)
        assertEquals(-170f, SphereViewProjection.wrapDegrees(190f), 1e-4f)
        assertEquals(10f, SphereViewProjection.wrapDegrees(-350f), 1e-4f)
    }

    @Test
    fun `a pixel to the right of centre looks east of the aim`() {
        val tanHalf = tan(Math.toRadians(40.0))
        val centre = SphereViewProjection.viewDirection(
            0.0, 0.0, 0f, 0f, tanHalf, tanHalf,
        )
        val right = SphereViewProjection.viewDirection(
            0.5, 0.0, 0f, 0f, tanHalf, tanHalf,
        )
        val centreLon = Equirectangular.longitudeOf(centre[0], centre[1])
        val rightLon = Equirectangular.longitudeOf(right[0], right[1])

        assertTrue(
            "a pixel to the right should increase longitude, was $rightLon vs $centreLon",
            rightLon > centreLon,
        )
        assertTrue(abs(Equirectangular.latitudeOf(right[2])) < 1.0)
    }

    private fun centreUv(lookYawDegrees: Float, lookPitchDegrees: Float): Pair<Float, Float>? {
        val tanHalf = tan(Math.toRadians(SphereViewProjection.DEFAULT_FOV_DEGREES / 2.0))
        return SphereViewProjection.sampleUv(
            SphereViewProjection.viewDirection(
                ndcX = 0.0,
                ndcY = 0.0,
                lookYawDegrees = lookYawDegrees,
                lookPitchDegrees = lookPitchDegrees,
                tanHalfFovH = tanHalf,
                tanHalfFovV = tanHalf,
            ),
            SphereViewCrop.Full,
        )
    }
}
