package com.n30dyn4m1c.photosphere.stitching;

/**
 * Thrown when a blocking stitch is cancelled via {@link PhotoSphereStitcher#cancel()}
 * or {@link Thread#interrupt()} at a stage boundary.
 */
public final class StitchCancelledException extends RuntimeException {
    public StitchCancelledException() {
        super("Stitch cancelled");
    }
}
