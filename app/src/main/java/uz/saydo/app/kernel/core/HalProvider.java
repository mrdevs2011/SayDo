package uz.saydo.app.kernel.core;

import android.content.Context;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * Hardware Abstraction Layer (HAL) Provider for SayDo Micro-Kernel.
 * Abstracts display metrics, power wake locks, haptics, and audio hardware.
 */
public final class HalProvider {

    private final Context context;
    private final WindowManager windowManager;
    private final PowerManager powerManager;
    private final Vibrator vibrator;

    public HalProvider(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        this.vibrator = (Vibrator) this.context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public int getScreenWidth() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return dm != null ? dm.widthPixels : 1080;
    }

    public int getScreenHeight() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return dm != null ? dm.heightPixels : 2400;
    }

    public float getScreenDensity() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return dm != null ? dm.density : 2.5f;
    }

    public int dpToPx(int dp) {
        return Math.round(dp * getScreenDensity());
    }

    public void vibrateTick() {
        if (vibrator == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(20);
            }
        } catch (Throwable ignored) {
        }
    }

    public void vibrateConfirm() {
        if (vibrator == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 30, 60, 40}, -1));
            } else {
                vibrator.vibrate(new long[]{0, 30, 60, 40}, -1);
            }
        } catch (Throwable ignored) {
        }
    }

    public void vibrateAbort() {
        if (vibrator == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 50, 40, 50}, -1));
            } else {
                vibrator.vibrate(new long[]{0, 50, 40, 50}, -1);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isScreenOn() {
        if (powerManager == null) return true;
        try {
            return powerManager.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    public Context getContext() {
        return context;
    }
}
