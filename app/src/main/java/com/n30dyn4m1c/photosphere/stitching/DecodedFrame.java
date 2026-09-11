package com.n30dyn4m1c.photosphere.stitching;

import org.opencv.core.Mat;

/**
 * One decoded frame on its way into refinement: the pixels, the lens model, and
 * the measured pose the sensor reported when it was shot.
 */
public final class DecodedFrame {
    public final Mat image;
    public final FrameIntrinsics intrinsics;
    public final CameraBasis sensorBasis;

    public DecodedFrame(Mat image, FrameIntrinsics intrinsics, CameraBasis sensorBasis) {
        this.image = image;
        this.intrinsics = intrinsics;
        this.sensorBasis = sensorBasis;
    }
}
