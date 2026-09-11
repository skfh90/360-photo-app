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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.n30dyn4m1c.photosphere.camera.CaptureFragment;
import com.n30dyn4m1c.photosphere.result.ResultFragment;
import com.n30dyn4m1c.photosphere.rooms.RoomsFragment;
import com.n30dyn4m1c.photosphere.sensor.OrientationDebugFragment;
import com.n30dyn4m1c.photosphere.storage.RoomLibrary;
import com.n30dyn4m1c.photosphere.storage.SphereImageStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Permission gate, then Capture / Rooms / Preview with a persistent bottom bar.
 */
public class MainActivity extends AppCompatActivity
        implements CaptureFragment.Host, ResultFragment.Host, RoomsFragment.Host {

    public static final int REQUEST_PERMISSIONS = 4101;

    public static final int TAB_CAPTURE = 0;
    public static final int TAB_ROOMS = 1;
    public static final int TAB_PREVIEW = 2;

    private static final int STATUS_UNKNOWN = 0;
    private static final int STATUS_GRANTED = 1;
    private static final int STATUS_RATIONALE = 2;
    private static final int STATUS_PERMANENT = 3;

    private static final String STATE_TAB = "main_tab";
    private static final String STATE_SPHERE = "main_sphere";
    private static final String TAG_CAPTURE = "tab_capture";
    private static final String TAG_ROOMS = "tab_rooms";
    private static final String TAG_PREVIEW = "tab_preview";
    private static final String TAG_PERMISSION = "tab_permission";
    private static final String TAG_DEBUG = "tab_debug";

    private Button debugButton;
    private View bottomNav;
    private View navCapture;
    private View navRooms;
    private View navPreview;
    private int permissionStatus = STATUS_UNKNOWN;
    private boolean hasRequested;
    private boolean showOrientationDebug;
    private int currentTab = TAB_CAPTURE;
    private SphereImageStore.StitchedSphere sphere;
    private String previewIdentity = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyEdgeToEdge(true);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, TAB_CAPTURE);
            String path = savedInstanceState.getString(STATE_SPHERE);
            if (path != null) {
                File file = new File(path);
                if (file.isFile()) {
                    sphere = RoomLibrary.open(file);
                }
            }
        }

        bottomNav = findViewById(R.id.bottom_nav);
        navCapture = findViewById(R.id.nav_capture);
        navRooms = findViewById(R.id.nav_rooms);
        navPreview = findViewById(R.id.nav_preview);
        bindNavItem(navCapture, R.drawable.ic_nav_capture, R.string.nav_capture);
        bindNavItem(navRooms, R.drawable.ic_nav_rooms, R.string.nav_rooms);
        bindNavItem(navPreview, R.drawable.ic_nav_preview, R.string.nav_preview);
        navCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_CAPTURE);
            }
        });
        navRooms.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_ROOMS);
            }
        });
        navPreview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_PREVIEW);
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
        if (sphere != null && sphere.file != null) {
            outState.putString(STATE_SPHERE, sphere.file.getAbsolutePath());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasAllPermissions(requiredPermissions())) {
            permissionStatus = STATUS_GRANTED;
            if (currentFragment() instanceof PermissionFragment) {
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
    public void onSphereReady(SphereImageStore.StitchedSphere stitched) {
        try {
            sphere = RoomLibrary.archive(this, stitched);
        } catch (IOException e) {
            sphere = stitched;
        }
        selectTab(TAB_PREVIEW);
    }

    @Override
    public SphereImageStore.StitchedSphere getSphere() {
        return sphere;
    }

    @Override
    public void onTakeAnother() {
        onCaptureRequested();
    }

    @Override
    public void onCaptureRequested() {
        selectTab(TAB_CAPTURE);
    }

    @Override
    public void onRoomSelected(SphereImageStore.StitchedSphere room) {
        sphere = room;
        selectTab(TAB_PREVIEW);
    }

    private void selectTab(int tab) {
        currentTab = tab;
        showOrientationDebug = false;
        showContent();
    }

    private void showContent() {
        if (debugButton != null && BuildConfig.DEBUG) {
            debugButton.setText(showOrientationDebug
                    ? R.string.orientation_debug_close
                    : R.string.orientation_debug_open);
        }
        boolean showNav = permissionStatus == STATUS_GRANTED && !showOrientationDebug;
        if (bottomNav != null) {
            bottomNav.setVisibility(showNav ? View.VISIBLE : View.GONE);
        }
        bindNavSelection();
        applyEdgeToEdge(currentTab != TAB_CAPTURE && currentTab != TAB_PREVIEW
                || permissionStatus != STATUS_GRANTED
                || (currentTab == TAB_PREVIEW && sphere == null));

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        if (showOrientationDebug) {
            hide(tx, fm, TAG_CAPTURE, TAG_ROOMS, TAG_PREVIEW, TAG_PERMISSION);
            showOrAdd(tx, fm, TAG_DEBUG, new OrientationDebugFragment());
        } else if (permissionStatus != STATUS_GRANTED) {
            hide(tx, fm, TAG_CAPTURE, TAG_ROOMS, TAG_PREVIEW, TAG_DEBUG);
            showOrAdd(tx, fm, TAG_PERMISSION, PermissionFragment.newInstance(permissionStatus));
        } else {
            hide(tx, fm, TAG_PERMISSION, TAG_DEBUG);
            ensureTab(tx, fm, TAG_CAPTURE, new CaptureFragment(), currentTab == TAB_CAPTURE);
            ensureTab(tx, fm, TAG_ROOMS, new RoomsFragment(), currentTab == TAB_ROOMS);
            String identity = sphere != null && sphere.file != null
                    ? sphere.file.getAbsolutePath()
                    : "empty";
            Fragment preview = fm.findFragmentByTag(TAG_PREVIEW);
            if (preview != null && !identity.equals(previewIdentity)) {
                tx.remove(preview);
                preview = null;
            }
            previewIdentity = identity;
            if (preview == null) {
                preview = new ResultFragment();
                tx.add(R.id.fragment_host, preview, TAG_PREVIEW);
            }
            setTabVisible(tx, preview, currentTab == TAB_PREVIEW);
        }
        tx.commit();
    }

    private void ensureTab(
            FragmentTransaction tx,
            FragmentManager fm,
            String tag,
            Fragment created,
            boolean visible
    ) {
        Fragment existing = fm.findFragmentByTag(tag);
        if (existing == null) {
            tx.add(R.id.fragment_host, created, tag);
            existing = created;
        }
        setTabVisible(tx, existing, visible);
    }

    private void showOrAdd(FragmentTransaction tx, FragmentManager fm, String tag, Fragment created) {
        Fragment existing = fm.findFragmentByTag(tag);
        if (existing == null) {
            tx.add(R.id.fragment_host, created, tag);
            existing = created;
        }
        tx.show(existing);
    }

    private void hide(FragmentTransaction tx, FragmentManager fm, String... tags) {
        for (int i = 0; i < tags.length; i++) {
            Fragment fragment = fm.findFragmentByTag(tags[i]);
            if (fragment != null) {
                tx.hide(fragment);
            }
        }
    }

    private void setTabVisible(FragmentTransaction tx, Fragment fragment, boolean visible) {
        if (fragment == null) {
            return;
        }
        if (visible) {
            tx.show(fragment);
        } else {
            tx.hide(fragment);
        }
    }

    private void bindNavItem(View item, int icon, int label) {
        ((ImageView) item.findViewById(R.id.nav_icon)).setImageResource(icon);
        ((TextView) item.findViewById(R.id.nav_label)).setText(label);
    }

    private void bindNavSelection() {
        styleNavItem(navCapture, currentTab == TAB_CAPTURE);
        styleNavItem(navRooms, currentTab == TAB_ROOMS);
        styleNavItem(navPreview, currentTab == TAB_PREVIEW);
    }

    private void styleNavItem(View item, boolean selected) {
        if (item == null) {
            return;
        }
        int color = ContextCompat.getColor(this, selected
                ? R.color.sphere_accent
                : R.color.sphere_on_surface_variant);
        ImageView icon = item.findViewById(R.id.nav_icon);
        TextView label = item.findViewById(R.id.nav_label);
        icon.setColorFilter(color);
        label.setTextColor(color);
        label.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void applyEdgeToEdge(boolean lightIcons) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (lightIcons && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
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
