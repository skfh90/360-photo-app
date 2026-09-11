package com.n30dyn4m1c.photosphere;

import android.app.Application;
import android.util.Log;

import org.opencv.android.OpenCVLoader;

/**
 * Loads OpenCV's native library once, at process start.
 *
 * {@link OpenCVLoader#initLocal()} links against the {@code .so} files packaged inside the
 * APK — there is no separate "OpenCV Manager" app to install. If it returns
 * false the stitching pipeline is unavailable, but capture still works, so we
 * record the state instead of crashing.
 */
public class PhotoSphereApplication extends Application {

    private static final String TAG = "PhotoSphere";

    /** True once the OpenCV native library has loaded successfully. */
    private static boolean isOpenCvAvailable;

    @Override
    public void onCreate() {
        super.onCreate();
        isOpenCvAvailable = OpenCVLoader.initLocal();
        if (isOpenCvAvailable) {
            Log.i(TAG, "OpenCV initialised: " + OpenCVLoader.OPENCV_VERSION);
        } else {
            Log.e(TAG, "OpenCV failed to initialise; sphere stitching is disabled");
        }
    }

    public static boolean isOpenCvAvailable() {
        return isOpenCvAvailable;
    }
}
