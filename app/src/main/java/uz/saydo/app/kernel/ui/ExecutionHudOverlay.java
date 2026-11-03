package uz.saydo.app.kernel.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating Micro-HUD Overlay Pill & Emergency Abort Indicator.
 * Renders macro progress at top of screen and handles instant user touch aborts.
 */
public final class ExecutionHudOverlay {

    public interface AbortCallback {
        void onEmergencyAbort();
    }

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View hudView;
    private TextView titleView;
    private TextView stepView;
    private AbortCallback abortCallback;
    private boolean isShowing = false;

    public ExecutionHudOverlay(Context context) {
        this.context = (context != null) ? context.getApplicationContext() : null;
        this.windowManager = (this.context != null) ? (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE) : null;
    }

    public void setAbortCallback(AbortCallback callback) {
        this.abortCallback = callback;
    }

    public void show(String macroName, int currentStep, int totalSteps) {
        if (context == null || windowManager == null) return;
        mainHandler.post(() -> {
            if (hudView == null) {
                initView();
            }
            updateText(macroName, currentStep, totalSteps);
            if (!isShowing) {
                int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                params.y = 48;

                try {
                    windowManager.addView(hudView, params);
                    isShowing = true;
                } catch (Throwable t) {
                    android.util.Log.e("ExecutionHudOverlay", "Failed to add HUD overlay", t);
                }
            }
        });
    }

    public void update(String macroName, int currentStep, int totalSteps) {
        mainHandler.post(() -> updateText(macroName, currentStep, totalSteps));
    }

    private void updateText(String macroName, int currentStep, int totalSteps) {
        if (titleView != null) {
            titleView.setText(macroName != null ? macroName : "Executing Macro");
        }
        if (stepView != null) {
            stepView.setText(String.format("Step %d/%d (Tap to Abort)", currentStep, totalSteps));
        }
    }

    private void initView() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int padH = 32;
        int padV = 16;
        layout.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor("#E612141C")); // Dark translucent pill
        bg.setCornerRadius(40f);
        bg.setStroke(2, Color.parseColor("#384260"));
        layout.setBackground(bg);

        titleView = new TextView(context);
        titleView.setTextColor(Color.parseColor("#FFFFFF"));
        titleView.setTextSize(13f);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        layout.addView(titleView);

        stepView = new TextView(context);
        stepView.setTextColor(Color.parseColor("#8E9BB5"));
        stepView.setTextSize(11f);
        layout.addView(stepView);

        layout.setOnClickListener(v -> {
            dismiss();
            if (abortCallback != null) {
                abortCallback.onEmergencyAbort();
            }
        });

        hudView = layout;
    }

    public void dismiss() {
        mainHandler.post(() -> {
            if (isShowing && hudView != null && windowManager != null) {
                try {
                    windowManager.removeView(hudView);
                } catch (Throwable ignored) {
                }
                isShowing = false;
            }
        });
    }

    public boolean isShowing() {
        return isShowing;
    }
}
