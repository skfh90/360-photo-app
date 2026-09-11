package com.n30dyn4m1c.photosphere.storage;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.camera.core.ImageCapture;

import com.n30dyn4m1c.photosphere.sensor.OrientationData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The frames of one capture session, held together with the attitude each was
 * shot at.
 *
 * <p>Guided capture produces frames faster than anything can consume them, and the
 * stitcher needs the whole set at once. This is the thing in between: capture
 * appends to it, the "Finish &amp; stitch" button reads {@link #files} out of it, and the
 * session is thrown away afterwards.
 *
 * <p>Pixels stay on disk — a sphere is forty-odd full-resolution JPEGs, and holding
 * them as decoded bitmaps would be several hundred megabytes of heap for no
 * gain. What is kept in memory is the index of {@link BufferedFrame}s: paths plus
 * sensor metadata. {@link #record} accepts either a file that CameraX has already
 * written or a {@link Bitmap} the caller holds, which it persists first.
 *
 * <p>All mutating calls touch the filesystem and are synchronized, so two
 * captures completing at once cannot lose a frame. Frame-count updates are
 * delivered through {@link Listener}. The reads ({@link #getFrames}, {@link #getFrameCount},
 * {@link #files}) are safe from anywhere.
 */
public class ImageBufferManager {

    private static final String TAG = "ImageBufferManager";

    /** Quality used when a caller hands over a {@link Bitmap} instead of a file. */
    private static final int DEFAULT_JPEG_QUALITY = 95;

    /**
     * Receives the buffered frame count after each mutation that changes it.
     */
    public interface Listener {
        void onFrameCountChanged(int frameCount);
    }

    // The manager outlives individual screens; holding an Activity here would
    // leak it for as long as the buffer is alive.
    private final Context appContext;

    private String sessionId = SphereImageStore.newSessionId();

    private final List<BufferedFrame> frames = new ArrayList<BufferedFrame>();

    private Listener listener;

    public ImageBufferManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized Listener getListener() {
        return listener;
    }

    /** Identifier of the run currently being captured. */
    public synchronized String getSessionId() {
        return sessionId;
    }

    /** Everything captured so far this session, ordered by capture index. */
    public synchronized List<BufferedFrame> getFrames() {
        return Collections.unmodifiableList(new ArrayList<BufferedFrame>(frames));
    }

    /** Total frames captured this session. */
    public synchronized int getFrameCount() {
        return frames.size();
    }

    /** True while the session has nothing worth stitching. */
    public synchronized boolean isEmpty() {
        return frames.isEmpty();
    }

    /** The buffered frames as stitcher input, in capture order. */
    public synchronized List<File> files() {
        List<BufferedFrame> snapshot = getFrames();
        List<File> out = new ArrayList<File>(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            out.add(snapshot.get(i).file);
        }
        return out;
    }

    /** The frame captured at {@code index}, if one landed. */
    public synchronized BufferedFrame frameAt(int index) {
        for (int i = 0; i < frames.size(); i++) {
            BufferedFrame frame = frames.get(i);
            if (frame.index == index) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Reserves the file frame {@code index} will be written to.
     *
     * <p>Creating the session directory is a filesystem touch; the returned
     * options go straight to {@code ImageCapture.takePicture}.
     */
    public synchronized SphereImageStore.TempFrameRequest reserveFrame(int index) {
        File directory = SphereImageStore.sessionDirectory(appContext, sessionId);
        return SphereImageStore.newTempFrameOptions(directory, index);
    }

    /**
     * Reserves {@code count} candidate files for frame {@code index}, one per burst shot.
     *
     * <p>The canonical {@code frame_%03d.jpg} name is left for the winner; the
     * candidates sit alongside it as {@code frame_%03d_t{k}.jpg} until
     * {@link #commitBestFrame} promotes one. Callers that only want a single frame
     * should keep using {@link #reserveFrame} and skip the burst machinery.
     */
    public synchronized List<SphereImageStore.TempFrameRequest> reserveBurstFrames(
            int index, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("burst size must be positive, was " + count);
        }
        File directory = SphereImageStore.sessionDirectory(appContext, sessionId);
        List<SphereImageStore.TempFrameRequest> requests =
                new ArrayList<SphereImageStore.TempFrameRequest>(count);
        for (int burst = 0; burst < count; burst++) {
            File file = SphereImageStore.burstFrameFile(directory, index, burst);
            requests.add(new SphereImageStore.TempFrameRequest(
                    new ImageCapture.OutputFileOptions.Builder(file).build(),
                    file));
        }
        return requests;
    }

    /**
     * Keeps {@code best} as frame {@code index}'s canonical file and buffers it.
     *
     * <p>{@code best} must be one of the files {@link #reserveBurstFrames} handed back (or, for
     * a single-frame session, the {@link #reserveFrame} file). It is promoted onto the
     * canonical {@code frame_%03d.jpg} name — within its own directory, so a frame
     * whose session was superseded mid-burst stays put and {@link #record}'s
     * session check still rejects it — and the rest of the burst is deleted, so
     * the session holds exactly one file per index. The stitcher reads the
     * buffer's {@link #getFrames}, never the directory.
     */
    public synchronized BufferedFrame commitBestFrame(
            File best,
            int index,
            OrientationData orientation) {
        File directory = best.getParentFile();
        File canonical = directory != null ? SphereImageStore.frameFile(directory, index) : null;

        File winner = best;
        if (canonical != null && !best.equals(canonical)) {
            // An atomic replace: the promotion either lands whole or leaves the
            // previous attempt in place. Deleting the canonical first would open
            // a window where both copies of the index are gone if the process
            // died between the two calls.
            try {
                Files.move(best.toPath(), canonical.toPath(), StandardCopyOption.REPLACE_EXISTING);
                winner = canonical;
            } catch (IOException e) {
                Log.w(TAG, "Could not promote " + best.getName() + " to " + canonical.getName(), e);
            }
        }

        // The burst candidates that lost. Deleting a sibling here never touches
        // the winner: burst names always carry the `_t{k}` suffix, the canonical
        // name never does.
        if (directory != null) {
            File[] siblings = directory.listFiles();
            if (siblings != null) {
                String prefix = SphereImageStore.burstPrefix(index);
                for (int i = 0; i < siblings.length; i++) {
                    File sibling = siblings[i];
                    if (!sibling.equals(winner) && sibling.getName().startsWith(prefix)) {
                        if (!sibling.delete()) {
                            Log.w(TAG, "Could not delete burst sibling " + sibling.getName());
                        }
                    }
                }
            }
        }

        return record(winner, index, orientation);
    }

    /**
     * Adds a frame that is already on disk, stamping its attitude into EXIF.
     *
     * <p>Re-recording an index replaces the earlier entry rather than duplicating
     * it, so a retried capture leaves one frame in the buffer, not two.
     *
     * <p>Returns null, having deleted the file, when it belongs to a session that
     * has since been cancelled: a shutter in flight when the user starts a new
     * sphere would otherwise drop one frame of the old one into the new set,
     * and a single frame from the wrong place is exactly what a stitcher cannot
     * cope with.
     */
    public synchronized BufferedFrame record(File file, int index, OrientationData orientation) {
        File parent = file.getParentFile();
        if (parent == null || !sessionId.equals(parent.getName())) {
            Log.w(TAG, "Dropping " + file.getName() + " from superseded session");
            file.delete();
            return null;
        }

        SphereImageStore.stampCaptureOrientation(
                file,
                index,
                orientation.getYawDegrees(),
                orientation.getPitchDegrees(),
                orientation.getRollDegrees());

        BufferedFrame frame = new BufferedFrame(
                index,
                file,
                orientation.getYawDegrees(),
                orientation.getPitchDegrees(),
                orientation.getRollDegrees(),
                orientation.getCameraBasis(),
                System.currentTimeMillis());

        List<BufferedFrame> updated = new ArrayList<BufferedFrame>();
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).index != index) {
                updated.add(frames.get(i));
            }
        }
        updated.add(frame);
        Collections.sort(updated, new Comparator<BufferedFrame>() {
            @Override
            public int compare(BufferedFrame a, BufferedFrame b) {
                return Integer.compare(a.index, b.index);
            }
        });
        frames.clear();
        frames.addAll(updated);
        notifyFrameCount(frames.size());
        return frame;
    }

    /**
     * Persists {@code bitmap} into the session directory and buffers it.
     *
     * <p>For callers that hold pixels rather than a file — an {@code ImageAnalysis} frame,
     * or a test. The bitmap is not recycled here; that stays with its owner.
     */
    public synchronized BufferedFrame record(Bitmap bitmap, int index, OrientationData orientation)
            throws IOException {
        return record(bitmap, index, orientation, DEFAULT_JPEG_QUALITY);
    }

    public synchronized BufferedFrame record(
            Bitmap bitmap,
            int index,
            OrientationData orientation,
            int quality) throws IOException {
        File directory = SphereImageStore.sessionDirectory(appContext, sessionId);
        File target = SphereImageStore.frameFile(directory, index);
        FileOutputStream out = new FileOutputStream(target);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw new IOException("Could not encode frame " + index);
            }
        } finally {
            out.close();
        }
        return record(target, index, orientation);
    }

    /**
     * Drops the most recently captured frame, deleting its file, and returns it.
     *
     * <p>Guided capture walks the plan one index at a time, so the newest frame is
     * always the highest buffered index — this is the "undo that shot" a
     * StreetView-style capture wants: a frame that came out blurred, or one shot
     * while something moved through the scene, is removed and its target becomes
     * the active one again so it can simply be re-shot.
     *
     * <p>Returns null when the buffer is empty.
     */
    public synchronized BufferedFrame undoLastFrame() {
        if (frames.isEmpty()) {
            return null;
        }
        BufferedFrame last = frames.get(0);
        for (int i = 1; i < frames.size(); i++) {
            if (frames.get(i).index > last.index) {
                last = frames.get(i);
            }
        }
        frames.remove(last);
        if (last.file.exists() && !last.file.delete()) {
            Log.w(TAG, "Could not delete undone frame " + last.file.getName());
        }
        notifyFrameCount(frames.size());
        return last;
    }

    /**
     * Empties the buffer, deleting the frames it was holding.
     *
     * <p>The session id survives, so capture can carry on writing into the same
     * directory — this is the "those frames have been consumed" call, used after
     * a stitch succeeds. Pass {@code deleteFiles = false} to keep the JPEGs on disk.
     */
    public synchronized void clear() {
        clear(true);
    }

    public synchronized void clear(boolean deleteFiles) {
        List<BufferedFrame> dropped = new ArrayList<BufferedFrame>(frames);
        frames.clear();
        if (deleteFiles) {
            for (int i = 0; i < dropped.size(); i++) {
                BufferedFrame frame = dropped.get(i);
                if (frame.file.exists() && !frame.file.delete()) {
                    Log.w(TAG, "Could not delete " + frame.file.getName());
                }
            }
        }
        notifyFrameCount(0);
    }

    /**
     * Abandons this session and opens a fresh, empty one.
     *
     * <p>The whole session directory goes, not just the buffered frames, so a
     * half-written capture cannot survive into the next run. Returns the new
     * session id.
     *
     * <p>Only the manager's own state is reset: a capture already in flight belongs
     * to the caller and has to be cancelled there, or its frame will
     * land in the new session's directory.
     */
    public synchronized String cancelSession() {
        String previous = sessionId;
        frames.clear();

        SphereImageStore.deleteSession(appContext, previous);

        // The id has one-second resolution, so a restart inside the same second
        // reuses the name. That is harmless now the directory itself is gone.
        sessionId = SphereImageStore.newSessionId();
        notifyFrameCount(0);
        return sessionId;
    }

    /** Deletes the leftovers of every session but this one. */
    public synchronized void pruneStaleSessions() {
        SphereImageStore.pruneSessions(appContext, sessionId);
    }

    private void notifyFrameCount(int frameCount) {
        Listener callback = listener;
        if (callback != null) {
            callback.onFrameCountChanged(frameCount);
        }
    }
}
