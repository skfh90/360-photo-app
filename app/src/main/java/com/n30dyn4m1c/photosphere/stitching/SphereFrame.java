package com.n30dyn4m1c.photosphere.stitching;

import java.io.File;

/**
 * One frame on its way into a sphere: the pixels, and where the camera was.
 *
 * The pose is what makes this pipeline possible at all — see the class docs on
 * {@link PhotoSphereStitcher}.
 */
public final class SphereFrame {
    public final File file;
    public final CameraPose pose;

    public SphereFrame(File file, CameraPose pose) {
        this.file = file;
        this.pose = pose;
    }
}
