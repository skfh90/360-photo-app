package com.n30dyn4m1c.photosphere.result

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.tan

private const val TAG = "SphereViewer"

/**
 * A pannable 360° view of an equirectangular sphere.
 *
 * Drag grabs the scene; pinch changes the field of view. The look starts on
 * north — the centre of the canvas, which is where guided capture begins —
 * and stays inside [crop] so a ring capture cannot be aimed at uncovered sky.
 *
 * Rendered with a full-screen OpenGL ES 2.0 quad rather than a tessellated
 * sphere: every pixel is a look direction, sampled through the same mapping
 * the stitcher used to paint the JPEG. No extra dependency, and it runs on
 * every device this app already supports.
 */
@Composable
fun SphereViewer(
    bitmap: Bitmap,
    crop: SphereViewCrop,
    modifier: Modifier = Modifier,
    onLook: () -> Unit = {},
) {
    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var fov by remember {
        mutableFloatStateOf(SphereViewProjection.DEFAULT_FOV_DEGREES)
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier.onSizeChanged { viewSize = it },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> SphereGlView(context) },
            update = { view ->
                view.setSphere(bitmap, crop)
                view.setLook(yaw, pitch, fov)
            },
        )
        // Gestures sit on a Compose overlay rather than on the SurfaceView:
        // a SurfaceView is a separate compositor layer and would eat the
        // pointer stream before detectTransformGestures ever saw it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(crop) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val size = viewSize
                        if (size.width > 0 && size.height > 0) {
                            val (dYaw, dPitch) = SphereViewProjection.lookDelta(
                                panX = pan.x,
                                panY = pan.y,
                                width = size.width,
                                height = size.height,
                                horizontalFovDegrees = fov,
                            )
                            fov = (fov / zoom).coerceIn(
                                SphereViewProjection.MIN_FOV_DEGREES,
                                SphereViewProjection.MAX_FOV_DEGREES,
                            )
                            val fovV = SphereViewProjection.verticalFovDegrees(
                                fov, size.width, size.height,
                            )
                            yaw = SphereViewProjection.wrapDegrees(yaw + dYaw)
                            pitch = SphereViewProjection.clampPitch(
                                pitch + dPitch, fovV, crop,
                            )
                        }
                        onLook()
                    }
                },
        )
    }
}

/**
 * [GLSurfaceView] that paints one equirectangular frame as a look-around.
 *
 * Lifecycle pause/resume is bound through the ViewTree owner so a Compose
 * recomposition cannot leak the GL thread after this screen leaves.
 */
internal class SphereGlView(context: Context) : GLSurfaceView(context) {

    private val renderer = EquirectSphereRenderer()
    private var lifecycleObserver: LifecycleEventObserver? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.RGBA_8888)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val owner = findViewTreeLifecycleOwner() ?: return
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_PAUSE -> onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        lifecycleObserver = observer
    }

    override fun onDetachedFromWindow() {
        lifecycleObserver?.let { observer ->
            findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(observer)
            lifecycleObserver = null
        }
        onPause()
        super.onDetachedFromWindow()
    }

    fun setSphere(bitmap: Bitmap, crop: SphereViewCrop) {
        queueEvent {
            renderer.setSphere(bitmap, crop)
            requestRender()
        }
    }

    fun setLook(yawDegrees: Float, pitchDegrees: Float, fovDegrees: Float) {
        renderer.lookYawDegrees = yawDegrees
        renderer.lookPitchDegrees = pitchDegrees
        renderer.horizontalFovDegrees = fovDegrees
        requestRender()
    }
}

/**
 * Full-screen quad whose fragment shader turns each pixel into a world
 * direction and samples the equirectangular texture there.
 *
 * All GL calls stay on the GL thread. Look angles are written from the UI
 * thread as volatiles and read once per frame.
 */
private class EquirectSphereRenderer : GLSurfaceView.Renderer {

    @Volatile var lookYawDegrees: Float = 0f
    @Volatile var lookPitchDegrees: Float = 0f
    @Volatile var horizontalFovDegrees: Float = SphereViewProjection.DEFAULT_FOV_DEGREES

    private var program = 0
    private var aPosition = 0
    private var uCam = 0
    private var uTanHalfFov = 0
    private var uCrop = 0
    private var uWrapS = 0
    private var uTexture = 0
    private var textureId = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var crop = SphereViewCrop.Full
    private var pendingBitmap: Bitmap? = null
    private var uploadedBitmap: Bitmap? = null

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD)
            position(0)
        }

    private val camMatrix = FloatArray(9)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program != 0) {
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            uCam = GLES20.glGetUniformLocation(program, "uCam")
            uTanHalfFov = GLES20.glGetUniformLocation(program, "uTanHalfFov")
            uCrop = GLES20.glGetUniformLocation(program, "uCrop")
            uWrapS = GLES20.glGetUniformLocation(program, "uWrapS")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        uploadedBitmap = null
        pendingBitmap?.let { upload(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingBitmap?.let { upload(it) }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0 || uploadedBitmap == null) return

        val fovH = horizontalFovDegrees
        val fovV = SphereViewProjection.verticalFovDegrees(fovH, surfaceWidth, surfaceHeight)
        val tanH = tan(Math.toRadians(fovH / 2.0)).toFloat()
        val tanV = tan(Math.toRadians(fovV / 2.0)).toFloat()
        val basis = SphereViewProjection.lookBasis(lookYawDegrees, lookPitchDegrees)
        SphereViewProjection.cameraMatrix(basis).copyInto(camMatrix)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix3fv(uCam, 1, false, camMatrix, 0)
        GLES20.glUniform2f(uTanHalfFov, tanH, tanV)
        GLES20.glUniform4f(uCrop, crop.left, crop.top, crop.width, crop.height)
        GLES20.glUniform1f(uWrapS, if (crop.wrapsLongitude) 1f else 0f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
    }

    fun setSphere(bitmap: Bitmap, crop: SphereViewCrop) {
        this.crop = crop
        if (bitmap !== uploadedBitmap) {
            pendingBitmap = bitmap
        }
    }

    private fun upload(bitmap: Bitmap) {
        if (textureId == 0 || bitmap.isRecycled) return
        val sized = fitToMaxTexture(bitmap)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            if (crop.wrapsLongitude) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, sized, 0)
        if (sized !== bitmap) sized.recycle()
        uploadedBitmap = bitmap
        pendingBitmap = null
    }

    private fun fitToMaxTexture(bitmap: Bitmap): Bitmap {
        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        val max = maxSize[0].coerceAtLeast(1024)
        if (bitmap.width <= max && bitmap.height <= max) return bitmap
        val scale = max.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val link = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}

private val QUAD = floatArrayOf(
    -1f, -1f,
    1f, -1f,
    -1f, 1f,
    1f, 1f,
)

private const val VERTEX_SHADER = """
attribute vec2 aPosition;
varying vec2 vNdc;
void main() {
    vNdc = aPosition;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

/**
 * Inverse equirectangular sample. Must stay in lock-step with
 * [SphereViewProjection]: `uCam` is CameraBasis.toWorld as a mat3, crop is
 * GPano's cropped area as fractions of the full sphere, and longitude wraps
 * the same way [Equirectangular.longitudeOf] does.
 */
private const val FRAGMENT_SHADER = """
precision highp float;
uniform sampler2D uTexture;
uniform mat3 uCam;
uniform vec2 uTanHalfFov;
uniform vec4 uCrop;
uniform float uWrapS;
varying vec2 vNdc;

void main() {
    vec3 cam = normalize(vec3(vNdc.x * uTanHalfFov.x, vNdc.y * uTanHalfFov.y, 1.0));
    vec3 dir = normalize(uCam * cam);
    float lon = atan(dir.x, dir.y);
    float lat = asin(clamp(dir.z, -1.0, 1.0));
    float fullU = lon / 6.28318530718 + 0.5;
    float fullV = 0.5 - lat / 3.14159265359;
    vec2 uv = (vec2(fullU, fullV) - uCrop.xy) / uCrop.zw;
    bool outsideY = uv.y < 0.0 || uv.y > 1.0;
    bool outsideX = uWrapS < 0.5 && (uv.x < 0.0 || uv.x > 1.0);
    if (outsideY || outsideX) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        gl_FragColor = texture2D(uTexture, vec2(uv.x, uv.y));
    }
}
"""
