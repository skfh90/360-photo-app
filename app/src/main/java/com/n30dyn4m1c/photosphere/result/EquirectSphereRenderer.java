package com.n30dyn4m1c.photosphere.result;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import com.n30dyn4m1c.photosphere.stitching.CameraBasis;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Full-screen quad whose fragment shader turns each pixel into a world
 * direction and samples the equirectangular texture there.
 *
 * <p>All GL calls stay on the GL thread. Look angles are written from the UI
 * thread as volatiles and read once per frame.
 */
public class EquirectSphereRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "SphereViewer";

    private static final float[] QUAD = new float[] {
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "varying vec2 vNdc;\n"
                    + "void main() {\n"
                    + "    vNdc = aPosition;\n"
                    + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
                    + "}\n";

    /**
     * Inverse equirectangular sample. Must stay in lock-step with
     * {@link SphereViewProjection}: {@code uCam} is CameraBasis.toWorld as a mat3, crop is
     * GPano's cropped area as fractions of the full sphere, and longitude wraps
     * the same way {@code Equirectangular.longitudeOf} does.
     */
    private static final String FRAGMENT_SHADER =
            "precision highp float;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "uniform mat3 uCam;\n"
                    + "uniform vec2 uTanHalfFov;\n"
                    + "uniform vec4 uCrop;\n"
                    + "uniform float uWrapS;\n"
                    + "varying vec2 vNdc;\n"
                    + "\n"
                    + "void main() {\n"
                    + "    vec3 cam = normalize(vec3(vNdc.x * uTanHalfFov.x, vNdc.y * uTanHalfFov.y, 1.0));\n"
                    + "    vec3 dir = normalize(uCam * cam);\n"
                    + "    float lon = atan(dir.x, dir.y);\n"
                    + "    float lat = asin(clamp(dir.z, -1.0, 1.0));\n"
                    + "    float fullU = lon / 6.28318530718 + 0.5;\n"
                    + "    float fullV = 0.5 - lat / 3.14159265359;\n"
                    + "    vec2 uv = (vec2(fullU, fullV) - uCrop.xy) / uCrop.zw;\n"
                    + "    bool outsideY = uv.y < 0.0 || uv.y > 1.0;\n"
                    + "    bool outsideX = uWrapS < 0.5 && (uv.x < 0.0 || uv.x > 1.0);\n"
                    + "    if (outsideY || outsideX) {\n"
                    + "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
                    + "    } else {\n"
                    + "        gl_FragColor = texture2D(uTexture, vec2(uv.x, uv.y));\n"
                    + "    }\n"
                    + "}\n";

    public volatile float lookYawDegrees = 0f;
    public volatile float lookPitchDegrees = 0f;
    public volatile float horizontalFovDegrees = SphereViewProjection.DEFAULT_FOV_DEGREES;

    private int program = 0;
    private int aPosition = 0;
    private int uCam = 0;
    private int uTanHalfFov = 0;
    private int uCrop = 0;
    private int uWrapS = 0;
    private int uTexture = 0;
    private int textureId = 0;
    private int surfaceWidth = 1;
    private int surfaceHeight = 1;
    private SphereViewCrop crop = SphereViewCrop.Full;
    private Bitmap pendingBitmap;
    private Bitmap uploadedBitmap;

    private final FloatBuffer quad;
    private final float[] camMatrix = new float[9];

    public EquirectSphereRenderer() {
        quad = ByteBuffer
                .allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quad.put(QUAD);
        quad.position(0);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program != 0) {
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            uCam = GLES20.glGetUniformLocation(program, "uCam");
            uTanHalfFov = GLES20.glGetUniformLocation(program, "uTanHalfFov");
            uCrop = GLES20.glGetUniformLocation(program, "uCrop");
            uWrapS = GLES20.glGetUniformLocation(program, "uWrapS");
            uTexture = GLES20.glGetUniformLocation(program, "uTexture");
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        uploadedBitmap = null;
        if (pendingBitmap != null) {
            upload(pendingBitmap);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = Math.max(width, 1);
        surfaceHeight = Math.max(height, 1);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (pendingBitmap != null) {
            upload(pendingBitmap);
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (program == 0 || uploadedBitmap == null) {
            return;
        }

        float fovH = horizontalFovDegrees;
        float fovV = SphereViewProjection.verticalFovDegrees(fovH, surfaceWidth, surfaceHeight);
        float tanH = (float) Math.tan(Math.toRadians(fovH / 2.0));
        float tanV = (float) Math.tan(Math.toRadians(fovV / 2.0));
        CameraBasis basis = SphereViewProjection.lookBasis(lookYawDegrees, lookPitchDegrees);
        float[] matrix = SphereViewProjection.cameraMatrix(basis);
        System.arraycopy(matrix, 0, camMatrix, 0, matrix.length);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix3fv(uCam, 1, false, camMatrix, 0);
        GLES20.glUniform2f(uTanHalfFov, tanH, tanV);
        GLES20.glUniform4f(uCrop, crop.getLeft(), crop.getTop(), crop.getWidth(), crop.getHeight());
        GLES20.glUniform1f(uWrapS, crop.getWrapsLongitude() ? 1f : 0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(uTexture, 0);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPosition);
    }

    public void setSphere(Bitmap bitmap, SphereViewCrop crop) {
        this.crop = crop;
        if (bitmap != uploadedBitmap) {
            pendingBitmap = bitmap;
        }
    }

    private void upload(Bitmap bitmap) {
        if (textureId == 0 || bitmap.isRecycled()) {
            return;
        }
        Bitmap sized = fitToMaxTexture(bitmap);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                crop.getWrapsLongitude() ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, sized, 0);
        if (sized != bitmap) {
            sized.recycle();
        }
        uploadedBitmap = bitmap;
        pendingBitmap = null;
    }

    private Bitmap fitToMaxTexture(Bitmap bitmap) {
        int[] maxSize = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0);
        int max = Math.max(maxSize[0], 1024);
        if (bitmap.getWidth() <= max && bitmap.getHeight() <= max) {
            return bitmap;
        }
        float scale = max / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
        int w = Math.max((int) (bitmap.getWidth() * scale), 1);
        int h = Math.max((int) (bitmap.getHeight() * scale), 1);
        return Bitmap.createScaledBitmap(bitmap, w, h, true);
    }

    private int buildProgram(String vertex, String fragment) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] link = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0);
        if (link[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
