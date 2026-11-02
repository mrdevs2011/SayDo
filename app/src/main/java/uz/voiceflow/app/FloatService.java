package uz.voiceflow.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * OS darajasidagi suzuvchi Start/Stop tugmasi + yozish qatlami.
 * - Start: trigger so'raydi (TriggerActivity), keyin yozishni boshlaydi
 * - Yozishda: barmoq ostida nuqta/chiziq, ko'tarilganda yo'qoladi
 * - Stop: makroni saqlaydi
 * - Uzoq bosish: ilovani ochadi
 * - Sudrash: joyini o'zgartirish
 */
public class FloatService extends Service {

    static final String ACTION_SHOW = "uz.voiceflow.app.FLOAT_SHOW";
    static final String ACTION_HIDE = "uz.voiceflow.app.FLOAT_HIDE";
    static final String ACTION_REFRESH = "uz.voiceflow.app.FLOAT_REFRESH";
    static final String ACTION_START_REC = "uz.voiceflow.app.FLOAT_START_REC";
    static final String EXTRA_TRIGGER = "trigger";

    static volatile boolean showing = false;
    static volatile boolean recording = false;

    private static final int SIZE_DP = 58;
    private static final int MARGIN_DP = 10;
    private static final int MAX_GESTURES = 200;
    private static final int MAX_PATH_POINTS = 500;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View bubble;
    private TextView bubbleTxt;
    private GradientDrawable bubbleBg;
    private WindowManager.LayoutParams bubbleLp;

    private RecordView recordView;
    private WindowManager.LayoutParams recordLp;

    private int sizePx;
    private float dens;
    private float downX, downY;
    private int startX, startY;
    private boolean moved;
    private long downAt;

    private String pendingTrigger = "";
    private final List<JSONObject> gestures = new ArrayList<JSONObject>();
    private long recStartElapsed;
    private JSONObject curStroke;
    private JSONArray curPath;
    private long strokeStartElapsed;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        dens = getResources().getDisplayMetrics().density;
        sizePx = Math.round(SIZE_DP * dens);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_HIDE.equals(action)) {
            stopRecording(false);
            removeBubble();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!Settings.canDrawOverlays(this)) {
            stopRecording(false);
            removeBubble();
            showing = false;
            recording = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START_REC.equals(action)) {
            String t = intent.getStringExtra(EXTRA_TRIGGER);
            if (t != null && t.trim().length() > 0) {
                if (bubble == null) addBubble();
                beginRecording(t.trim());
            }
            showing = true;
            return START_STICKY;
        }

        if (bubble == null) addBubble();
        else refreshBubble();
        showing = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRecording(false);
        removeBubble();
        showing = false;
        recording = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ============================================================ bubble

    private void addBubble() {
        if (bubble != null) return;

        FrameLayout root = new FrameLayout(this);
        root.setClickable(true);

        bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(Color.parseColor("#34C759"));
        root.setBackground(bubbleBg);
        root.setElevation(8 * dens);

        bubbleTxt = new TextView(this);
        bubbleTxt.setText("START");
        bubbleTxt.setTextColor(Color.WHITE);
        bubbleTxt.setTextSize(11);
        bubbleTxt.setGravity(Gravity.CENTER);
        bubbleTxt.setTypeface(bubbleTxt.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(bubbleTxt, new FrameLayout.LayoutParams(sizePx, sizePx));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleLp = new WindowManager.LayoutParams(
                sizePx, sizePx, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int margin = Math.round(MARGIN_DP * dens);
        bubbleLp.x = screenW - sizePx - margin;
        bubbleLp.y = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);

        root.setOnTouchListener(bubbleTouch);
        bubble = root;

        try {
            wm.addView(bubble, bubbleLp);
            refreshBubble();
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

    private void refreshBubble() {
        if (bubbleBg == null || bubbleTxt == null) return;
        if (recording) {
            bubbleBg.setColor(Color.parseColor("#FF3B30"));
            bubbleTxt.setText("STOP");
        } else {
            bubbleBg.setColor(Color.parseColor("#34C759"));
            bubbleTxt.setText("START");
        }
    }

    private final View.OnTouchListener bubbleTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = bubbleLp.x;
                    startY = bubbleLp.y;
                    moved = false;
                    downAt = SystemClock.uptimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!moved && (dx * dx + dy * dy) > 64) moved = true;
                    if (moved) {
                        bubbleLp.x = startX + Math.round(dx);
                        bubbleLp.y = startY + Math.round(dy);
                        try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!moved) {
                        long held = SystemClock.uptimeMillis() - downAt;
                        if (held >= 550) openApp();
                        else onBubbleTap();
                    } else {
                        snapBubble();
                    }
                    return true;
            }
            return false;
        }
    };

    private void snapBubble() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int margin = Math.round(MARGIN_DP * dens);
        if (bubbleLp.x + sizePx / 2 < screenW / 2) bubbleLp.x = margin;
        else bubbleLp.x = screenW - sizePx - margin;
        if (bubbleLp.y < margin) bubbleLp.y = margin;
        int maxY = getResources().getDisplayMetrics().heightPixels - sizePx - margin;
        if (bubbleLp.y > maxY) bubbleLp.y = maxY;
        try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) { }
    }

    private void onBubbleTap() {
        if (recording) {
            stopRecording(true);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Overlay ruxsati yo'q");
            return;
        }
        if (!Store.isEnabled(this)) {
            toast("Avval ON qiling");
            return;
        }
        try {
            Intent i = new Intent(this, TriggerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            toast("Ochib bo'lmadi");
        }
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    // ============================================================ recording

    private void beginRecording(String trigger) {
        if (trigger == null) return;
        trigger = trigger.trim();
        if (trigger.isEmpty()) return;
        if (trigger.length() > 60) trigger = trigger.substring(0, 60);
        if (recording) stopRecording(false);
        pendingTrigger = trigger;
        gestures.clear();
        curStroke = null;
        curPath = null;
        recStartElapsed = SystemClock.uptimeMillis();
        recording = true;
        refreshBubble();
        addRecordLayer();
        toast("Yozilmoqda: " + trigger);
    }

    private void stopRecording(boolean save) {
        if (!recording && recordView == null) return;
        finishStroke();
        recording = false;
        removeRecordLayer();
        refreshBubble();

        if (save && !gestures.isEmpty() && pendingTrigger.length() > 0) {
            try {
                JSONArray arr = new JSONArray(Store.getMacros(this));
                JSONObject m = new JSONObject();
                m.put("trigger", pendingTrigger);
                JSONArray gArr = new JSONArray();
                for (JSONObject g : gestures) gArr.put(g);
                m.put("gestures", gArr);
                long dur = 0;
                if (!gestures.isEmpty()) {
                    JSONObject last = gestures.get(gestures.size() - 1);
                    JSONObject first = gestures.get(0);
                    dur = Math.max(0, last.optLong("et", 0) - first.optLong("st", 0));
                }
                m.put("dur", dur);
                m.put("ts", System.currentTimeMillis());
                arr.put(m);
                Store.saveMacros(this, arr.toString());
                toast("Saqlandi: " + pendingTrigger);
            } catch (Exception e) {
                toast("Saqlashda xato");
            }
        } else if (save) {
            toast("Harakat yozilmadi");
        }
        pendingTrigger = "";
        gestures.clear();
    }

    private void addRecordLayer() {
        if (recordView != null) return;
        recordView = new RecordView(this);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        recordLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        recordLp.gravity = Gravity.TOP | Gravity.START;

        try {
            wm.addView(recordView, recordLp);
            // Bubble ni eng yuqorida qoldirish
            if (bubble != null) {
                try {
                    wm.removeView(bubble);
                    wm.addView(bubble, bubbleLp);
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            recordView = null;
            recording = false;
            refreshBubble();
            toast("Yozish qatlamini ochib bo'lmadi");
        }
    }

    private void removeRecordLayer() {
        if (recordView != null && wm != null) {
            try { wm.removeView(recordView); } catch (Exception ignored) { }
        }
        recordView = null;
    }

    private void finishStroke() {
        if (curStroke == null) return;
        try {
            long now = SystemClock.uptimeMillis() - recStartElapsed;
            curStroke.put("et", now);
            curStroke.put("dur", Math.max(0, now - strokeStartElapsed));
            if (curPath != null && curPath.length() > 1) {
                curStroke.put("type", "swipe");
                curStroke.put("path", curPath);
            } else {
                long d = now - strokeStartElapsed;
                curStroke.put("type", d >= 400 ? "hold" : "tap");
            }
            if (gestures.size() < MAX_GESTURES) {
                gestures.add(curStroke);
            }
        } catch (Exception ignored) { }
        curStroke = null;
        curPath = null;
        if (recordView != null) recordView.clearVisual();
    }

    // ============================================================ record view

    private final class RecordView extends View {
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path livePath = new Path();
        private float lastX, lastY;
        private boolean fingerDown;

        RecordView(Context ctx) {
            super(ctx);
            setBackgroundColor(Color.argb(30, 0, 0, 0)); // biroz qorong'i — yozayotgani ko'rinsin
            setClickable(true);
            dotPaint.setColor(Color.argb(220, 0, 122, 255));
            dotPaint.setStyle(Paint.Style.FILL);
            linePaint.setColor(Color.argb(200, 0, 122, 255));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(6f * dens);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
        }

        void clearVisual() {
            fingerDown = false;
            livePath.reset();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (!fingerDown) return;
            if (!livePath.isEmpty()) {
                c.drawPath(livePath, linePaint);
            }
            c.drawCircle(lastX, lastY, 10f * dens, dotPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            // Bubble hududiga tegsa — o'tkazib yubormaymiz, lekin bubble o'zi yuqorida
            float x = e.getRawX();
            float y = e.getRawY();
            int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
            int h = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
            double nx = clamp01(x / (double) w);
            double ny = clamp01(y / (double) h);
            long tRel = SystemClock.uptimeMillis() - recStartElapsed;

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    finishStroke();
                    fingerDown = true;
                    lastX = x;
                    lastY = y;
                    livePath.reset();
                    livePath.moveTo(x, y);
                    strokeStartElapsed = SystemClock.uptimeMillis() - recStartElapsed;
                    try {
                        curStroke = new JSONObject();
                        curStroke.put("x", nx);
                        curStroke.put("y", ny);
                        curStroke.put("st", strokeStartElapsed);
                        curPath = new JSONArray();
                        JSONObject p0 = new JSONObject();
                        p0.put("x", nx);
                        p0.put("y", ny);
                        p0.put("t", 0);
                        curPath.put(p0);
                    } catch (Exception ignored) { }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!fingerDown) return true;
                    lastX = x;
                    lastY = y;
                    livePath.lineTo(x, y);
                    if (curPath != null && curPath.length() < MAX_PATH_POINTS) {
                        try {
                            JSONObject p = new JSONObject();
                            p.put("x", nx);
                            p.put("y", ny);
                            p.put("t", Math.max(0, tRel - strokeStartElapsed));
                            curPath.put(p);
                        } catch (Exception ignored) { }
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (fingerDown) {
                        finishStroke();
                        fingerDown = false;
                        livePath.reset();
                        invalidate();
                    }
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private double clamp01(double v) {
            if (v < 0) return 0;
            if (v > 1) return 1;
            return v;
        }
    }

    // ============================================================ helpers

    private void toast(final String msg) {
        h.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    static boolean canOverlay(Context ctx) {
        return Settings.canDrawOverlays(ctx);
    }

    static void show(Context ctx) {
        ctx.startService(new Intent(ctx, FloatService.class).setAction(ACTION_SHOW));
    }

    static void hide(Context ctx) {
        ctx.startService(new Intent(ctx, FloatService.class).setAction(ACTION_HIDE));
    }

    static void refresh(Context ctx) {
        if (!showing) return;
        ctx.startService(new Intent(ctx, FloatService.class).setAction(ACTION_REFRESH));
    }

    static void startRecording(Context ctx, String trigger) {
        Intent i = new Intent(ctx, FloatService.class)
                .setAction(ACTION_START_REC)
                .putExtra(EXTRA_TRIGGER, trigger);
        ctx.startService(i);
    }
}
