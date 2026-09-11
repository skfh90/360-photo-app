package com.n30dyn4m1c.photosphere.splash;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;

import com.n30dyn4m1c.photosphere.MainActivity;
import com.n30dyn4m1c.photosphere.R;

/**
 * Light interior launch: a room drawing eases in, the wordmark fades up,
 * then the gallery starts. Configuration changes skip the replay.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long DURATION_MS = 2200L;

    private ValueAnimator animator;
    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        if (savedInstanceState != null) {
            openMain(false);
            return;
        }

        setContentView(R.layout.activity_splash);
        final SplashRoomView room = findViewById(R.id.splash_room);
        final View wordmark = findViewById(R.id.splash_wordmark);
        room.setPose(0f, 0f);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                float appear = clamp((t - 0.04f) / 0.36f);
                room.setPose(t, appear);
                float title = clamp((t - 0.40f) / 0.28f);
                wordmark.setAlpha(title);
                wordmark.setTranslationY((1f - title) * dp(14));
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                openMain(true);
            }
        });
        animator.start();
    }

    @Override
    protected void onDestroy() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDestroy();
    }

    private void openMain(boolean animate) {
        if (launched) {
            return;
        }
        launched = true;
        startActivity(new Intent(this, MainActivity.class));
        if (animate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.ECLAIR) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
        finish();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}
