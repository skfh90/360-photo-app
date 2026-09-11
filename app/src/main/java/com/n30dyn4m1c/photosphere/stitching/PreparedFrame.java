package com.n30dyn4m1c.photosphere.stitching;

import org.opencv.core.Mat;

/**
 * One frame ready to be painted onto the sphere: its pixels, where the camera
 * was, and the block of canvas it can reach.
 *
 * {@link #image} is {@code CV_8UC3} and belongs to whoever built the frame — the renderer
 * reads from it and never releases it.
 */
public final class PreparedFrame {
    public final Mat image;
    public final CameraBasis basis;
    public final FrameIntrinsics intrinsics;
    public final CanvasFootprint footprint;

    public PreparedFrame(
        Mat image,
        CameraBasis basis,
        FrameIntrinsics intrinsics,
        CanvasFootprint footprint
    ) {
        this.image = image;
        this.basis = basis;
        this.intrinsics = intrinsics;
        this.footprint = footprint;
    }
}
