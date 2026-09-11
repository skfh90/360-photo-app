package com.n30dyn4m1c.photosphere.storage;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.camera.core.ImageCapture;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata;
import com.n30dyn4m1c.photosphere.metadata.GPanoXmpInjector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Where captured frames go.
 *
 * <p>Three destinations, for three different kinds of image:
 *
 * <ul>
 *   <li><b>Cache sessions</b> ({@link #sessionDirectory}, {@link #newTempFrameOptions}) hold the raw
 *       frames of a capture run. They are intermediate data, they are large, and
 *       they are cleared when the next run starts.
 *   <li><b>The sphere cache</b> ({@link #writeStitchedSphere}) holds the one finished
 *       equirectangular JPEG a run produces, complete with its GPano metadata. It
 *       lives there while the user looks at it on the result screen, and goes no
 *       further unless they ask.
 *   <li><b>MediaStore</b> is for images the user keeps, and is
 *       {@link MediaExporter}'s job — "Export to gallery" on the result screen is the only
 *       thing that puts a sphere in front of the rest of the system.
 * </ul>
 */
public final class SphereImageStore {

    private static final String TAG = "SphereImageStore";
    private static final String ALBUM = "PhotoSphere";
    private static final String MIME_TYPE = "image/jpeg";

    /** Cache subdirectory holding one directory per capture session. */
    private static final String SESSIONS_DIRECTORY = "sphere_sessions";

    /**
     * Cache subdirectory holding finished spheres awaiting a decision.
     *
     * <p>Shared out through a {@code FileProvider}, so it is named in
     * {@code res/xml/file_paths.xml} too — the two must stay in step or sharing fails
     * with an {@code IllegalArgumentException} at the moment the user taps Share.
     */
    private static final String SPHERES_DIRECTORY = "spheres";

    /**
     * Encode quality for the finished sphere. High, because this is the only
     * image of the run that is kept and it has already been through one
     * generation of JPEG on the way in.
     */
    private static final int SPHERE_JPEG_QUALITY = 95;

    private SphereImageStore() {
    }

    /** Output options plus the display name they will produce. */
    public static final class FrameOutputRequest {
        public final ImageCapture.OutputFileOptions outputOptions;
        public final String displayName;

        public FrameOutputRequest(ImageCapture.OutputFileOptions outputOptions, String displayName) {
            this.outputOptions = outputOptions;
            this.displayName = displayName;
        }

        public ImageCapture.OutputFileOptions getOutputOptions() {
            return outputOptions;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /** Output options plus the cache file they will write to. */
    public static final class TempFrameRequest {
        public final ImageCapture.OutputFileOptions outputOptions;
        public final File file;

        public TempFrameRequest(ImageCapture.OutputFileOptions outputOptions, File file) {
            this.outputOptions = outputOptions;
            this.file = file;
        }

        public ImageCapture.OutputFileOptions getOutputOptions() {
            return outputOptions;
        }

        public File getFile() {
            return file;
        }
    }

    /**
     * A stitched sphere on its way to the user: a GPano-tagged JPEG in the cache,
     * and the dimensions written into that metadata.
     *
     * <p>Held as a file rather than a {@code Bitmap} because it is what both destinations
     * want — {@link MediaExporter} copies its bytes into the gallery and the share
     * sheet hands out a URI to it — and because a 4096×2048 bitmap is 32 MB of
     * heap to carry across a screen change for no reason.
     */
    public static final class StitchedSphere {
        public final File file;
        public final int width;
        public final int height;
        /** Debug-only text describing the frames and geometry that made it. */
        public final String diagnostics;
        /**
         * Where this JPEG sits on the full sphere. The in-app 360 viewer uses
         * it to sample the image; a ring capture is a horizontal band, not a
         * 2:1 canvas with black wedges.
         */
        public final GPanoMetadata gpano;

        public StitchedSphere(File file, int width, int height) {
            this(file, width, height, null, GPanoMetadata.forFullPano(width, height));
        }

        public StitchedSphere(File file, int width, int height, String diagnostics) {
            this(file, width, height, diagnostics, GPanoMetadata.forFullPano(width, height));
        }

        public StitchedSphere(
                File file, int width, int height, String diagnostics, GPanoMetadata gpano) {
            this.file = file;
            this.width = width;
            this.height = height;
            this.diagnostics = diagnostics;
            this.gpano = gpano;
        }

        public File getFile() {
            return file;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public String getDiagnostics() {
            return diagnostics;
        }

        public GPanoMetadata getGpano() {
            return gpano;
        }
    }

    /** Identifier for one run of guided capture. Sorts chronologically. */
    public static String newSessionId() {
        // Millisecond precision: the manager is recreated on configuration
        // changes, and two sessions born in the same second would share a
        // directory and silently mix their frames.
        return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
    }

    /**
     * Directory holding the frames of {@code sessionId}, created if needed.
     *
     * <p>Guided capture writes to the cache rather than the gallery: these frames
     * are stitcher input, and a user who asked for one sphere has not asked for
     * forty-four photos in their camera roll. Only the finished equirectangular
     * image belongs in MediaStore.
     *
     * <p>Touches the filesystem — call off the main thread.
     */
    public static File sessionDirectory(Context context, String sessionId) {
        File directory = new File(new File(context.getCacheDir(), SESSIONS_DIRECTORY), sessionId);
        directory.mkdirs();
        return directory;
    }

    /** Path frame {@code index} of a session occupies. Zero-padded so it sorts. */
    public static File frameFile(File directory, int index) {
        return new File(directory, String.format(Locale.US, "frame_%03d.jpg", index));
    }

    /** Where frame {@code index} of a session should be written. */
    public static TempFrameRequest newTempFrameOptions(File directory, int index) {
        File file = frameFile(directory, index);
        return new TempFrameRequest(
                new ImageCapture.OutputFileOptions.Builder(file).build(),
                file);
    }

    /**
     * Path one candidate of frame {@code index}'s burst occupies.
     *
     * <p>Burst shots sit alongside the canonical {@code frame_%03d.jpg} name as
     * {@code frame_%03d_t{k}.jpg} until a winner is chosen and promoted — the plain
     * name is left for the frame that is actually kept, so the session holds
     * exactly one file per index.
     */
    public static File burstFrameFile(File directory, int index, int burst) {
        return new File(directory, String.format(Locale.US, "frame_%03d_t%d.jpg", index, burst));
    }

    /** Prefix shared by every candidate of frame {@code index}'s burst. */
    public static String burstPrefix(int index) {
        return String.format(Locale.US, "frame_%03d_t", index);
    }

    /**
     * Removes one session's directory and everything in it.
     *
     * <p>Touches the filesystem — call off the main thread.
     */
    public static void deleteSession(Context context, String sessionId) {
        File session = new File(new File(context.getCacheDir(), SESSIONS_DIRECTORY), sessionId);
        if (session.exists() && !deleteRecursively(session)) {
            Log.w(TAG, "Could not clear session " + sessionId);
        }
    }

    /**
     * Deletes every session directory except {@code keepSessionId}.
     *
     * <p>A sphere's worth of full-resolution JPEGs is on the order of a hundred
     * megabytes. The cache is reclaimable by the system, but only under
     * pressure, so an abandoned run is cleared at the start of the next one
     * instead of being left for Android to notice.
     *
     * <p>Touches the filesystem — call off the main thread.
     */
    public static void pruneSessions(Context context, String keepSessionId) {
        File root = new File(context.getCacheDir(), SESSIONS_DIRECTORY);
        File[] sessions = root.listFiles();
        if (sessions == null) {
            return;
        }
        for (int i = 0; i < sessions.length; i++) {
            File session = sessions[i];
            if (!session.getName().equals(keepSessionId)) {
                if (!deleteRecursively(session)) {
                    Log.w(TAG, "Could not clear stale session " + session.getName());
                }
            }
        }
    }

    /**
     * Records the device attitude a frame was shot at, in EXIF.
     *
     * <p>The stitcher gets a starting guess at each frame's place on the sphere
     * from this, which is the difference between searching for correspondences
     * and merely confirming them. Written as a {@code UserComment} because EXIF has no
     * standard tag for camera attitude, in a fixed {@code key=value;} form so it can be
     * parsed back without ambiguity.
     *
     * <p>Note: this is only for frames an external tool inspects. The stitch reads
     * the attitude out of the in-memory {@link BufferedFrame}
     * instead, and it must never be applied through {@link ExifInterface#saveAttributes} —
     * that rewrite re-encodes the whole JPEG and has dropped CameraX's
     * {@code ORIENTATION} tag on some files, which is what {@code decodeUpright} rotates
     * the frame by. The rotation tag is the one thing the file cannot lose.
     */
    public static void stampCaptureOrientation(
            File file,
            int index,
            float yawDegrees,
            float pitchDegrees,
            float rollDegrees) {
        try {
            ExifInterface exif = new ExifInterface(file);
            // Read the rotation CameraX recorded first and write it back
            // explicitly: saveAttributes() must not silently reset it to
            // NORMAL, or every frame of the session decodes landscape.
            int rotation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(rotation));
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere");
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, ALBUM + " frame " + index);
            exif.setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    String.format(
                            Locale.US,
                            "index=%d;yaw=%.3f;pitch=%.3f;roll=%.3f",
                            index,
                            yawDegrees,
                            pitchDegrees,
                            rollDegrees));
            exif.saveAttributes();
        } catch (Exception e) {
            // Metadata is a nice-to-have; never lose the frame over it.
            Log.w(TAG, "Could not write EXIF for " + file.getName(), e);
        }
    }

    public static FrameOutputRequest newFrameOutputOptions(Context context, int index) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String displayName = String.format(Locale.US, "sphere_%s_%03d.jpg", timestamp, index);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage: the system picks the real path from this hint.
            // IS_PENDING is intentionally not set here — CameraX's ImageSaver
            // raises and clears it around the write on its own.
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + ALBUM);
        }

        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions
                .Builder(
                        context.getContentResolver(),
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values)
                .build();

        return new FrameOutputRequest(outputOptions, displayName);
    }

    /**
     * Encodes a finished equirectangular sphere into the cache as a 360 photo.
     *
     * <p>This is the handover between stitching and the result screen: the pixels
     * come off the heap into a JPEG, the JPEG is tagged, and everything
     * downstream — preview, gallery export, share sheet — works from that one
     * file.
     *
     * <p>{@code gpano} is the metadata a viewer reads to treat the file as a pano. It
     * defaults to a full sphere ({@link GPanoMetadata#forFullPano}); a ring capture
     * passes a region ({@link GPanoMetadata#forSphereRegion}) so the band the frames
     * covered is what viewers render.
     *
     * <p>The order of the two metadata passes matters. EXIF goes on first, because
     * {@link ExifInterface#saveAttributes} rewrites the whole JPEG and makes no promise
     * about carrying unrecognised segments across; GPano goes on second, so the
     * marker that makes viewers treat this as a sphere is written by the last
     * thing to touch the file. Neither pass re-encodes the image.
     *
     * <p>The new sphere is built up under a temporary name and moved onto its final
     * name only once it is complete. The previous sphere is cleared <em>after</em> that
     * swap, so a failed encode or metadata pass never destroys the last good
     * output — the user keeps the older sphere instead of being left with
     * nothing. Only one sphere is ever in play, and each is several megabytes.
     *
     * <p>Blocking I/O and a full-size compress — call off the main thread. Throws
     * {@link IOException} if the encode fails.
     */
    public static StitchedSphere writeStitchedSphere(Context context, Bitmap bitmap)
            throws IOException {
        return writeStitchedSphere(
                context,
                bitmap,
                SPHERE_JPEG_QUALITY,
                GPanoMetadata.forFullPano(bitmap.getWidth(), bitmap.getHeight()));
    }

    public static StitchedSphere writeStitchedSphere(Context context, Bitmap bitmap, int quality)
            throws IOException {
        return writeStitchedSphere(
                context,
                bitmap,
                quality,
                GPanoMetadata.forFullPano(bitmap.getWidth(), bitmap.getHeight()));
    }

    public static StitchedSphere writeStitchedSphere(
            Context context, Bitmap bitmap, GPanoMetadata gpano) throws IOException {
        return writeStitchedSphere(context, bitmap, SPHERE_JPEG_QUALITY, gpano);
    }

    public static StitchedSphere writeStitchedSphere(
            Context context,
            Bitmap bitmap,
            int quality,
            GPanoMetadata gpano) throws IOException {
        File directory = spheresDirectory(context);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(directory, String.format(Locale.US, "sphere_%s.jpg", timestamp));
        File temp = new File(directory, file.getName() + ".tmp");

        try {
            FileOutputStream out = new FileOutputStream(temp);
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    throw new IOException("Could not encode the stitched sphere");
                }
            } finally {
                out.close();
            }
            stampSphereDescription(temp);
            GPanoXmpInjector.Result injected = GPanoXmpInjector.inject(temp, gpano);
            if (injected.isFailure()) {
                // Recoverable: the file is a valid 2:1 JPEG either way, it
                // will simply open flat instead of as a sphere.
                Log.w(TAG, "Could not write GPano metadata for " + file.getName(),
                        injected.getError());
            }
            // The new sphere is complete; move it into place (atomically — the
            // name is fresh, but REPLACE_EXISTING also absorbs a same-second
            // collision) and only then drop the previous one.
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Log.w(TAG, "Could not finalise " + file.getName(), e);
                throw e;
            }
        } catch (IOException e) {
            temp.delete();
            throw e;
        } catch (RuntimeException e) {
            temp.delete();
            throw e;
        }

        File[] staleFiles = directory.listFiles();
        if (staleFiles != null) {
            for (int i = 0; i < staleFiles.length; i++) {
                File stale = staleFiles[i];
                if (!stale.equals(file) && !stale.delete()) {
                    Log.w(TAG, "Could not clear stale sphere " + stale.getName());
                }
            }
        }

        return new StitchedSphere(file, bitmap.getWidth(), bitmap.getHeight(), null, gpano);
    }

    /** Directory holding the finished sphere of the current run, created if needed. */
    private static File spheresDirectory(Context context) {
        File directory = new File(context.getCacheDir(), SPHERES_DIRECTORY);
        directory.mkdirs();
        return directory;
    }

    /**
     * A URI another app can read {@code file} through.
     *
     * <p>The share sheet cannot be handed a {@code file://} URI — since API 24 that
     * throws {@code FileUriExposedException} — so the cached sphere goes out through
     * the app's {@code FileProvider}. The authority is derived from the running
     * package rather than hardcoded, because the debug build carries a
     * {@code .debug} suffix.
     */
    public static Uri shareUri(Context context, File file) {
        return FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", file);
    }

    /** Discards a cached sphere the user has finished with. */
    public static void deleteCachedSphere(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete cached sphere " + file.getName());
        }
    }

    /** Marks a finished sphere as this app's output, in EXIF. */
    private static void stampSphereDescription(File file) {
        try {
            ExifInterface exif = new ExifInterface(file);
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere");
            exif.setAttribute(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    ALBUM + " equirectangular panorama");
            exif.saveAttributes();
        } catch (Exception e) {
            // Metadata is a nice-to-have; never lose the sphere over it.
            Log.w(TAG, "Could not write EXIF for " + file.getName(), e);
        }
    }

    /**
     * Stamps identifying EXIF tags onto a saved frame.
     *
     * <p>CameraX already writes orientation and the capture timestamp; this adds the
     * album/sequence markers the stitcher uses to group a run of frames.
     *
     * <p>Note: the "this is a 360 photo" marker that Google Photos and other viewers
     * look for is XMP GPano, not EXIF, and {@link ExifInterface} cannot write XMP.
     * That marker belongs on the stitched sphere rather than on a frame, and
     * {@link GPanoXmpInjector} is what writes it.
     */
    public static void stampSphereMetadata(Context context, Uri uri, int index) {
        try {
            android.os.ParcelFileDescriptor descriptor =
                    context.getContentResolver().openFileDescriptor(uri, "rw");
            if (descriptor != null) {
                try {
                    ExifInterface exif = new ExifInterface(descriptor.getFileDescriptor());
                    exif.setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere");
                    exif.setAttribute(
                            ExifInterface.TAG_IMAGE_DESCRIPTION, ALBUM + " frame " + index);
                    exif.saveAttributes();
                } finally {
                    descriptor.close();
                }
            }
        } catch (Exception e) {
            // Metadata is a nice-to-have; never lose the frame over it.
            Log.w(TAG, "Could not write EXIF for " + uri, e);
        }
    }

    /** Reads back the orientation CameraX recorded for a frame. */
    public static int readOrientation(Context context, Uri uri) {
        try {
            InputStream stream = context.getContentResolver().openInputStream(uri);
            if (stream == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
            try {
                return new ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            } finally {
                stream.close();
            }
        } catch (Exception e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    if (!deleteRecursively(children[i])) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }
}
