package uz.voiceflow.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Haqiqiy OS darajasidagi suzuvchi tugma (TYPE_APPLICATION_OVERLAY).
 * Boshqa ilovalar ustida turadi. Bosilsa — tinglashni yoqadi/o'chiradi.
 * Uzoq bosilsa — ilovani ochadi.
 */
public class FloatService extends Service {

    static final String ACTION_SHOW = "uz.voiceflow.app.FLOAT_SHOW";
    static final String ACTION_HIDE = "uz.voiceflow.app.FLOAT_HIDE";
    static final String ACTION_REFRESH = "uz.voiceflow.app.FLOAT_REFRESH";

    static volatile boolean showing = false;

    private static final int SIZE_DP = 56;
    private static final int MARGIN_DP = 12;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams lp;
    private ImageView icon;
    private GradientDrawable bg;

    private int sizePx;
    private float downX, downY;
    private int startX, startY;
    private boolean moved;
    private long downAt;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        float dens = getResources().getDisplayMetrics().density;
        sizePx = Math.round(SIZE_DP * dens);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_HIDE.equals(action)) {
            removeBubble();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (bubble == null) addBubble();
        else refreshAppearance();
        showing = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeBubble();
        showing = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- UI

    private void addBubble() {
        if (bubble != null) return;

        FrameLayout root = new FrameLayout(this);
        root.setClickable(true);

        bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#2C2C2E"));

        icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_stat_mic);
        icon.setColorFilter(Color.WHITE);
        int pad = Math.round(14 * getResources().getDisplayMetrics().density);
        icon.setPadding(pad, pad, pad, pad);

        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(sizePx, sizePx);
        root.addView(icon, ilp);
        root.setBackground(bg);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                sizePx, sizePx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int margin = Math.round(MARGIN_DP * getResources().getDisplayMetrics().density);
        lp.x = screenW - sizePx - margin;
        lp.y = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);

        root.setOnTouchListener(touchListener);
        bubble = root;

        try {
            wm.addView(bubble, lp);
            refreshAppearance();
        } catch (Exception e) {
            bubble = null;
            stopSelf();
        }
    }

    private void removeBubble() {
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) { }
        }
        bubble = null;
        showing = false;
    }

    private void refreshAppearance() {
        if (bg == null || icon == null) return;
        boolean on = VoiceService.running;
        bg.setColor(Color.parseColor(on ? "#34C759" : "#2C2C2E"));
        icon.setColorFilter(Color.WHITE);
        if (bubble != null) bubble.setAlpha(on ? 1f : 0.92f);
    }

    // ---------------------------------------------------------------- touch

    private final View.OnTouchListener touchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    downAt = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!moved && (dx * dx + dy * dy) > 36) moved = true;
                    if (moved) {
                        lp.x = startX + Math.round(dx);
                        lp.y = startY + Math.round(dy);
                        try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!moved) {
                        long held = System.currentTimeMillis() - downAt;
                        if (held >= 500) openApp();
                        else toggleListen();
                    } else {
                        snapToEdge();
                    }
                    return true;
            }
            return false;
        }
    };

    private void snapToEdge() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int margin = Math.round(MARGIN_DP * getResources().getDisplayMetrics().density);
        if (lp.x + sizePx / 2 < screenW / 2) lp.x = margin;
        else lp.x = screenW - sizePx - margin;
        if (lp.y < margin) lp.y = margin;
        int maxY = getResources().getDisplayMetrics().heightPixels - sizePx - margin;
        if (lp.y > maxY) lp.y = maxY;
        try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- actions

    private void toggleListen() {
        if (VoiceService.running) {
            stopService(new Intent(this, VoiceService.class));
            VoiceService.running = false;
            refreshAppearance();
        } else {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(new Intent(this, VoiceService.class));
                } else {
                    startService(new Intent(this, VoiceService.class));
                }
            } catch (Exception ignored) { }
            // VoiceService start bo'lgach yashil qilish uchun biroz kutamiz
            h.postDelayed(new Runnable() {
                @Override public void run() { refreshAppearance(); }
            }, 400);
            h.postDelayed(new Runnable() {
                @Override public void run() { refreshAppearance(); }
            }, 1200);
        }
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    // ---------------------------------------------------------------- public helpers

    static boolean canOverlay(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    static void show(Context ctx) {
        Intent i = new Intent(ctx, FloatService.class).setAction(ACTION_SHOW);
        ctx.startService(i);
    }

    static void hide(Context ctx) {
        Intent i = new Intent(ctx, FloatService.class).setAction(ACTION_HIDE);
        ctx.startService(i);
    }

    static void refresh(Context ctx) {
        if (!showing) return;
        Intent i = new Intent(ctx, FloatService.class).setAction(ACTION_REFRESH);
        ctx.startService(i);
    }
}
