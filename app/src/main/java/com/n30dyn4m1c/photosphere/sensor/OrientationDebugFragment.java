package com.n30dyn4m1c.photosphere.sensor;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.n30dyn4m1c.photosphere.R;

/**
 * Developer screen for the live {@link OrientationTracker} readout.
 */
public class OrientationDebugFragment extends Fragment {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OrientationTracker tracker;
    private long sampleCount;
    private AttitudeIndicatorView attitude;
    private TextView sensorUnavailable;
    private View statusCard;
    private TextView statusAccuracy;
    private TextView statusSamples;
    private TextView statusTimestamp;
    private AngleReadout yaw;
    private AngleReadout pitch;
    private AngleReadout roll;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_orientation_debug, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tracker = new OrientationTracker(requireContext());
        tracker.setDisplayRotation(DisplayRotation.current(requireContext()));

        attitude = view.findViewById(R.id.attitude_indicator);
        sensorUnavailable = view.findViewById(R.id.sensor_unavailable);
        statusCard = view.findViewById(R.id.status_card);
        statusAccuracy = view.findViewById(R.id.status_accuracy);
        statusSamples = view.findViewById(R.id.status_samples);
        statusTimestamp = view.findViewById(R.id.status_timestamp);
        yaw = new AngleReadout(view.findViewById(R.id.yaw_readout));
        pitch = new AngleReadout(view.findViewById(R.id.pitch_readout));
        roll = new AngleReadout(view.findViewById(R.id.roll_readout));

        yaw.bind(getString(R.string.orientation_yaw), getString(R.string.orientation_yaw_caption), 180f);
        pitch.bind(getString(R.string.orientation_pitch), getString(R.string.orientation_pitch_caption), 90f);
        roll.bind(getString(R.string.orientation_roll), getString(R.string.orientation_roll_caption), 180f);

        ViewCompat.setOnApplyWindowInsetsListener(view, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                v.setPadding(v.getPaddingLeft(), insets.getSystemWindowInsetTop() + dp(8),
                        v.getPaddingRight(), insets.getSystemWindowInsetBottom() + dp(8));
                return insets;
            }
        });

        boolean available = tracker.isSensorAvailable();
        sensorUnavailable.setVisibility(available ? View.GONE : View.VISIBLE);
        attitude.setVisibility(available ? View.VISIBLE : View.GONE);
        statusCard.setVisibility(available ? View.VISIBLE : View.GONE);
        yaw.root.setVisibility(available ? View.VISIBLE : View.GONE);
        pitch.root.setVisibility(available ? View.VISIBLE : View.GONE);
        roll.root.setVisibility(available ? View.VISIBLE : View.GONE);

        tracker.setListener(new OrientationTracker.Listener() {
            @Override
            public void onOrientationChanged(final OrientationData orientation) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        sampleCount++;
                        bind(orientation);
                    }
                });
            }
        });
        bind(tracker.getOrientation());
    }

    @Override
    public void onResume() {
        super.onResume();
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
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (tracker != null) {
            tracker.setListener(null);
        }
        super.onDestroyView();
    }

    private void bind(OrientationData orientation) {
        if (!isAdded()) {
            return;
        }
        attitude.setOrientation(orientation);
        yaw.setDegrees(orientation.yawDegrees);
        pitch.setDegrees(orientation.pitchDegrees);
        roll.setDegrees(orientation.rollDegrees);
        statusAccuracy.setText(orientation.accuracy.name());
        statusSamples.setText(String.valueOf(sampleCount));
        if (orientation.hasFix()) {
            statusTimestamp.setText(getString(
                    R.string.orientation_timestamp_value,
                    orientation.timestampNanos / 1_000_000.0
            ));
        } else {
            statusTimestamp.setText(R.string.orientation_waiting);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AngleReadout {
        final View root;
        final TextView label;
        final TextView value;
        final TextView caption;
        final AngleBarView bar;

        AngleReadout(View root) {
            this.root = root;
            this.label = root.findViewById(R.id.angle_label);
            this.value = root.findViewById(R.id.angle_value);
            this.caption = root.findViewById(R.id.angle_caption);
            this.bar = root.findViewById(R.id.angle_bar);
        }

        void bind(String title, String captionText, float range) {
            label.setText(title);
            caption.setText(captionText);
            bar.setRange(range);
        }

        void setDegrees(float degrees) {
            value.setText(root.getResources().getString(R.string.orientation_degrees, degrees));
            bar.setDegrees(degrees);
        }
    }
}
