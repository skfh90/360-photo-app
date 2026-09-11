package com.n30dyn4m1c.photosphere.stitching;

/**
 * Why a stitch ended the way it did.
 *
 * The codes are this pipeline's contract with its own logs and bug reports, not
 * anyone else's: 0–3 are the outcomes a registration pass can have, and
 * everything from 100 up is a failure of the machinery around it.
 */
public enum StitchStatus {
    /** A panorama came back. */
    Ok(0),

    /**
     * Too little of the sphere was covered to be worth calling a photo sphere.
     * Usually a run that stopped early, or a pan that skipped a chunk.
     */
    NeedMoreImages(1),

    /**
     * The frames could not be reconciled into one view. The classic cause is the
     * camera *translating* between frames — walking, or pivoting around the body
     * rather than the lens — which breaks the pure-rotation assumption a
     * panorama is built on.
     */
    AlignmentFailed(2),

    /** No consistent camera could explain the frames that were handed in. */
    CameraEstimationFailed(3),

    /** The native library never loaded, so there is no stitcher to run. */
    OpenCvUnavailable(100),

    /** Nothing was handed in. */
    NoInputImages(101),

    /** A buffered frame could not be decoded — deleted, or truncated mid-write. */
    UnreadableInput(102),

    /** The render completed but reached none of the sphere. */
    EmptyResult(103),

    /** Ran out of memory holding the frames or the panorama. */
    OutOfMemory(104),

    /** Anything else, including native exceptions from inside OpenCV. */
    Unknown(199);

    public final int code;

    StitchStatus(int code) {
        this.code = code;
    }
}
