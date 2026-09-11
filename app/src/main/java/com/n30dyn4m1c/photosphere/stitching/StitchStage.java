package com.n30dyn4m1c.photosphere.stitching;

/** Which part of the pipeline is running. */
public enum StitchStage {
    /** Checking inputs and the native library. */
    Preparing,

    /** Decoding buffered frames into matrices. */
    Reading,

    /** Matching features between overlapping frames and correcting the poses. */
    Refining,

    /** Finding the seam lines the overlaps will be cut along. */
    Seaming,

    /** Projecting frames onto the sphere and blending the overlaps. */
    Stitching,

    /** Turning the finished canvas into a bitmap. */
    Projecting,
}
