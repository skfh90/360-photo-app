package com.n30dyn4m1c.photosphere.camera;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.fragment.app.Fragment;

import com.google.common.util.concurrent.ListenableFuture;
import com.n30dyn4m1c.photosphere.BuildConfig;
import com.n30dyn4m1c.photosphere.R;
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata;
import com.n30dyn4m1c.photosphere.sensor.DisplayRotation;
import com.n30dyn4m1c.photosphere.sensor.OrientationAccuracy;
import com.n30dyn4m1c.photosphere.sensor.OrientationData;
import com.n30dyn4m1c.photosphere.sensor.OrientationMean;
import com.n30dyn4m1c.photosphere.sensor.OrientationTracker;
import com.n30dyn4m1c.photosphere.stitching.CameraPose;
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher;
import com.n30dyn4m1c.photosphere.stitching.RadialDistortion;
import com.n30dyn4m1c.photosphere.stitching.SphereFrame;
import com.n30dyn4m1c.photosphere.stitching.StitchCancelledException;
import com.n30dyn4m1c.photosphere.stitching.StitchException;
import com.n30dyn4m1c.photosphere.stitching.StitchProgress;
import com.n30dyn4m1c.photosphere.stitching.StitchStage;
import com.n30dyn4m1c.photosphere.stitching.StitchStatus;
import com.n30dyn4m1c.photosphere.storage.BufferedFrame;
import com.n30dyn4m1c.photosphere.storage.ImageBufferManager;
import com.n30dyn4m1c.photosphere.storage.SphereImageStore;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guided capture: viewfinder, alignment overlay, automatic shutter, then stitch.
 */
@SuppressLint("UnsafeOptInUsageError")
@ExperimentalCamera2Interop
public class CaptureFragment extends Fragment {

    public interface Host {
        void onSphereReady(SphereImageStore.StitchedSphere sphere);
    }

    private static final String TAG = "PhotoSphereCamera";
    private static final long THREE_A_LOCK_TIMEOUT_MS = 6_000L;
    private static final float RING_LATITUDE_SPAN_FACTOR = 1.3f;
    private static final int POSE_WINDOW_SIZE = 20;
    private static final String STATE_SCOPE = "capture_scope";
    private static final String STATE_INSTRUCTIONS = "show_instructions";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();
    private final ArrayDeque<OrientationData> poseWindow = new ArrayDeque<OrientationData>();

    private PreviewView previewView;
    private TargetOverlayView overlay;
    private ImageButton undoButton;
    private TextView scopeSphere;
    private TextView scopeRing;
    private TextView progressCaptured;
    private TextView progressTotal;
    private ProgressBar progressBar;
    private TextView progressRings;
    private LinearLayout focusChip;
    private TextView focusChipText;
    private TextView hintText;
    private View debugToggles;
    private TextView debugDistortion;
    private TextView debugRefine;
    private TextView debugSeam;
    private TextView debugColor;
    private Button finishButton;
    private Button restartButton;
    private View instructionsOverlay;
    private TextView snackbarText;
    private Dialog stitchDialog;
    private TextView stitchStage;
    private ProgressBar stitchSpinner;
    private ProgressBar stitchRing;
    private TextView stitchPercent;

    private OrientationTracker tracker;
    private CaptureFeedback feedback;
    private SphereDeviceProfile deviceProfile;
    private AlignmentGate gate;
    private ImageBufferManager buffer;
    private SphereOptics optics;

    private String boundCameraId;
    private float streamAspectRatio;
    private SphereCaptureScope captureScope = SphereCaptureScope.Sphere;
    private ImageCapture imageCapture;
    private SphereCaptureProfile captureProfile;
    private Camera boundCamera;
    private SphereTargetPlan plan;
    private int activeIndex;
    private boolean isHolding;
    private OrientationAccuracy accuracy = OrientationAccuracy.Unknown;
    private boolean distortionEnabled;
    private boolean refinementEnabled;
    private boolean seamEnabled;
    private boolean colorFrames;
    private boolean showInstructions = true;
    private AlignmentState alignment = new AlignmentState();
    private TargetOverlayView.FocusReticle focusReticle;
    private volatile boolean captureInFlight;
    private volatile boolean stitching;
    private Context appContext;
    private Future<?> stitchFuture;
    private boolean threeALocked;
    private Camera2CameraControl camera2Control;
    private Runnable snackbarHide;
    private Runnable threeATimeout;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_capture, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (savedInstanceState != null) {
            captureScope = savedInstanceState.getInt(STATE_SCOPE, 0) == 1
                    ? SphereCaptureScope.Ring
                    : SphereCaptureScope.Sphere;
            showInstructions = savedInstanceState.getBoolean(STATE_INSTRUCTIONS, true);
        }

        previewView = view.findViewById(R.id.preview_view);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        overlay = view.findViewById(R.id.target_overlay);
        undoButton = view.findViewById(R.id.button_undo);
        scopeSphere = view.findViewById(R.id.scope_sphere);
        scopeRing = view.findViewById(R.id.scope_ring);
        progressCaptured = view.findViewById(R.id.progress_captured);
        progressTotal = view.findViewById(R.id.progress_total);
        progressBar = view.findViewById(R.id.progress_bar);
        progressRings = view.findViewById(R.id.progress_rings);
        focusChip = view.findViewById(R.id.chip_focus);
        focusChipText = view.findViewById(R.id.chip_focus_text);
        hintText = view.findViewById(R.id.hint_text);
        debugToggles = view.findViewById(R.id.debug_toggles);
        debugDistortion = view.findViewById(R.id.debug_distortion);
        debugRefine = view.findViewById(R.id.debug_refine);
        debugSeam = view.findViewById(R.id.debug_seam);
        debugColor = view.findViewById(R.id.debug_color);
        finishButton = view.findViewById(R.id.button_finish);
        restartButton = view.findViewById(R.id.button_restart);
        instructionsOverlay = view.findViewById(R.id.instructions_overlay);
        snackbarText = view.findViewById(R.id.snackbar_text);

        bindInstructionSteps(instructionsOverlay);
        applyHudInsets(view);

        Context context = requireContext();
        appContext = context.getApplicationContext();
        tracker = new OrientationTracker(context);
        tracker.setDisplayRotation(DisplayRotation.current(context));
        feedback = new CaptureFeedback(context);
        deviceProfile = SphereDeviceProfile.forDevice();
        gate = new AlignmentGate();
        buffer = new ImageBufferManager(context);
        optics = CameraOptics.estimateSphereOptics(
                context,
                DisplayRotation.current(context),
                deviceProfile
        );
        overlay.setFieldOfView(optics.getFieldOfView());

        buffer.setListener(new ImageBufferManager.Listener() {
            @Override
            public void onFrameCountChanged(int frameCount) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateHud();
                    }
                });
            }
        });
        captureExecutor.execute(new Runnable() {
            @Override
            public void run() {
                buffer.pruneStaleSessions();
            }
        });

        tracker.setListener(new OrientationTracker.Listener() {
            @Override
            public void onOrientationChanged(final OrientationData orientation) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        onOrientationSample(orientation);
                    }
                });
            }
        });

        previewView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP
                        && captureProfile != null
                        && captureProfile.getFocusMode() == FocusMode.FOCUS_POINT
                        && v.getWidth() > 0
                        && v.getHeight() > 0) {
                    lockFocusAt(
                            coerceIn(event.getX() / v.getWidth(), 0f, 1f),
                            coerceIn(event.getY() / v.getHeight(), 0f, 1f)
                    );
                    v.performClick();
                }
                return true;
            }
        });

        scopeSphere.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCaptureScope(SphereCaptureScope.Sphere);
            }
        });
        scopeRing.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCaptureScope(SphereCaptureScope.Ring);
            }
        });
        finishButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startStitch();
            }
        });
        restartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetGuidance();
                captureExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        buffer.cancelSession();
                    }
                });
                updateHud();
            }
        });
        undoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final BufferedFrame undone = buffer.undoLastFrame();
                        if (undone == null) {
                            return;
                        }
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                activeIndex = undone.index;
                                isHolding = false;
                                alignment = new AlignmentState();
                                gate.reset();
                                overlay.setAlignment(alignment);
                                overlay.setPlan(plan, activeIndex);
                                updateHud();
                            }
                        });
                    }
                });
            }
        });
        debugDistortion.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                distortionEnabled = !distortionEnabled;
                updateHud();
            }
        });
        debugRefine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refinementEnabled = !refinementEnabled;
                updateHud();
            }
        });
        debugSeam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seamEnabled = !seamEnabled;
                updateHud();
            }
        });
        debugColor.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                colorFrames = !colorFrames;
                updateHud();
            }
        });
        instructionsOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissInstructions();
            }
        });
        instructionsOverlay.findViewById(R.id.button_instructions_dismiss)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismissInstructions();
                    }
                });

        bindCamera();
        updateHud();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SCOPE, captureScope == SphereCaptureScope.Ring ? 1 : 0);
        outState.putBoolean(STATE_INSTRUCTIONS, showInstructions);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getView() != null) {
            getView().setKeepScreenOn(true);
        }
        if (tracker != null) {
            tracker.setDisplayRotation(DisplayRotation.current(requireContext()));
            tracker.startListening();
        }
    }

    @Override
    public void onPause() {
        if (tracker != null) {
            tracker.stopListening();
        }
        if (getView() != null) {
            getView().setKeepScreenOn(false);
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (threeATimeout != null) {
            mainHandler.removeCallbacks(threeATimeout);
        }
        if (snackbarHide != null) {
            mainHandler.removeCallbacks(snackbarHide);
        }
        dismissStitchDialog();
        imageCapture = null;
        boundCamera = null;
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(requireContext());
        if (future.isDone()) {
            try {
                future.get().unbindAll();
            } catch (Exception e) {
                Log.w(TAG, "Could not release the camera", e);
            }
        }
        if (feedback != null) {
            feedback.release();
        }
        if (tracker != null) {
            tracker.setListener(null);
        }
        captureExecutor.shutdownNow();
        super.onDestroyView();
    }

    private void bindCamera() {
        final Context context = requireContext();
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);
        future.addListener(new Runnable() {
            @Override
            public void run() {
                if (!isAdded()) {
                    return;
                }
                ProcessCameraProvider provider;
                try {
                    provider = future.get();
                } catch (Exception e) {
                    Log.e(TAG, "Camera provider unavailable", e);
                    showSnackbar(getString(R.string.capture_failed, messageOf(e)));
                    return;
                }
                attachUseCases(provider);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void attachUseCases(ProcessCameraProvider provider) {
        Context context = requireContext();
        captureProfile = SphereCaptureProfile.resolveSphereCaptureProfile(context, deviceProfile);
        CameraSelector selector;
        if (deviceProfile.getPreferWidestCamera()) {
            CameraSelector widest = widestCameraSelector(context);
            selector = widest != null ? widest : CameraSelector.DEFAULT_BACK_CAMERA;
        } else {
            selector = CameraSelector.DEFAULT_BACK_CAMERA;
        }

        threeALocked = false;
        camera2Control = null;
        Preview.Builder previewBuilder = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3);
        Camera2Interop.Extender<Preview> previewExtender =
                new Camera2Interop.Extender<Preview>(previewBuilder);
        SphereCaptureProfile.applySphereCaptureOptions(previewExtender, captureProfile);
        previewExtender.setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(
                    @NonNull CameraCaptureSession session,
                    @NonNull CaptureRequest request,
                    @NonNull TotalCaptureResult result
            ) {
                Integer state = result.get(CaptureResult.CONTROL_AE_STATE);
                boolean settled = state != null
                        && (state == CaptureResult.CONTROL_AE_STATE_CONVERGED
                        || state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED);
                if (settled) {
                    lockThreeA();
                }
            }
        });
        Preview preview = previewBuilder.build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setTargetResolution(new Size(
                        deviceProfile.getCaptureMaxLongEdgePx(),
                        deviceProfile.getCaptureMaxLongEdgePx() * 3 / 4
                ))
                .setTargetRotation(DisplayRotation.current(context));
        SphereCaptureProfile.applySphereCaptureOptions(
                new Camera2Interop.Extender<ImageCapture>(captureBuilder),
                captureProfile
        );
        SphereCaptureProfile.applyStillImageOptions(
                new Camera2Interop.Extender<ImageCapture>(captureBuilder),
                captureProfile
        );
        ImageCapture capture = captureBuilder.build();

        try {
            provider.unbindAll();
            Camera camera;
            try {
                camera = provider.bindToLifecycle(getViewLifecycleOwner(), selector, preview, capture);
            } catch (Exception e) {
                Log.w(TAG, "Widest camera unavailable, binding the default", e);
                camera = provider.bindToLifecycle(
                        getViewLifecycleOwner(),
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture
                );
            }
            imageCapture = capture;
            boundCamera = camera;
            try {
                camera2Control = Camera2CameraControl.from(camera.getCameraControl());
            } catch (Exception ignored) {
            }
            try {
                boundCameraId = Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId();
            } catch (Exception ignored) {
                boundCameraId = null;
            }
            streamAspectRatio = 4f / 3f;
            optics = CameraOptics.estimateSphereOptics(
                    context,
                    DisplayRotation.current(context),
                    deviceProfile,
                    boundCameraId,
                    streamAspectRatio
            );
            overlay.setFieldOfView(optics.getFieldOfView());
            if (buffer.isEmpty()) {
                plan = null;
                activeIndex = 0;
                isHolding = false;
                alignment = new AlignmentState();
            }
            gate.reset();
            Log.i(TAG, "Bound camera " + boundCameraId + ", stills " + streamAspectRatio + ":1");
            if (threeATimeout != null) {
                mainHandler.removeCallbacks(threeATimeout);
            }
            threeATimeout = new Runnable() {
                @Override
                public void run() {
                    lockThreeA();
                }
            };
            mainHandler.postDelayed(threeATimeout, THREE_A_LOCK_TIMEOUT_MS);
            if (captureProfile.getFocusMode() == FocusMode.FOCUS_POINT) {
                lockFocusAt(0.5f, 0.5f);
            }
            updateHud();
        } catch (Exception e) {
            Log.e(TAG, "Camera binding failed", e);
            showSnackbar(getString(R.string.capture_failed, messageOf(e)));
        }
    }

    private void lockThreeA() {
        if (threeALocked || camera2Control == null) {
            return;
        }
        threeALocked = true;
        try {
            camera2Control.addCaptureRequestOptions(
                    new CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                            .build()
            );
        } catch (Exception e) {
            Log.w(TAG, "Could not lock 3A", e);
        }
    }

    private void onOrientationSample(OrientationData orientation) {
        overlay.setOrientation(orientation);
        if (!orientation.hasFix() || captureInFlight) {
            return;
        }
        accuracy = orientation.accuracy;
        poseWindow.addLast(orientation);
        while (poseWindow.size() > POSE_WINDOW_SIZE) {
            poseWindow.removeFirst();
        }
        if (stitching) {
            gate.reset();
            poseWindow.clear();
            return;
        }
        if (plan == null) {
            plan = SphereTargetPlan.createForFieldOfView(
                    orientation.yawDegrees,
                    optics.getFieldOfView(),
                    captureScope
            );
            overlay.setPlan(plan, activeIndex);
        }
        SphereTarget target = plan.getOrNull(activeIndex);
        if (target == null) {
            gate.reset();
            alignment = new AlignmentState();
            isHolding = false;
            overlay.setAlignment(alignment);
            updateHud();
            return;
        }
        float distance = SphereProjection.angularDistanceDegrees(orientation, target);
        if (!orientation.accuracy.allowsCapture()) {
            gate.reset();
            alignment = new AlignmentState(distance, 0f, false, false);
            isHolding = false;
            overlay.setAlignment(alignment);
            updateHud();
            return;
        }
        AlignmentGate.Reading reading = gate.update(distance, SystemClock.elapsedRealtime());
        alignment = new AlignmentState(
                distance,
                reading.getDwellProgress(),
                reading.isAligned(),
                false
        );
        isHolding = reading.isAligned();
        if (!reading.isAligned()) {
            poseWindow.clear();
        }
        overlay.setAlignment(alignment);
        overlay.setPlan(plan, activeIndex);
        updateHud();
        if (!reading.isTriggered()) {
            return;
        }
        fireShutter(activeIndex);
    }

    private void fireShutter(final int index) {
        final ImageCapture capture = imageCapture;
        if (capture == null || captureInFlight) {
            return;
        }
        captureInFlight = true;
        alignment = new AlignmentState(
                alignment.getDistanceDegrees(),
                alignment.getDwellProgress(),
                alignment.isAligned(),
                true
        );
        overlay.setAlignment(alignment);
        final List<OrientationData> dwell = new ArrayList<OrientationData>(poseWindow);
        poseWindow.clear();
        captureExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File file = saveFrame(
                            capture,
                            index,
                            OrientationMean.meanOrientation(dwell),
                            deviceProfile.getBurstPerTarget()
                    );
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            captureInFlight = false;
                            alignment = new AlignmentState(
                                    alignment.getDistanceDegrees(),
                                    0f,
                                    false,
                                    false
                            );
                            overlay.setAlignment(alignment);
                            if (file != null) {
                                feedback.onFrameCaptured();
                                activeIndex = index + 1;
                            }
                            gate.reset();
                            overlay.setPlan(plan, activeIndex);
                            updateHud();
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            captureInFlight = false;
                            gate.reset();
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "Frame " + index + " failed", e);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            captureInFlight = false;
                            alignment = new AlignmentState();
                            overlay.setAlignment(alignment);
                            gate.reset();
                            showSnackbar(getString(R.string.capture_failed, messageOf(e)));
                            updateHud();
                        }
                    });
                }
            }
        });
    }

    private File saveFrame(
            ImageCapture capture,
            int index,
            OrientationData orientation,
            int burstPerTarget
    ) throws Exception {
        List<SphereImageStore.TempFrameRequest> requests;
        if (burstPerTarget > 1) {
            requests = buffer.reserveBurstFrames(index, burstPerTarget);
        } else {
            requests = new ArrayList<SphereImageStore.TempFrameRequest>();
            requests.add(buffer.reserveFrame(index));
        }
        try {
            for (int i = 0; i < requests.size(); i++) {
                takePictureTo(capture, requests.get(i).outputOptions);
            }
            File best;
            if (burstPerTarget > 1) {
                List<File> files = new ArrayList<File>(requests.size());
                for (int i = 0; i < requests.size(); i++) {
                    files.add(requests.get(i).file);
                }
                best = SharpnessSelection.pickSharpest(files);
            } else {
                best = requests.get(0).file;
            }
            BufferedFrame committed = buffer.commitBestFrame(best, index, orientation);
            if (committed == null) {
                throw new IllegalStateException("session superseded, frame not captured");
            }
            return committed.file;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            for (int i = 0; i < requests.size(); i++) {
                File file = requests.get(i).file;
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Could not delete failed burst file " + file.getName());
                }
            }
            throw e;
        }
    }

    private void takePictureTo(
            ImageCapture capture,
            ImageCapture.OutputFileOptions options
    ) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        capture.takePicture(
                options,
                ContextCompat.getMainExecutor(appContext),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        error.set(exception);
                        latch.countDown();
                    }
                }
        );
        latch.await();
        if (error.get() != null) {
            throw error.get();
        }
    }

    private void lockFocusAt(final float nx, final float ny) {
        final Camera camera = boundCamera;
        if (camera == null) {
            return;
        }
        focusReticle = new TargetOverlayView.FocusReticle(nx, ny, true, false);
        overlay.setFocusReticle(focusReticle);
        updateHud();
        if (previewView.getWidth() <= 0 || previewView.getHeight() <= 0) {
            focusReticle = new TargetOverlayView.FocusReticle(nx, ny, false, false);
            overlay.setFocusReticle(focusReticle);
            return;
        }
        ListenableFuture<?> future = camera.getCameraControl().startFocusAndMetering(
                new FocusMeteringAction.Builder(
                        previewView.getMeteringPointFactory().createPoint(
                                previewView.getWidth() * coerceIn(nx, 0f, 1f),
                                previewView.getHeight() * coerceIn(ny, 0f, 1f)
                        ),
                        FocusMeteringAction.FLAG_AF
                ).build()
        );
        future.addListener(new Runnable() {
            @Override
            public void run() {
                boolean locked = true;
                try {
                    future.get();
                } catch (Exception e) {
                    locked = false;
                }
                focusReticle = new TargetOverlayView.FocusReticle(nx, ny, false, locked);
                overlay.setFocusReticle(focusReticle);
                updateHud();
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void startStitch() {
        if (stitching) {
            return;
        }
        stitching = true;
        showStitchDialog(StitchProgress.Preparing);
        updateHud();
        stitchFuture = captureExecutor.submit(new Runnable() {
            @Override
            public void run() {
                while (captureInFlight) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(16);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        finishStitchOnMain(null, null);
                        return;
                    }
                }
                List<BufferedFrame> buffered = buffer.getFrames();
                final List<SphereFrame> frames = new ArrayList<SphereFrame>(buffered.size());
                for (int i = 0; i < buffered.size(); i++) {
                    BufferedFrame frame = buffered.get(i);
                    double[] matrix = null;
                    if (frame.cameraBasis != null) {
                        matrix = new double[frame.cameraBasis.length];
                        for (int j = 0; j < frame.cameraBasis.length; j++) {
                            matrix[j] = frame.cameraBasis[j];
                        }
                    }
                    frames.add(new SphereFrame(
                            frame.file,
                            new CameraPose(
                                    frame.yawDegrees,
                                    frame.pitchDegrees,
                                    frame.rollDegrees,
                                    matrix
                            )
                    ));
                }
                float latitudeSpan = latitudeSpanDegrees();
                Bitmap sphereBitmap = null;
                try {
                    sphereBitmap = PhotoSphereStitcher.stitchPhotos(
                            frames,
                            optics.getFieldOfView().getHorizontalDegrees(),
                            optics.getFieldOfView().getVerticalDegrees(),
                            distortionEnabled ? optics.getRadialDistortion() : null,
                            deviceProfile.getStitchMaxInputDimension(),
                            deviceProfile.getStitchMaxOutputWidth(),
                            deviceProfile.getUnsharpAmount(),
                            deviceProfile.getPivot(),
                            optics.getPortraitRotationDegrees(),
                            refinementEnabled,
                            seamEnabled,
                            colorFrames,
                            360f,
                            0f,
                            latitudeSpan,
                            0f,
                            new PhotoSphereStitcher.ProgressCallback() {
                                @Override
                                public void onProgress(final StitchProgress progress) {
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            updateStitchDialog(progress);
                                        }
                                    });
                                }
                            }
                    );
                    SphereImageStore.StitchedSphere written = SphereImageStore.writeStitchedSphere(
                            appContext,
                            sphereBitmap,
                            GPanoMetadata.forSphereRegion(
                                    sphereBitmap.getWidth(),
                                    sphereBitmap.getHeight(),
                                    360f,
                                    0f,
                                    latitudeSpan,
                                    0f
                            )
                    );
                    SphereImageStore.StitchedSphere stitched = new SphereImageStore.StitchedSphere(
                            written.file,
                            written.width,
                            written.height,
                            stitchDiagnostics(frames),
                            written.gpano
                    );
                    buffer.clear();
                    finishStitchOnMain(stitched, null);
                } catch (StitchCancelledException e) {
                    finishStitchOnMain(null, null);
                } catch (final Exception e) {
                    Log.e(TAG, "Stitch failed", e);
                    finishStitchOnMain(null, e);
                } finally {
                    if (sphereBitmap != null) {
                        sphereBitmap.recycle();
                    }
                }
            }
        });
    }

    private void finishStitchOnMain(
            final SphereImageStore.StitchedSphere stitched,
            final Exception error
    ) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                stitching = false;
                stitchFuture = null;
                dismissStitchDialog();
                if (stitched != null) {
                    resetGuidance();
                    Host host = host();
                    if (host != null) {
                        host.onSphereReady(stitched);
                    }
                    return;
                }
                if (error != null) {
                    showSnackbar(stitchFailureMessage(error));
                }
                updateHud();
            }
        });
    }

    private void cancelStitch() {
        PhotoSphereStitcher.cancel();
        if (stitchFuture != null) {
            stitchFuture.cancel(true);
        }
    }

    private void setCaptureScope(SphereCaptureScope scope) {
        if (!canChangeScope() || captureScope == scope) {
            return;
        }
        captureScope = scope;
        if (buffer.isEmpty()) {
            plan = null;
            activeIndex = 0;
            isHolding = false;
            alignment = new AlignmentState();
            overlay.setAlignment(alignment);
            overlay.setPlan(null, 0);
        }
        updateHud();
    }

    private void resetGuidance() {
        plan = null;
        activeIndex = 0;
        isHolding = false;
        alignment = new AlignmentState();
        overlay.setAlignment(alignment);
        overlay.setPlan(null, 0);
    }

    private void dismissInstructions() {
        showInstructions = false;
        if (instructionsOverlay != null) {
            instructionsOverlay.setVisibility(View.GONE);
        }
    }

    private void updateHud() {
        if (!isAdded() || progressCaptured == null) {
            return;
        }
        int totalTargets = plan != null ? plan.size() : 0;
        boolean isComplete = totalTargets > 0 && activeIndex >= totalTargets;
        int capturedTargets = Math.min(activeIndex, totalTargets);
        int completedRings = plan != null ? plan.completedRings(capturedTargets) : 0;
        int ringCount = plan != null ? plan.getRingCount() : 0;
        int bufferedCount = buffer.getFrameCount();
        boolean canStitch = bufferedCount >= PhotoSphereStitcher.MIN_FRAMES && !stitching;
        boolean canUndo = bufferedCount > 0 && !stitching;

        progressCaptured.setText(String.valueOf(capturedTargets));
        progressTotal.setText("/ " + totalTargets);
        if (totalTargets > 0) {
            progressBar.setProgress(Math.round(1000f * capturedTargets / totalTargets));
            progressBar.setVisibility(View.VISIBLE);
        } else {
            progressBar.setProgress(0);
        }
        if (ringCount > 1) {
            progressRings.setVisibility(View.VISIBLE);
            progressRings.setText(getString(R.string.capture_rings, completedRings, ringCount));
        } else {
            progressRings.setVisibility(View.GONE);
        }
        progressCaptured.setContentDescription(
                getString(R.string.capture_progress_description, capturedTargets, totalTargets)
        );

        String lockBadge = lockBadgeText();
        if (lockBadge != null) {
            focusChip.setVisibility(View.VISIBLE);
            focusChipText.setText(lockBadge);
        } else {
            focusChip.setVisibility(View.GONE);
        }

        hintText.setText(captureHint(
                tracker != null && tracker.isSensorAvailable(),
                imageCapture != null,
                plan != null,
                isComplete,
                isHolding,
                accuracy,
                completedRings,
                ringCount
        ));

        boolean canChange = canChangeScope();
        scopeSphere.setSelected(captureScope == SphereCaptureScope.Sphere);
        scopeRing.setSelected(captureScope == SphereCaptureScope.Ring);
        scopeSphere.setEnabled(canChange);
        scopeRing.setEnabled(canChange);
        scopeSphere.setTextColor(scopeSphere.isSelected()
                ? ContextCompat.getColor(requireContext(), R.color.sphere_background)
                : ContextCompat.getColor(requireContext(), canChange
                ? R.color.glass_content
                : R.color.glass_content_dim));
        scopeRing.setTextColor(scopeRing.isSelected()
                ? ContextCompat.getColor(requireContext(), R.color.sphere_background)
                : ContextCompat.getColor(requireContext(), canChange
                ? R.color.glass_content
                : R.color.glass_content_dim));

        undoButton.setVisibility(canUndo ? View.VISIBLE : View.GONE);
        finishButton.setVisibility(canStitch ? View.VISIBLE : View.GONE);
        restartButton.setVisibility(isComplete ? View.VISIBLE : View.GONE);
        if (BuildConfig.DEBUG && canStitch) {
            debugToggles.setVisibility(View.VISIBLE);
            debugDistortion.setText("Dist: " + (distortionEnabled ? "on" : "off"));
            debugRefine.setText("Refine: " + (refinementEnabled ? "on" : "off"));
            debugSeam.setText("Seam: " + (seamEnabled ? "on" : "off"));
            debugColor.setText("Color: " + (colorFrames ? "on" : "off"));
        } else {
            debugToggles.setVisibility(View.GONE);
        }
        boolean showWelcome = showInstructions && activeIndex == 0 && buffer.isEmpty();
        instructionsOverlay.setVisibility(showWelcome ? View.VISIBLE : View.GONE);
    }

    private boolean canChangeScope() {
        return buffer.isEmpty() && !stitching;
    }

    private String lockBadgeText() {
        if (captureProfile == null) {
            return null;
        }
        if (captureProfile.getFocusMode() == FocusMode.FIXED_FOCUS) {
            return getString(R.string.capture_lock_fixed);
        }
        if (captureProfile.getFocusMode() == FocusMode.FOCUS_POINT) {
            if (focusReticle == null) {
                return getString(R.string.capture_lock_hint);
            }
            if (focusReticle.isWorking) {
                return getString(R.string.capture_lock_focusing);
            }
            return getString(R.string.capture_lock_locked);
        }
        return null;
    }

    private String captureHint(
            boolean isSensorAvailable,
            boolean isCameraReady,
            boolean hasPlan,
            boolean isComplete,
            boolean holding,
            OrientationAccuracy currentAccuracy,
            int completedRings,
            int ringCount
    ) {
        if (!isSensorAvailable) {
            return getString(R.string.capture_orientation_unavailable);
        }
        if (!isCameraReady) {
            return getString(R.string.capture_starting);
        }
        if (isComplete) {
            return getString(R.string.capture_sphere_complete);
        }
        if (!hasPlan) {
            return getString(R.string.capture_waiting_for_orientation);
        }
        if (!currentAccuracy.allowsCapture()) {
            return getString(R.string.capture_accuracy_blocked);
        }
        if (!currentAccuracy.isUsable() && currentAccuracy != OrientationAccuracy.Unknown) {
            return getString(R.string.capture_low_accuracy);
        }
        if (holding) {
            return getString(R.string.capture_hint_hold);
        }
        if (completedRings > 0) {
            return getString(R.string.capture_hint_ring_done, completedRings, ringCount);
        }
        return getString(R.string.capture_hint_search);
    }

    private float latitudeSpanDegrees() {
        if (captureScope == SphereCaptureScope.Sphere) {
            return 180f;
        }
        return Math.min(optics.getFieldOfView().getVerticalDegrees() * RING_LATITUDE_SPAN_FACTOR, 180f);
    }

    private void showStitchDialog(StitchProgress progress) {
        if (stitchDialog == null) {
            stitchDialog = new Dialog(requireContext(), R.style.Theme_PhotoSphere_Dialog);
            stitchDialog.setContentView(R.layout.dialog_stitching);
            stitchDialog.setCancelable(false);
            stitchDialog.setCanceledOnTouchOutside(false);
            stitchStage = stitchDialog.findViewById(R.id.stitch_stage);
            stitchSpinner = stitchDialog.findViewById(R.id.stitch_spinner);
            stitchRing = stitchDialog.findViewById(R.id.stitch_ring);
            stitchPercent = stitchDialog.findViewById(R.id.stitch_percent);
            stitchDialog.findViewById(R.id.stitch_cancel).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancelStitch();
                }
            });
        }
        updateStitchDialog(progress);
        if (!stitchDialog.isShowing()) {
            stitchDialog.show();
        }
    }

    private void updateStitchDialog(StitchProgress progress) {
        if (stitchDialog == null || stitchStage == null) {
            return;
        }
        stitchStage.setText(stitchLabel(progress));
        Float fraction = progress.getFraction();
        if (fraction != null) {
            stitchSpinner.setVisibility(View.GONE);
            stitchRing.setVisibility(View.VISIBLE);
            stitchPercent.setVisibility(View.VISIBLE);
            stitchRing.setProgress(Math.round(fraction * 1000f));
            stitchPercent.setText(Math.round(fraction * 100f) + "%");
        } else {
            stitchSpinner.setVisibility(View.VISIBLE);
            stitchRing.setVisibility(View.GONE);
            stitchPercent.setVisibility(View.GONE);
        }
    }

    private void dismissStitchDialog() {
        if (stitchDialog != null && stitchDialog.isShowing()) {
            stitchDialog.dismiss();
        }
        stitchDialog = null;
        stitchStage = null;
        stitchSpinner = null;
        stitchRing = null;
        stitchPercent = null;
    }

    private String stitchLabel(StitchProgress progress) {
        StitchStage stage = progress.stage;
        if (stage == StitchStage.Preparing) {
            return getString(R.string.stitch_stage_preparing);
        }
        if (stage == StitchStage.Reading) {
            return getString(R.string.stitch_stage_reading, progress.completed, progress.total);
        }
        if (stage == StitchStage.Refining) {
            return getString(R.string.stitch_stage_refining, progress.completed, progress.total);
        }
        if (stage == StitchStage.Seaming) {
            return getString(R.string.stitch_stage_seaming);
        }
        if (stage == StitchStage.Stitching) {
            return getString(R.string.stitch_stage_stitching);
        }
        return getString(R.string.stitch_stage_projecting);
    }

    private String stitchFailureMessage(Throwable error) {
        StitchStatus status = error instanceof StitchException
                ? ((StitchException) error).status
                : StitchStatus.Unknown;
        String reason;
        if (status == StitchStatus.NeedMoreImages || status == StitchStatus.NoInputImages) {
            reason = getString(R.string.stitch_error_need_more_images);
        } else if (status == StitchStatus.AlignmentFailed) {
            reason = getString(R.string.stitch_error_alignment);
        } else if (status == StitchStatus.CameraEstimationFailed) {
            reason = getString(R.string.stitch_error_camera_estimation);
        } else if (status == StitchStatus.OpenCvUnavailable) {
            reason = getString(R.string.stitch_error_opencv_unavailable);
        } else if (status == StitchStatus.UnreadableInput) {
            reason = getString(R.string.stitch_error_unreadable_input);
        } else if (status == StitchStatus.OutOfMemory) {
            reason = getString(R.string.stitch_error_out_of_memory);
        } else if (error instanceof java.io.IOException) {
            reason = getString(R.string.stitch_error_write_failed);
        } else {
            reason = getString(R.string.stitch_error_unknown, status.code);
        }
        return getString(R.string.stitch_failed, reason);
    }

    private String stitchDiagnostics(List<SphereFrame> frames) {
        StringBuilder poses = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            SphereFrame frame = frames.get(i);
            int exif = ExifInterface.ORIENTATION_NORMAL;
            try {
                exif = new ExifInterface(frame.file).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                );
            } catch (Exception ignored) {
            }
            poses.append("  #").append(i)
                    .append(" yaw ").append(Math.round(frame.pose.yawDegrees))
                    .append("° pitch ").append(Math.round(frame.pose.pitchDegrees))
                    .append("° roll ").append(Math.round(frame.pose.rollDegrees))
                    .append("° exif=").append(exif)
                    .append('\n');
        }
        String device = (Build.MANUFACTURER != null ? Build.MANUFACTURER : "")
                + " "
                + (Build.MODEL != null ? Build.MODEL : "");
        RadialDistortion distortion = optics.getRadialDistortion();
        String distortionText;
        if (distortion != null && distortion.coefficients != null) {
            StringBuilder k = new StringBuilder("[");
            for (int i = 0; i < distortion.coefficients.length; i++) {
                if (i > 0) {
                    k.append(", ");
                }
                k.append(String.format(Locale.US, "%.4f", distortion.coefficients[i]));
            }
            k.append("]");
            distortionText = k.toString();
        } else {
            distortionText = "none";
        }
        return "Device: " + device.trim() + "\n"
                + "FOV: " + Math.round(optics.getFieldOfView().getHorizontalDegrees())
                + "° x " + Math.round(optics.getFieldOfView().getVerticalDegrees())
                + "° (screen) rotation=" + optics.getPortraitRotationDegrees() + "°\n"
                + "Distortion k: " + distortionText
                + " " + (distortionEnabled ? "applied" : "OFF (pinhole)") + "\n"
                + "Refinement: " + (refinementEnabled ? "on" : "off (sensor poses)") + "\n"
                + "Seams: " + (seamEnabled ? "on (carved)" : "off (blend)") + "\n"
                + "Frames (capture order):\n" + poses;
    }

    private CameraSelector widestCameraSelector(Context context) {
        return CameraSelector.DEFAULT_BACK_CAMERA;
    }

    private void bindInstructionSteps(View overlay) {
        bindStep(overlay.findViewById(R.id.step_1), 1, R.string.capture_step_1, R.string.capture_instructions_1);
        bindStep(overlay.findViewById(R.id.step_2), 2, R.string.capture_step_2, R.string.capture_instructions_2);
        bindStep(overlay.findViewById(R.id.step_3), 3, R.string.capture_step_3, R.string.capture_instructions_3);
        bindStep(overlay.findViewById(R.id.step_4), 4, R.string.capture_step_4, R.string.capture_instructions_4);
    }

    private void bindStep(View step, int number, int label, int detail) {
        ((TextView) step.findViewById(R.id.step_number)).setText(String.valueOf(number));
        ((TextView) step.findViewById(R.id.step_label)).setText(label);
        ((TextView) step.findViewById(R.id.step_detail)).setText(detail);
    }

    private void applyHudInsets(View root) {
        final View top = root.findViewById(R.id.capture_top_hud);
        final View bottom = root.findViewById(R.id.capture_bottom_hud);
        ViewCompat.setOnApplyWindowInsetsListener(root, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                top.setPadding(top.getPaddingLeft(), insets.getSystemWindowInsetTop() + dp(8),
                        top.getPaddingRight(), top.getPaddingBottom());
                bottom.setPadding(
                        bottom.getPaddingLeft(),
                        bottom.getPaddingTop(),
                        bottom.getPaddingRight(),
                        insets.getSystemWindowInsetBottom() + dp(16)
                );
                return insets;
            }
        });
    }

    private void showSnackbar(String message) {
        if (snackbarText == null) {
            return;
        }
        snackbarText.setText(message);
        snackbarText.setVisibility(View.VISIBLE);
        if (snackbarHide != null) {
            mainHandler.removeCallbacks(snackbarHide);
        }
        snackbarHide = new Runnable() {
            @Override
            public void run() {
                snackbarText.setVisibility(View.GONE);
            }
        };
        mainHandler.postDelayed(snackbarHide, 4000);
    }

    private Host host() {
        if (getActivity() instanceof Host) {
            return (Host) getActivity();
        }
        return null;
    }

    private static String messageOf(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : "";
    }

    private static float coerceIn(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
