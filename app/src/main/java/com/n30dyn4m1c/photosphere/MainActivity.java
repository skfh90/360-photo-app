package com.n30dyn4m1c.photosphere;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.n30dyn4m1c.photosphere.camera.CaptureFragment;
import com.n30dyn4m1c.photosphere.result.ResultFragment;
import com.n30dyn4m1c.photosphere.sensor.OrientationDebugFragment;
import com.n30dyn4m1c.photosphere.storage.SphereImageStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Permission gate, then capture or the finished-sphere result. A DEBUG button
 * opens the orientation readout beside that gate.
 */
public class MainActivity extends AppCompatActivity
        implements CaptureFragment.Host, ResultFragment.Host {

    public static final int REQUEST_PERMISSIONS = 4101;

    private static final int STATUS_UNKNOWN = 0;
    private static final int STATUS_GRANTED = 1;
    private static final int STATUS_RATIONALE = 2;
    private static final int STATUS_PERMANENT = 3;

    private Button debugButton;
    private int permissionStatus = STATUS_UNKNOWN;
    private boolean hasRequested;
    private boolean showOrientationDebug;
    private SphereImageStore.StitchedSphere sphere;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        setContentView(R.layout.activity_main);

        debugButton = findViewById(R.id.button_orientation_debug);
        if (BuildConfig.DEBUG) {
            debugButton.setVisibility(View.VISIBLE);
            debugButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showOrientationDebug = !showOrientationDebug;
                    showContent();
                }
            });
            ViewCompat.setOnApplyWindowInsetsListener(debugButton, new androidx.core.view.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                    v.setPadding(v.getPaddingLeft(), insets.getSystemWindowInsetTop() + dp(4),
                            v.getPaddingRight(), v.getPaddingBottom());
                    return insets;
                }
            });
        }

        if (hasAllPermissions(requiredPermissions())) {
            permissionStatus = STATUS_GRANTED;
        }
        showContent();
        if (permissionStatus != STATUS_GRANTED && !hasRequested) {
            hasRequested = true;
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasAllPermissions(requiredPermissions())) {
            permissionStatus = STATUS_GRANTED;
            if (!showOrientationDebug && !(currentFragment() instanceof ResultFragment)
                    && !(currentFragment() instanceof CaptureFragment)) {
                showContent();
            } else if (currentFragment() instanceof PermissionFragment) {
                showContent();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        if (hasAllPermissions(requiredPermissions())) {
            permissionStatus = STATUS_GRANTED;
        } else if (grantResults.length == 0) {
            permissionStatus = STATUS_RATIONALE;
        } else if (shouldShowAnyRationale(requiredPermissions())) {
            permissionStatus = STATUS_RATIONALE;
        } else {
            permissionStatus = STATUS_PERMANENT;
        }
        showContent();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onSphereReady(SphereImageStore.StitchedSphere stitched) {
        sphere = stitched;
        showContent();
    }

    @Override
    public SphereImageStore.StitchedSphere getSphere() {
        return sphere;
    }

    @Override
    public void onTakeAnother() {
        discardSphere();
        showContent();
    }

    private void discardSphere() {
        SphereImageStore.StitchedSphere finished = sphere;
        sphere = null;
        if (finished == null) {
            return;
        }
        final File file = finished.file;
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                SphereImageStore.deleteCachedSphere(file);
            }
        });
    }

    private void showContent() {
        if (debugButton != null && BuildConfig.DEBUG) {
            debugButton.setText(showOrientationDebug
                    ? R.string.orientation_debug_close
                    : R.string.orientation_debug_open);
        }
        Fragment next;
        if (showOrientationDebug) {
            next = new OrientationDebugFragment();
        } else if (permissionStatus == STATUS_GRANTED) {
            if (sphere == null) {
                next = new CaptureFragment();
            } else {
                next = new ResultFragment();
            }
        } else {
            next = PermissionFragment.newInstance(permissionStatus);
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_host, next)
                .commit();
    }

    private Fragment currentFragment() {
        return getSupportFragmentManager().findFragmentById(R.id.fragment_host);
    }

    void requestRuntimePermissions() {
        hasRequested = true;
        requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
    }

    void openAppSettings() {
        startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    static String[] requiredPermissions() {
        List<String> permissions = new ArrayList<String>();
        permissions.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return permissions.toArray(new String[0]);
    }

    private boolean hasAllPermissions(String[] permissions) {
        for (int i = 0; i < permissions.length; i++) {
            if (ContextCompat.checkSelfPermission(this, permissions[i])
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldShowAnyRationale(String[] permissions) {
        for (int i = 0; i < permissions.length; i++) {
            if (shouldShowRequestPermissionRationale(permissions[i])) {
                return true;
            }
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Front-door permission screen shown until the camera grant lands.
     */
    public static class PermissionFragment extends Fragment {

        private static final String ARG_STATUS = "status";

        static PermissionFragment newInstance(int status) {
            PermissionFragment fragment = new PermissionFragment();
            Bundle args = new Bundle();
            args.putInt(ARG_STATUS, status);
            fragment.setArguments(args);
            return fragment;
        }

        public PermissionFragment() {
            super(R.layout.fragment_permission);
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            int status = getArguments() != null ? getArguments().getInt(ARG_STATUS) : STATUS_UNKNOWN;
            TextView message = view.findViewById(R.id.permission_message);
            Button action = view.findViewById(R.id.permission_action);
            final MainActivity activity = (MainActivity) requireActivity();
            if (status == STATUS_PERMANENT) {
                message.setText(R.string.permission_camera_denied_permanently);
                action.setText(R.string.permission_open_settings);
                action.setVisibility(View.VISIBLE);
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activity.openAppSettings();
                    }
                });
            } else if (status == STATUS_RATIONALE) {
                message.setText(R.string.permission_camera_rationale);
                action.setText(R.string.permission_grant);
                action.setVisibility(View.VISIBLE);
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activity.requestRuntimePermissions();
                    }
                });
            } else {
                message.setText(R.string.permission_camera_rationale);
                action.setVisibility(View.GONE);
            }
        }
    }
}
