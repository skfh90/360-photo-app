package com.n30dyn4m1c.photosphere.camera;

/**
 * How much of the sphere a capture run is aiming at.
 *
 * The difference is only in how many rings get laid out. Either way the run can
 * be stitched from whatever has been captured when the user stops, so this sets
 * the target the progress bar counts against, not a floor on the result.
 */
public enum SphereCaptureScope {
    /**
     * One ring around the horizon.
     *
     * A dozen-odd frames instead of forty, and the natural thing to shoot when
     * what matters is around you rather than above and below: a street, a
     * viewpoint, a room at eye level. The output is still equirectangular, with
     * the sky and floor left black.
     */
    Ring,

    /** Rings from the horizon out to both poles: the whole sphere. */
    Sphere
}
