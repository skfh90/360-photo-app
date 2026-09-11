package com.n30dyn4m1c.photosphere.stitching;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import com.n30dyn4m1c.photosphere.BuildConfig;
import com.n30dyn4m1c.photosphere.PhotoSphereApplication;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Turns a session's frames into one equirectangular sphere.
 *
 * <b>Why this is not OpenCV's {@code Stitcher}.</b> It cannot be: the stitching module is
 * absent from every prebuilt OpenCV for Android. {@code org.opencv:opencv} ships Java
 * bindings for core, imgproc, features2d, calib3d and the rest, but there is no
 * {@code org.opencv.stitching} package and {@code libopencv_java4.so} contains none of the
 * pipeline's symbols either. {@code cv::Stitcher} is, in practice, a desktop API.
 *
 * <b>What replaces it.</b> Guided capture already knows where the camera was
 * pointing for every frame, because the alignment gate only fires when the
 * device is held on a known target. That turns the hard half of stitching —
 * solving for each camera's rotation — into something already measured, and what
 * is left is a reprojection: every pixel of the output canvas is a direction on
 * the sphere, and for each frame that direction is rotated into the frame's axes
 * and divided through by depth to find the pixel that saw it. Overlaps are
 * resolved by multi-band blending (see {@link MultibandBlender}): every frame and its
 * feather mask are split into Laplacian/Gaussian pyramids and each band is
 * cross-faded with a mask sized to the band, so a seam is faded at every scale
 * by a transition narrower than the detail that scale carries.
 *
 * The measured poses are not the last word. {@link PoseRefiner} matches ORB features
 * between overlapping frames and solves for a small per-frame correction on top
 * of the sensor pose — the sensor stays the starting guess and the fallback, and
 * the image content decides the fine alignment. The same matches also refine
 * the field of view: a per-frame focal scale is solved from the matched
 * bearings, so the last bit of scale error a loosely-described lens leaves
 * behind is absorbed before rendering (see {@link PoseRefiner}). The lens's radial
 * distortion is carried through the whole model (see {@link RadialDistortion}) so
 * frame edges, where seams live, land where the lens really put them, and
 * {@link ExposureCompensation} equalises per-frame brightness so the blends do not
 * show seams of light.
 *
 * The result is equirectangular *by construction* rather than by cropping
 * something else into shape: a pixel's row *is* its latitude, so an incomplete
 * sphere is black exactly where it was not shot, at the right elevation.
 *
 * The Kotlin {@code suspend} entry point is a blocking method here. Failures throw
 * {@link StitchException}. Cancel via {@link #cancel()} or {@link Thread#interrupt()}
 * throws {@link StitchCancelledException} at stage boundaries.
 */
public final class PhotoSphereStitcher {

    private static final String TAG = "PhotoSphereStitcher";

    /** Debug palette for {@link #stitchPhotos} colour-frames mode. */
    private static final Scalar[] DEBUG_FRAME_COLORS = new Scalar[] {
        new Scalar(90.0, 30.0, 230.0),   // blue
        new Scalar(40.0, 210.0, 80.0),   // green
        new Scalar(40.0, 40.0, 230.0),   // red
        new Scalar(220.0, 210.0, 30.0),  // yellow
        new Scalar(220.0, 40.0, 230.0),  // magenta
        new Scalar(40.0, 220.0, 230.0),  // cyan
    };

    /**
     * Below this a stitch is not worth offering; also what the UI gates on.
     *
     * Three, because three overlapping frames already make a panorama worth
     * having — a corner of a room, a stretch of skyline — and holding the button
     * back until a third of a sphere is in the buffer only turns a usable
     * capture into a failed one. The pipeline places frames from their measured
     * pose rather than by searching for a chain of matches, so it has no
     * minimum-frames requirement of its own; what the extra frames buy is
     * coverage, and coverage is the user's call to make.
     */
    public static final int MIN_FRAMES = 3;

    /** One frame is a photo, not a panorama. */
    public static final int MIN_STITCHABLE_FRAMES = 2;

    /** Long-edge limit each frame is decoded down to. */
    public static final int DEFAULT_MAX_INPUT_DIMENSION = 1024;

    /** Width cap for the finished sphere; the canvas is always half as tall. */
    public static final int DEFAULT_MAX_OUTPUT_WIDTH = 4096;

    /** Never render a canvas narrower than this, however coarse the input. */
    private static final int MIN_OUTPUT_WIDTH = 512;

    /**
     * Fraction of the sphere that has to be covered for the result to be worth
     * showing.
     *
     * Deliberately near the floor. Three frames of a narrow lens reach under 2%
     * of the canvas, and a partial capture — a single ring, or a corner of a
     * room — is now a supported outcome rather than a failed sphere, so this is
     * only here to catch a render that reached essentially nothing. Anything
     * above it is the user's to judge on the result screen.
     */
    private static final float MIN_COVERAGE = 0.002f;

    /** Gaussian sigma for the unsharp mask, in output pixels. */
    private static final double UNSHARP_RADIUS = 1.5;

    /** Pixels brighter than this count as shot content rather than a gap. */
    private static final double UNSHARP_CONTENT_THRESHOLD = 24.0;

    /** Rows the unsharp mask sharpens at a time. */
    private static final int UNSHARP_BAND_HEIGHT = 256;

    /**
     * Rows of context carried either side of a band.
     *
     * The Gaussian reaches {@link #UNSHARP_RADIUS}·3 pixels or so; a band blurred
     * without its neighbours would darken toward its own edges and leave a
     * horizontal line every {@link #UNSHARP_BAND_HEIGHT} rows.
     */
    private static final int UNSHARP_HALO = 16;

    private static volatile boolean isOpenCvReady = false;

    /** Set by {@link #cancel()} and checked with {@link Thread#isInterrupted()} at stage boundaries. */
    private static volatile boolean cancelled;

    /** Progress from the stitching thread; do not write Compose state from it directly. */
    public interface ProgressCallback {
        void onProgress(StitchProgress progress);
    }

    private PhotoSphereStitcher() {}

    /** Asks a running {@link #stitchPhotos} to abort at the next stage boundary. */
    public static void cancel() {
        cancelled = true;
    }

    /**
     * Loads the native library if it is not up already.
     *
     * {@link PhotoSphereApplication} does this at process start; this is the belt to
     * that braces, for a stitch running in a process where the Application class
     * never ran. {@code initLocal} is idempotent, so calling it twice costs nothing.
     */
    public static synchronized boolean ensureOpenCv() {
        if (isOpenCvReady) return true;
        isOpenCvReady = PhotoSphereApplication.isOpenCvAvailable()
            || OpenCVLoader.initLocal();
        if (!isOpenCvReady) Log.e(TAG, "OpenCV native library unavailable; cannot stitch");
        return isOpenCvReady;
    }

    /**
     * Stitches {@code frames} into an equirectangular {@link Bitmap}.
     *
     * Blocking Java equivalent of the Kotlin {@code suspend} method. Failures throw
     * {@link StitchException}. Cancel via {@link #cancel()} or thread interrupt throws
     * {@link StitchCancelledException}.
     */
    public static Bitmap stitchPhotos(
        List<SphereFrame> frames,
        float horizontalFovDegrees,
        float verticalFovDegrees,
        RadialDistortion radialDistortion,
        int maxInputDimension,
        int maxOutputWidth,
        float unsharpAmount,
        PivotModel pivot,
        int portraitRotationDegrees,
        boolean useRefinement,
        boolean useSeams,
        boolean debugColorFrames,
        float longitudeSpanDegrees,
        float centerLongitudeDegrees,
        float latitudeSpanDegrees,
        float centerLatitudeDegrees,
        ProgressCallback onProgress
    ) throws StitchException {
        cancelled = false;
        ArrayList<DecodedFrame> decoded = new ArrayList<DecodedFrame>(frames.size());
        try {
            report(onProgress, StitchProgress.Preparing);
            checkCancelled();

            if (!ensureOpenCv()) {
                throw new StitchException(
                    StitchStatus.OpenCvUnavailable,
                    "OpenCV is not loaded on this device"
                );
            }
            if (frames.isEmpty()) {
                throw new StitchException(StitchStatus.NoInputImages, "No frames to stitch");
            }
            if (frames.size() < MIN_STITCHABLE_FRAMES) {
                throw new StitchException(
                    StitchStatus.NeedMoreImages,
                    "Got " + frames.size() + " frame(s), need at least " + MIN_STITCHABLE_FRAMES
                );
            }

            // The canvas is sized to the detail the frames actually carry: a
            // frame is worth `width / horizontal fov` pixels per degree, and 360°
            // of that is as wide as the sphere can be without inventing pixels.
            int canvasWidth = maxOutputWidth;

            // The field of view describes the *upright* frame, as captured on
            // the portrait-locked display. It is checked once, against the first
            // decoded frame, and swapped if it evidently describes the other
            // axis instead: a swapped FOV makes the focal lengths the frame
            // implies disagree by roughly the square of its aspect ratio, which
            // no real lens does. See [correctFovOrientation].
            float horizontalFov = horizontalFovDegrees;
            float verticalFov = verticalFovDegrees;
            boolean fovChecked = false;

            if (!frames.isEmpty()) {
                Log.i(
                    TAG,
                    "Stitch input: " + frames.size() + " frames, reported FOV "
                        + horizontalFovDegrees + "°x" + verticalFovDegrees + "°, "
                        + "distortion=" + distortionLabel(radialDistortion) + ", "
                        + "pivot=" + pivot
                );
            }

            for (int position = 0; position < frames.size(); position++) {
                checkCancelled();
                SphereFrame frame = frames.get(position);
                report(onProgress, new StitchProgress(StitchStage.Reading, position, frames.size()));

                Mat image = readFrame(frame.file, maxInputDimension);

                // From here the frame is owned by this iteration: a failure
                // before it is handed to `decoded` — the FOV correction, the
                // intrinsics, the placement — must not leak its native buffer.
                // Mat.release is idempotent, so releasing one that
                // `rotateClockwise` already released on its way out is safe.
                try {
                    // A frame must land on the sphere the same way its pose
                    // describes it. The pose is display-upright, so a frame that
                    // decoded in the sensor's native (landscape) orientation —
                    // its EXIF rotation tag missing, or lost when the metadata
                    // rewrite re-encoded the JPEG — has to be turned upright
                    // *here*, or its content paints onto the sphere rotated 90°
                    // against the measured pose and no two frames line up.
                    // `portraitRotationDegrees` is exactly the turn CameraX
                    // would have recorded in the tag.
                    if (image.cols() > image.rows() && portraitRotationDegrees % 180 == 90) {
                        Log.w(
                            TAG,
                            "Frame " + position + " decoded " + image.cols() + "x" + image.rows()
                                + " — transposed against the " + horizontalFov + "°x" + verticalFov
                                + "° FOV; rotating " + portraitRotationDegrees + "° clockwise"
                        );
                        image = rotateClockwise(image, portraitRotationDegrees);
                    }
                    Log.i(
                        TAG,
                        "Frame " + position + " decoded " + image.cols() + "x" + image.rows()
                            + " at pose yaw=" + frame.pose.yawDegrees + "° pitch="
                            + frame.pose.pitchDegrees + "° roll=" + frame.pose.rollDegrees + "°"
                    );
                    if (debugColorFrames) {
                        // Paint the frame a solid colour so the finished pano
                        // shows exactly where each frame was placed — the
                        // placement check.
                        image.setTo(DEBUG_FRAME_COLORS[position % DEBUG_FRAME_COLORS.length]);
                    }
                    if (!fovChecked) {
                        float[] corrected = correctFovOrientation(
                            image.cols(),
                            image.rows(),
                            horizontalFov,
                            verticalFov
                        );
                        if (corrected != null) {
                            Log.w(
                                TAG,
                                "FOV " + horizontalFov + "°x" + verticalFov
                                    + "° does not match the " + image.cols() + "x" + image.rows()
                                    + " frame; using " + corrected[0] + "°x" + corrected[1] + "°"
                            );
                            horizontalFov = corrected[0];
                            verticalFov = corrected[1];
                        }
                        fovChecked = true;
                    }
                    // The lens's unitless coefficients are converted against the
                    // focal length *this* decoded frame implies, so a frame
                    // subsampled to any size still carries the same physical
                    // lens.
                    FrameIntrinsics intrinsics = FrameIntrinsics.forLens(
                        image.cols(),
                        image.rows(),
                        horizontalFov,
                        verticalFov,
                        radialDistortion
                    );
                    if (position == 0) {
                        canvasWidth = canvasWidthFor(
                            image.cols(),
                            horizontalFov,
                            maxOutputWidth,
                            longitudeSpanDegrees
                        );
                    }
                    decoded.add(new DecodedFrame(
                        image,
                        intrinsics,
                        CameraBasis.of(frame.pose)
                    ));
                } catch (Throwable e) {
                    image.release();
                    throw e;
                }
            }
            report(onProgress, new StitchProgress(StitchStage.Reading, frames.size(), frames.size()));
            int canvasHeight = canvasHeightFor(canvasWidth, longitudeSpanDegrees, latitudeSpanDegrees);
            Log.i(
                TAG,
                "Stitch geometry: corrected FOV " + horizontalFov + "°x" + verticalFov + "°, "
                    + "canvas " + canvasWidth + "x" + canvasHeight
                    + " (" + longitudeSpanDegrees + "° x " + latitudeSpanDegrees + "°)"
            );

            checkCancelled();
            // Match overlapping frames and correct the measured poses against the
            // content. Falls back to the sensor poses when nothing matches.
            // The *corrected* angles go in: the pose graph is built by asking
            // which frames overlap, and a transposed field of view answers that
            // question about the wrong axis on every pair.
            final ProgressCallback progressRef = onProgress;
            RefinementResult refinement;
            if (useRefinement) {
                refinement = PoseRefiner.refine(
                    decoded,
                    horizontalFov,
                    verticalFov,
                    pivot.getRatio(),
                    new PoseRefiner.ProgressListener() {
                        public void onProgress(int completed, int total) {
                            report(progressRef, new StitchProgress(StitchStage.Refining, completed, total));
                        }
                    }
                );
            } else {
                // Debug A/B: stitch straight from the measured poses. Refinement
                // is the only step that replaces them, so this isolates whether
                // a bad feature match is moving frames off the sensor's answer.
                ArrayList<CameraBasis> bases = new ArrayList<CameraBasis>(decoded.size());
                for (int i = 0; i < decoded.size(); i++) {
                    bases.add(CameraBasis.fromRotationMatrix(decoded.get(i).sensorBasis.toRotationMatrix()));
                }
                float[] gains = new float[decoded.size()];
                for (int i = 0; i < gains.length; i++) gains[i] = 1f;
                double[] focalScales = new double[decoded.size()];
                for (int i = 0; i < focalScales.length; i++) focalScales[i] = 1.0;
                refinement = new RefinementResult(bases, gains, 0, focalScales);
            }

            if (BuildConfig.DEBUG) {
                StringBuilder poses = new StringBuilder();
                for (int i = 0; i < refinement.bases.size(); i++) {
                    CameraPose p = refinement.bases.get(i).toPose();
                    if (i > 0) poses.append(", ");
                    poses.append("y=").append(Math.round(p.yawDegrees))
                        .append("/p=").append(Math.round(p.pitchDegrees))
                        .append("/r=").append(Math.round(p.rollDegrees));
                }
                StringBuilder focals = new StringBuilder();
                for (int i = 0; i < refinement.focalScales.length; i++) {
                    if (i > 0) focals.append(", ");
                    focals.append(String.format(Locale.US, "%.4f", refinement.focalScales[i]));
                }
                Log.i(
                    TAG,
                    "Refinement matched " + refinement.matchedEdges + " edges; "
                        + "refined poses: " + poses + "; "
                        + "focal scales: " + focals
                );
            }

            ArrayList<PreparedFrame> prepared = new ArrayList<PreparedFrame>(decoded.size());
            for (int index = 0; index < decoded.size(); index++) {
                checkCancelled();
                CameraBasis basis = refinement.bases.get(index);
                // The focal refinement may have decided the reported field of
                // view was off; the scale is applied to the frame's intrinsics
                // (and the radial model re-normalised against the new focal
                // length) so the footprint and the renderer both see the lens
                // the feature matches measured.
                FrameIntrinsics intrinsics = decoded.get(index).intrinsics.scaledBy(
                    refinement.focalScales[index]
                );
                prepared.add(new PreparedFrame(
                    decoded.get(index).image,
                    basis,
                    intrinsics,
                    FrameFootprint.compute(
                        basis,
                        intrinsics,
                        canvasWidth,
                        canvasHeight,
                        2,
                        pivot.getRatio(),
                        longitudeSpanDegrees,
                        centerLongitudeDegrees,
                        latitudeSpanDegrees,
                        centerLatitudeDegrees
                    )
                ));
            }

            checkCancelled();
            // Seam carving: decide which frame paints each pixel, then render
            // with the wide cross-fade replaced by a near-hard cut that fades
            // only a few pixels across it. Without it the render falls back to
            // the multi-band blend.
            SeamWeights seams = null;
            if (useSeams) {
                report(onProgress, new StitchProgress(StitchStage.Seaming));
                seams = SeamFinder.computeSeams(
                    prepared,
                    canvasWidth,
                    canvasHeight,
                    refinement.gains,
                    pivot.getRatio(),
                    longitudeSpanDegrees,
                    centerLongitudeDegrees,
                    latitudeSpanDegrees,
                    centerLatitudeDegrees,
                    new SeamFinder.ProgressListener() {
                        public void onProgress(int completed, int total) {
                            report(progressRef, new StitchProgress(StitchStage.Seaming, completed, total));
                        }
                    },
                    new SeamFinder.CancelCheck() {
                        public void checkCancelled() {
                            PhotoSphereStitcher.checkCancelled();
                        }
                    }
                );
            }
            if (BuildConfig.DEBUG && seams != null) {
                Log.i(
                    TAG,
                    "Seam carving: " + seams.gridWidth + "x" + seams.gridHeight + " grid at scale "
                        + seams.scale + " over " + prepared.size() + " frames"
                );
            }

            report(onProgress, new StitchProgress(StitchStage.Stitching));
            EquirectangularRenderer.Rendered rendered = EquirectangularRenderer.render(
                prepared,
                canvasWidth,
                canvasHeight,
                refinement.gains,
                seams,
                pivot.getRatio(),
                longitudeSpanDegrees,
                centerLongitudeDegrees,
                latitudeSpanDegrees,
                centerLatitudeDegrees,
                new EquirectangularRenderer.ProgressListener() {
                    public void onProgress(int completed, int total) {
                        report(progressRef, new StitchProgress(StitchStage.Stitching, completed, total));
                    }
                },
                new EquirectangularRenderer.CancelCheck() {
                    public void checkCancelled() {
                        PhotoSphereStitcher.checkCancelled();
                    }
                }
            );

            try {
                if (rendered.coverage <= 0f) {
                    throw new StitchException(
                        StitchStatus.EmptyResult,
                        "The render reached none of the sphere"
                    );
                }
                if (rendered.coverage < MIN_COVERAGE) {
                    throw new StitchException(
                        StitchStatus.NeedMoreImages,
                        "Only " + Math.round(rendered.coverage * 100) + "% of the sphere was covered"
                    );
                }

                checkCancelled();
                report(onProgress, new StitchProgress(StitchStage.Projecting));
                applyUnsharpMask(rendered.canvas, unsharpAmount);
                return toBitmap(rendered.canvas);
            } finally {
                rendered.canvas.release();
            }
        } catch (StitchCancelledException e) {
            throw e;
        } catch (StitchException e) {
            Log.w(TAG, "Stitch failed: " + e.status + " (" + e.status.code + ")", e);
            throw e;
        } catch (OutOfMemoryError e) {
            // Recoverable here: the frames are released in the finally below, and
            // the user can retry at a lower input resolution.
            Log.e(TAG, "Out of memory stitching " + frames.size() + " frames", e);
            throw new StitchException(StitchStatus.OutOfMemory, "Not enough memory to stitch", e);
        } catch (Exception e) {
            // Native OpenCV failures arrive as CvException, a RuntimeException.
            Log.e(TAG, "Stitch failed", e);
            throw new StitchException(
                StitchStatus.Unknown,
                e.getMessage() != null ? e.getMessage() : "Stitch failed",
                e
            );
        } finally {
            // `prepared` and `decoded` share the same Mats; releasing the decoded
            // set covers every path, including a failure mid-decode or mid-refine.
            for (int i = 0; i < decoded.size(); i++) decoded.get(i).image.release();
        }
    }

    /**
     * Canvas width that matches the detail in the frames.
     *
     * Rendering wider than this only interpolates: the frames hold
     * {@code frameWidthPx / fov} pixels per degree and the sphere is
     * {@code longitudeSpanDegrees} around (360° for a full sphere or a ring capture).
     * The width is forced even so a full-sphere canvas has an integer height.
     */
    public static int canvasWidthFor(int frameWidthPx, float horizontalFovDegrees, int maxOutputWidth) {
        return canvasWidthFor(frameWidthPx, horizontalFovDegrees, maxOutputWidth, 360f);
    }

    public static int canvasWidthFor(
        int frameWidthPx,
        float horizontalFovDegrees,
        int maxOutputWidth,
        float longitudeSpanDegrees
    ) {
        int ideal = (int) Math.round(longitudeSpanDegrees * frameWidthPx / horizontalFovDegrees);
        int width = Math.max(Math.min(ideal, maxOutputWidth), MIN_OUTPUT_WIDTH);
        return width - (width % 2);
    }

    /**
     * Canvas height that keeps the pixels square for a canvas spanning
     * {@code longitudeSpanDegrees} of longitude and {@code latitudeSpanDegrees} of latitude.
     *
     * A full sphere is half as tall as wide (180° of latitude across 360° of
     * longitude); a ring capture is only as tall as its band is wide in angular
     * terms, so a 360° × 72° ring comes out at exactly one fifth of the width.
     */
    public static int canvasHeightFor(
        int canvasWidth,
        float longitudeSpanDegrees,
        float latitudeSpanDegrees
    ) {
        int height = (int) Math.round(canvasWidth * latitudeSpanDegrees / longitudeSpanDegrees);
        return Math.max(height, 1);
    }

    /**
     * Checks that the two field-of-view angles describe the axes of a decoded
     * {@code widthPx}x{@code heightPx} frame, swapping them when they evidently do not.
     *
     * A lens has square pixels, so the focal length the horizontal field of
     * view implies for the frame's width must match the one the vertical field
     * of view implies for its height. If the angles arrived transposed — the
     * axes of the sensor rather than of the upright frame — the two implied
     * focal lengths differ by roughly the square of the aspect ratio (0.56 for
     * a 4:3 frame), which is far beyond any field-of-view estimation error. The
     * boundary is drawn at 1 ± 30%: generous enough never to trip on a
     * loosely-described lens, unambiguous enough that a genuine swap is caught.
     *
     * Returns {@code [horizontal, vertical]} or null when the angles already match the
     * frame.
     */
    public static float[] correctFovOrientation(
        int widthPx,
        int heightPx,
        float horizontalFovDegrees,
        float verticalFovDegrees
    ) {
        if (widthPx <= 0 || heightPx <= 0) return null;
        double horizontalTan = Math.tan(Math.toRadians(horizontalFovDegrees / 2.0));
        double verticalTan = Math.tan(Math.toRadians(verticalFovDegrees / 2.0));
        if (horizontalTan <= 0.0 || verticalTan <= 0.0) return null;
        double horizontalFocal = widthPx / 2.0 / horizontalTan;
        double verticalFocal = heightPx / 2.0 / verticalTan;
        double ratio = horizontalFocal >= verticalFocal
            ? verticalFocal / horizontalFocal
            : horizontalFocal / verticalFocal;
        if (ratio >= 0.7) return null;
        return new float[] {verticalFovDegrees, horizontalFovDegrees};
    }

    /**
     * Power-of-two subsampling factor that brings the longer edge to {@code maxDimension}
     * or below.
     *
     * {@code BitmapFactory} only honours powers of two, so this returns one directly
     * rather than letting it round the value down and hand back something larger
     * than asked for.
     */
    public static int sampleSizeFor(int width, int height, int maxDimension) {
        if (maxDimension <= 0) {
            throw new IllegalArgumentException("maxDimension must be positive");
        }
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > maxDimension) sample *= 2;
        return sample;
    }

    /**
     * Decodes one frame into the 8-bit 3-channel matrix the renderer samples.
     *
     * Frames are decoded subsampled and rotated upright first. The rotation
     * matters more than it looks: CameraX records device rotation in EXIF rather
     * than rotating pixels, so a run that crossed a screen rotation would
     * otherwise hand the renderer a mix of portrait and landscape frames while
     * the field of view describes only one of them.
     */
    private static Mat readFrame(File file, int maxDimension) throws StitchException {
        Bitmap bitmap = decodeUpright(file, maxDimension);
        if (bitmap == null) {
            throw new StitchException(
                StitchStatus.UnreadableInput,
                "Could not decode " + file.getName()
            );
        }

        Mat rgba = new Mat();
        Mat rgb = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
        } catch (Throwable e) {
            rgb.release();
            throw e;
        } finally {
            rgba.release();
            bitmap.recycle();
        }
        return rgb;
    }

    /**
     * Rotates {@code source} by {@code degrees} clockwise in place, releasing the original.
     *
     * The fallback for a frame whose EXIF rotation was lost: {@code degrees} is the
     * turn CameraX would have recorded, so the result is exactly what the
     * frame's own tag should have produced. 180° is a plain flip; 90° and 270°
     * swap the axes, which is the case this pipeline actually meets.
     */
    private static Mat rotateClockwise(Mat source, int degrees) {
        Mat rotated = new Mat();
        try {
            int normalized = ((degrees % 360) + 360) % 360;
            switch (normalized) {
                case 90:
                    Core.rotate(source, rotated, Core.ROTATE_90_CLOCKWISE);
                    break;
                case 180:
                    Core.rotate(source, rotated, Core.ROTATE_180);
                    break;
                case 270:
                    Core.rotate(source, rotated, Core.ROTATE_90_COUNTERCLOCKWISE);
                    break;
                default:
                    return source;
            }
        } catch (Throwable e) {
            rotated.release();
            throw e;
        } finally {
            source.release();
        }
        return rotated;
    }

    /** Copies the finished canvas out into a bitmap the UI can show. */
    private static Bitmap toBitmap(Mat canvas) {
        Bitmap bitmap = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(canvas, bitmap);
        return bitmap;
    }

    /**
     * Applies a masked unsharp mask to the finished canvas, in place.
     *
     * {@code out = src + amount·(src − blur)} lifts the edge contrast that reads as
     * sharpness. The mask restricts it to pixels that were actually shot: the
     * black bands where the sphere was never captured must not grow a bright
     * rim, and near-black pixels (a night shot) are left alone so noise is not
     * sharpened along with the edges.
     *
     * <b>Memory.</b> Done whole, this is the peak of the entire pipeline: a blur, a
     * sharpened copy, a greyscale and a mask all at canvas size, which on the
     * 6144-wide profile is around 200 MB of native buffers on top of the canvas
     * itself. Working in bands holds one untouched copy of the canvas and four
     * band-sized temporaries instead. The copy is what keeps the result
     * identical to the whole-canvas version: every band reads its blur input
     * from the original pixels, so a band's halo cannot pick up the sharpening
     * its neighbour just wrote and sharpen it a second time.
     */
    private static void applyUnsharpMask(Mat canvas, float amount) {
        if (amount <= 0f) return;
        int height = canvas.rows();
        int width = canvas.cols();
        if (height <= 0 || width <= 0) return;

        Mat source = canvas.clone();
        try {
            int bandTop = 0;
            while (bandTop < height) {
                int bandHeight = Math.min(UNSHARP_BAND_HEIGHT, height - bandTop);
                // The band plus the context the Gaussian needs, clipped to the
                // canvas at the top and bottom rows.
                int haloTop = Math.max(0, bandTop - UNSHARP_HALO);
                int haloBottom = Math.min(height, bandTop + bandHeight + UNSHARP_HALO);
                sharpenBand(
                    source,
                    canvas,
                    width,
                    haloTop,
                    haloBottom - haloTop,
                    bandTop,
                    bandHeight,
                    amount
                );
                bandTop += bandHeight;
            }
        } finally {
            source.release();
        }
    }

    /** Sharpens one band of {@code canvas}, reading its blur input from {@code source}. */
    private static void sharpenBand(
        Mat source,
        Mat canvas,
        int width,
        int haloTop,
        int haloHeight,
        int bandTop,
        int bandHeight,
        float amount
    ) {
        Mat halo = source.submat(new Rect(0, haloTop, width, haloHeight));
        Mat blurred = new Mat();
        Mat sharpenedHalo = new Mat();
        Mat gray = new Mat();
        Mat mask = new Mat();
        try {
            Imgproc.GaussianBlur(halo, blurred, new Size(0.0, 0.0), UNSHARP_RADIUS);
            Core.addWeighted(halo, 1.0 + amount, blurred, -(double) amount, 0.0, sharpenedHalo);

            // Only the band itself is written back; the halo was context.
            Rect bandInHalo = new Rect(0, bandTop - haloTop, width, bandHeight);
            Mat sharpenedBand = sharpenedHalo.submat(bandInHalo);
            Mat sourceBand = source.submat(new Rect(0, bandTop, width, bandHeight));
            Mat target = canvas.submat(new Rect(0, bandTop, width, bandHeight));
            try {
                Imgproc.cvtColor(sourceBand, gray, Imgproc.COLOR_RGB2GRAY);
                Core.compare(gray, new Scalar(UNSHARP_CONTENT_THRESHOLD), mask, Core.CMP_GT);
                sharpenedBand.copyTo(target, mask);
            } finally {
                sharpenedBand.release();
                sourceBand.release();
                target.release();
            }
        } finally {
            halo.release();
            blurred.release();
            sharpenedHalo.release();
            gray.release();
            mask.release();
        }
    }

    /** Decodes {@code file} no larger than {@code maxDimension}, with EXIF rotation applied. */
    private static Bitmap decodeUpright(File file, int maxDimension) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension);
        // Utils.bitmapToMat accepts ARGB_8888 and RGB_565 only.
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
        if (decoded == null) return null;
        return applyExifRotation(decoded, file);
    }

    /** Rotates {@code bitmap} to match the orientation EXIF claims for {@code file}. */
    private static Bitmap applyExifRotation(Bitmap bitmap, File file) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            );
        } catch (Exception ignored) {
            orientation = ExifInterface.ORIENTATION_NORMAL;
        }

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270f);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270f);
                matrix.postScale(-1f, 1f);
                break;
            default:
                return bitmap;
        }

        Bitmap rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
        );
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private static void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new StitchCancelledException();
        }
    }

    private static void report(ProgressCallback onProgress, StitchProgress progress) {
        if (onProgress != null) onProgress.onProgress(progress);
    }

    private static String distortionLabel(RadialDistortion radialDistortion) {
        if (radialDistortion == null || radialDistortion.getCoefficients() == null) return "none";
        double[] coefficients = radialDistortion.getCoefficients();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < coefficients.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(coefficients[i]);
        }
        return builder.toString();
    }
}
