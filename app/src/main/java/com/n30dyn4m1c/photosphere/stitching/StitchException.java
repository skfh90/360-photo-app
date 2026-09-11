package com.n30dyn4m1c.photosphere.stitching;

/** A stitch that did not produce a panorama, carrying the {@link #status} that says why. */
public final class StitchException extends Exception {
    public final StitchStatus status;

    public StitchException(StitchStatus status, String message) {
        this(status, message, null);
    }

    public StitchException(StitchStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
