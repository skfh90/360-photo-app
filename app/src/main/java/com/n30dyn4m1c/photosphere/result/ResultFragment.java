package com.n30dyn4m1c.photosphere.result;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.n30dyn4m1c.photosphere.BuildConfig;
import com.n30dyn4m1c.photosphere.R;
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher;
import com.n30dyn4m1c.photosphere.storage.MediaExporter;
import com.n30dyn4m1c.photosphere.storage.SphereImageStore;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Preview and keep/share/discard a finished cached sphere.
 */
public class ResultFragment extends Fragment {

    public interface Host {
        SphereImageStore.StitchedSphere getSphere();
        void onTakeAnother();
        void onCaptureRequested();
    }

    private static final String TAG = "PanoramaResult";
    private static final int PREVIEW_MAX_DIMENSION = 2048;
    private static final int MODE_SPHERE = 0;
    private static final int MODE_FLAT = 1;
    private static final int EXPORT_IDLE = 0;
    private static final int EXPORT_WORKING = 1;
    private static final int EXPORT_DONE = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private SphereGlView sphereGl;
    private ImageView previewFlat;
    private ProgressBar previewLoading;
    private TextView previewFailed;
    private TextView resultDimensions;
    private TextView diagnostics;
    private TextView viewerHint;
    private TextView modeSphere;
    private TextView modeFlat;
    private View exportButton;
    private ProgressBar exportProgress;
    private ImageView exportIcon;
    private TextView exportText;
    private View shareButton;
    private View anotherButton;
    private TextView exportLocation;
    private TextView snackbarText;
    private View header;
    private View bottom;

    private Bitmap preview;
    private boolean previewDecodeFailed;
    private int viewMode = MODE_SPHERE;
    private int exportState = EXPORT_IDLE;
    private String exportedDisplayName;
    private boolean showHint = true;
    private Dialog discardDialog;
    private Runnable snackbarHide;
    private Runnable hideHint;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_result, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View empty = view.findViewById(R.id.preview_empty);
        SphereImageStore.StitchedSphere sphere = sphere();
        if (sphere == null) {
            empty.setVisibility(View.VISIBLE);
            view.findViewById(R.id.preview_empty_action).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Host host = host();
                    if (host != null) {
                        host.onCaptureRequested();
                    }
                }
            });
            return;
        }
        empty.setVisibility(View.GONE);

        sphereGl = view.findViewById(R.id.sphere_gl);
        previewFlat = view.findViewById(R.id.preview_flat);
        previewLoading = view.findViewById(R.id.preview_loading);
        previewFailed = view.findViewById(R.id.preview_failed);
        resultDimensions = view.findViewById(R.id.result_dimensions);
        diagnostics = view.findViewById(R.id.result_diagnostics);
        viewerHint = view.findViewById(R.id.viewer_hint);
        modeSphere = view.findViewById(R.id.mode_sphere);
        modeFlat = view.findViewById(R.id.mode_flat);
        exportButton = view.findViewById(R.id.button_export);
        exportProgress = view.findViewById(R.id.export_progress);
        exportIcon = view.findViewById(R.id.export_icon);
        exportText = view.findViewById(R.id.export_text);
        shareButton = view.findViewById(R.id.button_share);
        anotherButton = view.findViewById(R.id.button_another);
        exportLocation = view.findViewById(R.id.export_location);
        snackbarText = view.findViewById(R.id.snackbar_text);
        header = view.findViewById(R.id.result_header);
        bottom = view.findViewById(R.id.result_bottom);

        resultDimensions.setText(
                getString(R.string.result_dimensions, sphere.width, sphere.height)
        );
        if (BuildConfig.DEBUG && sphere.diagnostics != null) {
            diagnostics.setVisibility(View.VISIBLE);
            diagnostics.setText(sphere.diagnostics);
        }
        applyInsets(view);

        sphereGl.setLookListener(new SphereGlView.LookListener() {
            @Override
            public void onLook() {
                hideHintNow();
            }
        });
        modeSphere.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setViewMode(MODE_SPHERE);
            }
        });
        modeFlat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setViewMode(MODE_FLAT);
            }
        });
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        });
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!shareSphere(requireContext(), sphere().file)) {
                    showSnackbar(getString(R.string.result_share_failed));
                }
            }
        });
        anotherButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                leave();
            }
        });

        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        leave();
                    }
                }
        );

        hideHint = new Runnable() {
            @Override
            public void run() {
                hideHintNow();
            }
        };
        mainHandler.postDelayed(hideHint, 4000);
        decodePreview(sphere.file);
        updateChrome();
    }

    @Override
    public void onDestroyView() {
        if (hideHint != null) {
            mainHandler.removeCallbacks(hideHint);
        }
        if (snackbarHide != null) {
            mainHandler.removeCallbacks(snackbarHide);
        }
        if (discardDialog != null && discardDialog.isShowing()) {
            discardDialog.dismiss();
        }
        ioExecutor.shutdownNow();
        super.onDestroyView();
    }

    private void decodePreview(final File file) {
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap decoded = decodePreviewFile(file);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) {
                            return;
                        }
                        if (decoded == null) {
                            previewDecodeFailed = true;
                            Log.w(TAG, "Could not decode a preview of " + file.getName());
                        } else {
                            preview = decoded;
                            SphereImageStore.StitchedSphere sphere = sphere();
                            if (sphere != null) {
                                sphereGl.setSphere(decoded, SphereViewCrop.from(sphere.gpano));
                            }
                        }
                        updateChrome();
                    }
                });
            }
        });
    }

    private void startExport() {
        if (exportState != EXPORT_IDLE) {
            return;
        }
        final SphereImageStore.StitchedSphere sphere = sphere();
        if (sphere == null) {
            return;
        }
        exportState = EXPORT_WORKING;
        updateChrome();
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final MediaExporter.Result result = MediaExporter.export(
                        requireContext(),
                        sphere.file,
                        Integer.valueOf(sphere.width),
                        Integer.valueOf(sphere.height)
                );
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded()) {
                            return;
                        }
                        if (result.isSuccess()) {
                            exportState = EXPORT_DONE;
                            exportedDisplayName = result.getPanorama().displayName;
                            showSnackbar(getString(
                                    R.string.result_export_success,
                                    result.getPanorama().relativePath
                            ));
                        } else {
                            exportState = EXPORT_IDLE;
                            showSnackbar(getString(
                                    R.string.result_export_failed,
                                    result.getError() != null ? result.getError().getMessage() : ""
                            ));
                        }
                        updateChrome();
                    }
                });
            }
        });
    }

    private void leave() {
        Host host = host();
        if (host != null) {
            host.onCaptureRequested();
        }
    }

    private void showDiscardDialog() {
        if (discardDialog != null && discardDialog.isShowing()) {
            return;
        }
        discardDialog = new Dialog(requireContext(), R.style.Theme_PhotoSphere_Dialog);
        discardDialog.setContentView(R.layout.dialog_discard);
        discardDialog.setCancelable(true);
        discardDialog.findViewById(R.id.discard_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                discardDialog.dismiss();
            }
        });
        discardDialog.findViewById(R.id.discard_confirm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                discardDialog.dismiss();
                Host host = host();
                if (host != null) {
                    host.onTakeAnother();
                }
            }
        });
        discardDialog.show();
    }

    private void setViewMode(int mode) {
        viewMode = mode;
        updateChrome();
    }

    private void hideHintNow() {
        showHint = false;
        if (viewerHint != null) {
            viewerHint.setVisibility(View.GONE);
        }
    }

    private void updateChrome() {
        if (!isAdded() || modeSphere == null) {
            return;
        }
        boolean sphereMode = viewMode == MODE_SPHERE;
        modeSphere.setSelected(sphereMode);
        modeFlat.setSelected(!sphereMode);
        modeSphere.setTextColor(ContextCompat.getColor(
                requireContext(),
                sphereMode ? R.color.sphere_background : R.color.glass_content
        ));
        modeFlat.setTextColor(ContextCompat.getColor(
                requireContext(),
                !sphereMode ? R.color.sphere_background : R.color.glass_content
        ));

        if (preview != null) {
            previewLoading.setVisibility(View.GONE);
            previewFailed.setVisibility(View.GONE);
            sphereGl.setVisibility(sphereMode ? View.VISIBLE : View.GONE);
            previewFlat.setVisibility(sphereMode ? View.GONE : View.VISIBLE);
            if (!sphereMode) {
                previewFlat.setImageBitmap(preview);
            }
        } else if (previewDecodeFailed) {
            previewLoading.setVisibility(View.GONE);
            previewFailed.setVisibility(View.VISIBLE);
            sphereGl.setVisibility(View.GONE);
            previewFlat.setVisibility(View.GONE);
        } else {
            previewLoading.setVisibility(View.VISIBLE);
            previewFailed.setVisibility(View.GONE);
        }

        viewerHint.setVisibility(
                showHint && sphereMode && preview != null ? View.VISIBLE : View.GONE
        );
        sphereGl.setContentDescription(getString(
                sphereMode
                        ? R.string.result_preview_description
                        : R.string.result_preview_flat_description
        ));

        boolean working = exportState == EXPORT_WORKING;
        boolean done = exportState == EXPORT_DONE;
        exportButton.setEnabled(!working && !done);
        exportButton.setClickable(!working && !done);
        shareButton.setEnabled(!working);
        anotherButton.setEnabled(!working);
        exportProgress.setVisibility(working ? View.VISIBLE : View.GONE);
        exportIcon.setVisibility(working ? View.GONE : View.VISIBLE);
        if (done) {
            exportIcon.setImageResource(R.drawable.ic_check);
            exportText.setText(R.string.result_exported);
            exportLocation.setVisibility(View.VISIBLE);
            exportLocation.setText(getString(
                    R.string.result_export_location,
                    MediaExporter.RELATIVE_PATH,
                    exportedDisplayName
            ));
        } else if (working) {
            exportText.setText(R.string.result_exporting);
            exportLocation.setVisibility(View.GONE);
        } else {
            exportIcon.setImageResource(R.drawable.ic_gallery);
            exportText.setText(R.string.result_export);
            exportLocation.setVisibility(View.GONE);
        }
    }

    private boolean shareSphere(Context context, File file) {
        android.net.Uri uri;
        try {
            uri = SphereImageStore.shareUri(context, file);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "No FileProvider path covers " + file.getName(), e);
            return false;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri(null, uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.result_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Nothing on this device can receive an image", e);
            return false;
        }
    }

    private static Bitmap decodePreviewFile(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = PhotoSphereStitcher.sampleSizeFor(
                bounds.outWidth,
                bounds.outHeight,
                PREVIEW_MAX_DIMENSION
        );
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                header.setPadding(
                        header.getPaddingLeft(),
                        insets.getSystemWindowInsetTop() + dp(8),
                        header.getPaddingRight(),
                        header.getPaddingBottom()
                );
                bottom.setPadding(
                        bottom.getPaddingLeft(),
                        bottom.getPaddingTop(),
                        bottom.getPaddingRight(),
                        dp(8)
                );
                return insets;
            }
        });
    }

    private void showSnackbar(String message) {
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

    private SphereImageStore.StitchedSphere sphere() {
        Host host = host();
        return host != null ? host.getSphere() : null;
    }

    private Host host() {
        if (getActivity() instanceof Host) {
            return (Host) getActivity();
        }
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
