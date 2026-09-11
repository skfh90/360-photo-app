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
 * Dark cinematic launch: a glowing globe spins once, the wordmark fades in,
 * then capture starts. Configuration changes skip the replay.
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
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE);
        }
        if (savedInstanceState != null) {
            openMain(false);
            return;
        }

        setContentView(R.layout.activity_splash);
        final SplashGlobeView globe = findViewById(R.id.splash_globe);
        final View wordmark = findViewById(R.id.splash_wordmark);
        globe.setPose(0f, 0f);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                float appear = clamp((t - 0.02f) / 0.22f);
                float spin = clamp(t / 0.78f);
                globe.setPose(spin, appear);
                float title = clamp((t - 0.42f) / 0.28f);
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
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
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
