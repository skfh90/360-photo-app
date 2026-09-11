package com.n30dyn4m1c.photosphere.storage;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Publishes a finished 360 photo to the device's public gallery.
 *
 * <p>The stitched sphere lives in the app's cache until the user asks to keep it —
 * this is the step that asks. It writes into {@code Pictures/360Panoramas}, so the
 * spheres land in their own album rather than scattered through the camera roll,
 * and every gallery app, backup service and share target on the device can see
 * them.
 *
 * <p><b>The JPEG is copied byte for byte.</b> It is not decoded, re-compressed or
 * re-encoded, which is what keeps {@link com.n30dyn4m1c.photosphere.metadata.GPanoXmpInjector}'s
 * XMP packet intact — a re-encode would drop the metadata and the gallery would
 * show a wide flat picture instead of a pannable sphere.
 *
 * <h3>Two paths, one album</h3>
 *
 * <p>From API 29 the insert is made <b>pending</b> ({@code IS_PENDING = 1}), the pixels are
 * streamed into the URI MediaStore hands back, and the row is published
 * ({@code IS_PENDING = 0}) only once the copy has finished. Nothing half-written is
 * ever visible in the gallery, and the app needs no storage permission for it.
 *
 * <p>On API 26–28 there is no {@code RELATIVE_PATH} and no pending flag: the file is
 * written into the public Pictures directory directly and the row points at it
 * with {@code DATA}. That path needs {@code WRITE_EXTERNAL_STORAGE}, which
 * {@code MainActivity.REQUIRED_PERMISSIONS} requests on exactly those versions.
 */
public final class MediaExporter {

    private static final String TAG = "MediaExporter";

    /** Album the spheres are filed under, inside the public Pictures directory. */
    public static final String ALBUM = "360Panoramas";

    /**
     * The album as MediaStore's {@code RELATIVE_PATH} wants it: relative to the shared
     * storage root, with no leading slash.
     */
    public static final String RELATIVE_PATH = "Pictures/" + ALBUM;

    private static final String MIME_TYPE = "image/jpeg";

    private static final String FILE_NAME_PREFIX = "panorama";

    private MediaExporter() {
    }

    /** A sphere as it now exists in the gallery. */
    public static final class ExportedPanorama {
        /** MediaStore row for the published image. Shareable, openable, deletable. */
        public final Uri uri;
        /** File name it was filed under, which may differ from the one requested. */
        public final String displayName;
        /** Album path it landed in, for telling the user where to look. */
        public final String relativePath;

        public ExportedPanorama(Uri uri, String displayName) {
            this(uri, displayName, RELATIVE_PATH);
        }

        public ExportedPanorama(Uri uri, String displayName, String relativePath) {
            this.uri = uri;
            this.displayName = displayName;
            this.relativePath = relativePath;
        }

        public Uri getUri() {
            return uri;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getRelativePath() {
            return relativePath;
        }
    }

    /**
     * Outcome of a blocking {@link #export}: the published panorama on success,
     * or the failure that left the gallery unchanged.
     */
    public static final class Result {
        private final ExportedPanorama panorama;
        private final Throwable error;

        private Result(ExportedPanorama panorama, Throwable error) {
            this.panorama = panorama;
            this.error = error;
        }

        public static Result success(ExportedPanorama panorama) {
            return new Result(panorama, null);
        }

        public static Result failure(Throwable error) {
            return new Result(null, error);
        }

        public boolean isSuccess() {
            return error == null;
        }

        public boolean isFailure() {
            return error != null;
        }

        public ExportedPanorama getPanorama() {
            return panorama;
        }

        public Throwable getError() {
            return error;
        }
    }

    /** A gallery file name for a sphere exported now. Sorts chronologically. */
    public static String newDisplayName() {
        return newDisplayName(new Date());
    }

    public static String newDisplayName(Date timestamp) {
        return String.format(
                Locale.US,
                "%s_%s.jpg",
                FILE_NAME_PREFIX,
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(timestamp));
    }

    /**
     * Copies {@code source} into {@code Pictures/360Panoramas}.
     *
     * <p>{@code width} and {@code height} are stored on the row so gallery apps can lay the
     * image out without decoding it first; pass the sphere's real dimensions or
     * null.
     *
     * <p>Blocking I/O — call off the main thread. Failures come back as a failed
     * {@link Result} — a gallery that refuses the write is something to tell the
     * user about, not to crash over.
     */
    public static Result export(Context context, File source) {
        return export(context, source, newDisplayName(), null, null);
    }

    public static Result export(Context context, File source, String displayName) {
        return export(context, source, displayName, null, null);
    }

    public static Result export(
            Context context,
            File source,
            Integer width,
            Integer height) {
        return export(context, source, newDisplayName(), width, height);
    }

    public static Result export(
            Context context,
            File source,
            String displayName,
            Integer width,
            Integer height) {
        try {
            if (!source.isFile()) {
                throw new IOException(source.getName() + " is not there to export");
            }
            ExportedPanorama panorama;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                panorama = exportPending(context, source, displayName, width, height);
            } else {
                panorama = exportLegacy(context, source, displayName, width, height);
            }
            return Result.success(panorama);
        } catch (Exception error) {
            Log.e(TAG, "Could not export " + source.getName() + " to " + RELATIVE_PATH, error);
            return Result.failure(error);
        }
    }

    /** API 29+: insert pending, stream the bytes in, then publish. */
    private static ExportedPanorama exportPending(
            Context context,
            File source,
            String displayName,
            Integer width,
            Integer height) throws IOException {
        android.content.ContentResolver resolver = context.getContentResolver();
        ContentValues values = baseValues(displayName, width, height);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore would not accept " + displayName);
        }

        try {
            OutputStream sink = resolver.openOutputStream(uri);
            if (sink == null) {
                throw new IOException("Could not open " + uri + " for writing");
            }
            // A straight byte copy: no decode, no re-encode, so the GPano XMP
            // and every EXIF tag arrive in the gallery exactly as written.
            InputStream bytes = new FileInputStream(source);
            try {
                try {
                    copy(bytes, sink);
                } finally {
                    sink.close();
                }
            } finally {
                bytes.close();
            }
        } catch (Exception e) {
            // A pending row nobody publishes is invisible but not free; drop it
            // rather than leaving a zero-byte entry behind.
            try {
                resolver.delete(uri, null, null);
            } catch (Exception ignored) {
                // Best-effort cleanup of the pending row.
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException(e.getMessage(), e);
        }

        ContentValues published = new ContentValues();
        published.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, published, null, null);

        return new ExportedPanorama(uri, displayName);
    }

    /**
     * API 26–28: write the file into the public album, then index it.
     *
     * <p>Pre-scoped-storage MediaStore has no {@code RELATIVE_PATH} to place a file by and
     * no pending state to hide it behind, so the app owns the write and the row
     * is inserted afterwards, pointing at what is already on disk. Inserting
     * first would leave a row referring to a file that may never appear.
     */
    private static ExportedPanorama exportLegacy(
            Context context,
            File source,
            String displayName,
            Integer width,
            Integer height) throws IOException {
        File album = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM);
        if (!album.isDirectory() && !album.mkdirs()) {
            throw new IOException("Could not create " + RELATIVE_PATH);
        }

        File target = uniqueFile(album, displayName);
        InputStream bytes = new FileInputStream(source);
        try {
            OutputStream sink = new FileOutputStream(target);
            try {
                copy(bytes, sink);
            } finally {
                sink.close();
            }
        } finally {
            bytes.close();
        }

        ContentValues values = baseValues(target.getName(), width, height);
        values.put(MediaStore.Images.Media.DATA, target.getAbsolutePath());
        Uri uri;
        try {
            uri = context.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore would not index " + target.getName());
            }
        } catch (Exception e) {
            // The row is what makes the file visible; without one the copy is
            // just clutter in the user's Pictures directory.
            target.delete();
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException(e.getMessage(), e);
        }

        return new ExportedPanorama(uri, target.getName());
    }

    /** The columns both paths set. */
    private static ContentValues baseValues(String displayName, Integer width, Integer height) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE);
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        if (width != null) {
            values.put(MediaStore.Images.Media.WIDTH, width);
        }
        if (height != null) {
            values.put(MediaStore.Images.Media.HEIGHT, height);
        }
        return values;
    }

    /**
     * {@code name} in {@code directory}, or the first {@code name (n)} variant that is free.
     *
     * <p>MediaStore does this itself from API 29; below it a second export in the
     * same second would silently overwrite the first.
     */
    private static File uniqueFile(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) {
            return candidate;
        }

        int lastDot = name.lastIndexOf('.');
        String base;
        String suffix;
        if (lastDot < 0) {
            base = name;
            suffix = "";
        } else {
            base = name.substring(0, lastDot);
            String extension = name.substring(lastDot + 1);
            suffix = extension.isEmpty() ? "" : "." + extension;
        }
        int attempt = 1;
        while (true) {
            File next = new File(directory, base + " (" + attempt + ")" + suffix);
            if (!next.exists()) {
                return next;
            }
            attempt++;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
    }
}
