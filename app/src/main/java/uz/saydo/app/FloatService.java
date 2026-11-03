package uz.saydo.app;

import android.animation.ValueAnimator;
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
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

    static final String ACTION_SHOW = "uz.saydo.app.FLOAT_SHOW";
    static final String ACTION_HIDE = "uz.saydo.app.FLOAT_HIDE";
    static final String ACTION_REFRESH = "uz.saydo.app.FLOAT_REFRESH";
    static final String ACTION_START_REC = "uz.saydo.app.FLOAT_START_REC";
    static final String ACTION_REBUILD = "uz.saydo.app.FLOAT_REBUILD";
    static final String EXTRA_TRIGGER = "trigger";

    static volatile boolean showing = false;
    static volatile boolean recording = false;

    private static final int SIZE_DP = 58;
    private static final int MARGIN_DP = 10;
    /** Devor tomonga shuncha (tugma o'lchamining ulushi) itarilsa, yarmi devorga kirib ketadi. */
    private static final float DOCK_PUSH_FRACTION = 0.30f;
    private static final int MAX_GESTURES = 200;
    private static final int MAX_PATH_POINTS = 500;

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View bubble;
    private TextView bubbleTxt;
    private GradientDrawable bubbleBg;
    private WindowManager.LayoutParams bubbleLp;
    private WindowManager bubbleWm;
    private WindowManager recordWm;
    private WindowManager cancelWm;
    private ValueAnimator dockAnim;

    private RecordView recordView;
    private WindowManager.LayoutParams recordLp;

    private View triggerPanel;
    private WindowManager.LayoutParams triggerLp;
    private EditText triggerInput;

    private View cancelZone;
    private WindowManager.LayoutParams cancelLp;
    private boolean cancelArmed;

    private int sizePx;
    private float dens;
    private float downX, downY;
    private int startX, startY;
    private boolean moved;
    private long downAt;

    private String pendingTrigger = "";
    /** Yozish qulf ekranidan boshlandimi (qulf ekranida yozilgan makro qulf ekranidan ijro etiladi). */
    private boolean lockedRec = false;
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

        if (!Net.isOnline(this)) {
            // Internet yo'q: float ham chiqmaydi
            stopRecording(false);
            hideTriggerPanel();
            removeBubble();
            showing = false;
            recording = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_REBUILD.equals(action)) {
            if (bubble != null) {
                removeBubble();
                addBubble();
                showing = true;
            }
            return START_STICKY;
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
                startRecordingFlow(t.trim());
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
        hideTriggerPanel();
        hideCancelZone();
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

        // Accessibility qatlami lock screen ustida ham ko'rinadi
        bubbleWm = overlayWm();
        bubbleLp = new WindowManager.LayoutParams(
                sizePx, sizePx, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
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
            bubbleWm.addView(bubble, bubbleLp);
            refreshBubble();
        } catch (Exception e) {
            bubble = null;
            stopSelf();
        }
    }

    private void removeBubble() {
        if (dockAnim != null) { dockAnim.cancel(); dockAnim = null; }
        if (bubble != null) {
            WindowManager w = bubbleWm != null ? bubbleWm : wm;
            try { w.removeView(bubble); } catch (Exception ignored) { }
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
                    if (dockAnim != null) dockAnim.cancel();
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = bubbleLp.x;
                    startY = bubbleLp.y;
                    moved = false;
                    cancelArmed = false;
                    downAt = SystemClock.uptimeMillis();
                    showCancelZone();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!moved && (dx * dx + dy * dy) > 64) moved = true;
                    if (moved) {
                        int half = sizePx / 2;
                        int nx = startX + Math.round(dx);
                        // Yarmigacha devorga kirishi mumkin, undan ortiq emas
                        bubbleLp.x = Math.max(-half, Math.min(screenW() - sizePx + half, nx));
                        bubbleLp.y = startY + Math.round(dy);
                        updateBubble();
                        cancelArmed = isOverCancelZone(e.getRawX(), e.getRawY());
                        refreshCancelZone();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean overCancel = cancelArmed || isOverCancelZone(e.getRawX(), e.getRawY());
                    hideCancelZone();
                    if (overCancel && moved) {
                        onCancelDrop();
                        return true;
                    }
                    if (!moved) {
                        long held = SystemClock.uptimeMillis() - downAt;
                        if (held >= 550) openApp();
                        else onBubbleTap();
                    } else {
                        snapBubble();
                    }
                    return true;
                }
            }
            return false;
        }
    };

    /**
     * Qo'yib yuborilganda: oddiy holatda to'liq ko'rinadigan chetga yopishadi.
     * Faqat devor tomonga qasddan (DOCK_PUSH_FRACTION dan ko'proq) itarilsa, 50% devorga kirib ketadi.
     */
    private void snapBubble() {
        int sw = screenW();
        int sh = screenH();
        int margin = Math.round(MARGIN_DP * dens);
        int half = sizePx / 2;
        float overLeft = -bubbleLp.x;
        float overRight = bubbleLp.x + sizePx - sw;
        float deliberate = sizePx * DOCK_PUSH_FRACTION;

        int targetX;
        if (overLeft >= deliberate) targetX = -half;
        else if (overRight >= deliberate) targetX = sw - half;
        else if (bubbleLp.x + sizePx / 2 < sw / 2) targetX = margin;
        else targetX = sw - sizePx - margin;

        int maxY = sh - sizePx - margin;
        int targetY = Math.max(margin, Math.min(maxY, bubbleLp.y));
        animateBubbleTo(targetX, targetY);
    }

    private void animateBubbleTo(final int tx, final int ty) {
        if (bubble == null) return;
        if (dockAnim != null) dockAnim.cancel();
        final int sx = bubbleLp.x;
        final int sy = bubbleLp.y;
        dockAnim = ValueAnimator.ofFloat(0f, 1f);
        dockAnim.setDuration(180);
        dockAnim.setInterpolator(new DecelerateInterpolator());
        dockAnim.addUpdateListener(a -> {
            if (bubble == null) return;
            float f = (float) a.getAnimatedValue();
            bubbleLp.x = sx + Math.round((tx - sx) * f);
            bubbleLp.y = sy + Math.round((ty - sy) * f);
            updateBubble();
        });
        dockAnim.start();
    }

    private void updateBubble() {
        if (bubble == null) return;
        WindowManager w = bubbleWm != null ? bubbleWm : wm;
        try { w.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) { }
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private void onBubbleTap() {
        if (recording) {
            stopRecording(true);
            return;
        }
        if (!Net.isOnline(this)) {
            hide(this);
            return;
        }
        if (!Store.isEnabled(this)) {
            toast("Turn ON first");
            return;
        }
        // To'liq sozlanmagan bo'lsa, trigger so'ralishidan oldin kerakli sozlamaga o'tkazadi
        int miss = Setup.missing(this);
        if (miss != Setup.OK) {
            Setup.route(this, miss);
            return;
        }
        if (Setup.isLocked(this)) {
            openTriggerActivity();
            return;
        }
        showTriggerPanel();
    }

    /** Qulf ekranida trigger nomi qulf ustidagi Activity orqali kiritiladi. */
    private void openTriggerActivity() {
        try {
            startActivity(new Intent(this, TriggerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            toast("Could not open");
        }
    }

    /** Trigger nomi kiritilgach: har doim Home'ga o'tadi va faqat shundan keyin yozishni boshlaydi. */
    private void startRecordingFlow(String trigger) {
        String t = trigger == null ? "" : trigger.trim();
        if (t.isEmpty()) return;
        if (t.length() > 60) t = t.substring(0, 60);
        if (!Net.isOnline(this)) {
            hide(this);
            return;
        }
        if (isTriggerTaken(t)) {
            toast("Already used: \"" + t + "\"");
            return;
        }
        GestureService g = GestureService.instance;
        if (g == null) {
            Setup.route(this, Setup.NEED_ACCESSIBILITY);
            return;
        }
        final String name = t;
        if (Setup.isLocked(this)) {
            // Qulf ekrani: Home kerak emas. Yozish qulf ekranining o'zidan boshlanadi,
            // slide va parol kiritish ham yoziladi.
            lockedRec = true;
            beginRecording(name);
            return;
        }
        lockedRec = false;
        g.goHomeThen(new Runnable() {
            @Override public void run() {
                if (bubble == null) return;
                beginRecording(name);
            }
        });
    }

    private void onCancelDrop() {
        // Drag to X: cancel recording / close panel / turn OFF
        if (recording) {
            stopRecording(false);
            toast("Cancelled");
            return;
        }
        if (triggerPanel != null) {
            hideTriggerPanel();
            toast("Cancelled");
            return;
        }
        // Turn master OFF and hide float
        Store.setEnabled(this, false);
        try { stopService(new Intent(this, VoiceService.class)); } catch (Exception ignored) { }
        VoiceService.running = false;
        hide(this);
        toast("SayDo OFF");
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
        if (isTriggerTaken(trigger)) {
            toast("Already used: \"" + trigger + "\"");
            return;
        }
        if (GestureService.instance == null) {
            toast("Turn on SayDo in Accessibility first");
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
            return;
        }
        if (recording) stopRecording(false);
        pendingTrigger = trigger;
        gestures.clear();
        curStroke = null;
        curPath = null;
        recStartElapsed = SystemClock.uptimeMillis();
        recording = true;
        refreshBubble();
        addRecordLayer();
        toast("Recording: " + trigger);
    }

    private void stopRecording(boolean save) {
        if (!recording && recordView == null) return;
        finishStroke(false, false);
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
                m.put("locked", lockedRec);
                m.put("ts", System.currentTimeMillis());
                arr.put(m);
                Store.saveMacros(this, arr.toString());
                toast("Saved: " + pendingTrigger);
            } catch (Exception e) {
                toast("Save failed");
            }
        } else if (save) {
            toast("No actions recorded");
        }
        pendingTrigger = "";
        lockedRec = false;
        gestures.clear();
    }

    private void addRecordLayer() {
        if (recordView != null) return;
        recordView = new RecordView(this);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        recordWm = overlayWm();
        recordLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        recordLp.gravity = Gravity.TOP | Gravity.START;

        try {
            recordWm.addView(recordView, recordLp);
            // Bubble ni eng yuqorida qoldirish
            if (bubble != null) {
                try {
                    bubbleWm.removeView(bubble);
                    bubbleWm.addView(bubble, bubbleLp);
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            recordView = null;
            recording = false;
            refreshBubble();
            toast("Could not open recording overlay");
        }
    }

    private void removeRecordLayer() {
        if (recordView != null) {
            WindowManager w = recordWm != null ? recordWm : wm;
            try { w.removeView(recordView); } catch (Exception ignored) { }
        }
        recordView = null;
    }

    /**
     * Joriy chiziqni gest sifatida saqlaydi.
     * @param cancelled tizim (orqaga/uy swipe) chiziqni o'zi olib qo'ygan bo'lsa true
     * @param inject    true bo'lsa, gest tagidagi ilovaga ham yuboriladi (pass-through)
     */
    private void finishStroke(boolean cancelled, boolean inject) {
        if (curStroke == null) return;
        JSONObject saved = null;
        try {
            long now = SystemClock.uptimeMillis() - recStartElapsed;
            long d = Math.max(0, now - strokeStartElapsed);
            curStroke.put("et", now);
            curStroke.put("dur", d);
            String globalAction = cancelled
                    ? edgeAction(curStroke.optDouble("x", 0.5), curStroke.optDouble("y", 0.5), d)
                    : null;
            if (globalAction != null) {
                curStroke.put("type", "global");
                curStroke.put("action", globalAction);
            } else if (curPath != null && curPath.length() > 1) {
                curStroke.put("type", "swipe");
                curStroke.put("path", curPath);
            } else {
                curStroke.put("type", d >= 400 ? "hold" : "tap");
            }
            if (gestures.size() < MAX_GESTURES) {
                gestures.add(curStroke);
                saved = curStroke;
            }
        } catch (Exception ignored) { }
        curStroke = null;
        curPath = null;
        if (recordView != null) recordView.clearVisual();
        if (inject && saved != null && recording) passThrough(saved);
    }

    /** Ekran chetidan boshlangan va tizim olib qo'ygan swipe: orqaga / uy / so'nggi ilovalar. */
    private String edgeAction(double nx, double ny, long durMs) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float px = (float) (nx * dm.widthPixels);
        float py = (float) (ny * dm.heightPixels);
        if (py > dm.heightPixels - 64f * dens) return durMs >= 450 ? "recents" : "home";
        if (px < 32f * dens || px > dm.widthPixels - 32f * dens) return "back";
        return null;
    }

    // ---- pass-through: yozish qatlami ushlab olgan bosishni tagidagi ilovaga o'tkazish

    private int passToken = 0;

    private void setRecordTouchable(boolean touchable) {
        if (recordView == null || recordLp == null) return;
        if (touchable) recordLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else recordLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(recordView, recordLp); } catch (Exception ignored) { }
    }

    private void passThrough(JSONObject stroke) {
        GestureService svc = GestureService.instance;
        if (svc == null || recordView == null) return;
        final int tok = ++passToken;
        setRecordTouchable(false);
        boolean ok = svc.injectNow(stroke, new Runnable() {
            @Override public void run() {
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (tok == passToken) setRecordTouchable(true);
                    }
                }, 30);
            }
        });
        if (!ok) {
            setRecordTouchable(true);
            return;
        }
        // Xavfsizlik: callback kelmasa ham qatlam qayta tegiladigan bo'ladi
        long guard = Math.max(stroke.optLong("dur", 60), 60) + 1200;
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (tok == passToken) setRecordTouchable(true);
            }
        }, guard);
    }

    // ============================================================ record view

    private final class RecordView extends View {
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path livePath = new Path();
        private float lastX, lastY;
        private boolean fingerDown;

        RecordView(Context ctx) {
            super(ctx);
            setBackgroundColor(Color.TRANSPARENT); // ilova ko'rinib tursin
            setClickable(true);
            borderPaint.setColor(Color.argb(210, 255, 59, 48));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(3f * dens);
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
            float bw = borderPaint.getStrokeWidth() / 2f;
            c.drawRect(bw, bw, getWidth() - bw, getHeight() - bw, borderPaint);
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
                    finishStroke(false, false);
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
                        finishStroke(e.getActionMasked() == MotionEvent.ACTION_CANCEL,
                                e.getActionMasked() == MotionEvent.ACTION_UP);
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


    // ============================================================ trigger overlay (no Activity)

    private void showTriggerPanel() {
        if (triggerPanel != null) return;
        if (wm == null) return;

        float d = dens;
        FrameLayout root = new FrameLayout(this);
        root.setClickable(true);
        root.setBackgroundColor(Color.argb(180, 15, 31, 82));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        card.setPadding(pad, pad, pad, Math.round(16 * d));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(Color.parseColor("#1B3A8C"));
        cbg.setCornerRadius(18 * d);
        cbg.setStroke(Math.round(1 * d), Color.parseColor("#2A4A9E"));
        card.setBackground(cbg);

        TextView title = new TextView(this);
        title.setText("Enter your voice trigger");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, Math.round(12 * d));
        card.addView(title);

        triggerInput = new EditText(this);
        triggerInput.setHint("e.g. open youtube");
        triggerInput.setHintTextColor(Color.parseColor("#A8C0E8"));
        triggerInput.setTextColor(Color.WHITE);
        triggerInput.setSingleLine(true);
        triggerInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        triggerInput.setTextSize(16);
        triggerInput.setPadding(Math.round(14 * d), Math.round(14 * d), Math.round(14 * d), Math.round(14 * d));
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(Color.parseColor("#152868"));
        ibg.setCornerRadius(12 * d);
        ibg.setStroke(Math.round(1 * d), Color.parseColor("#2A4A9E"));
        triggerInput.setBackground(ibg);
        card.addView(triggerInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button go = new Button(this);
        go.setText("Start recording");
        go.setAllCaps(false);
        go.setTextColor(Color.WHITE);
        go.setTextSize(15);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(Color.parseColor("#22C55E"));
        gbg.setCornerRadius(12 * d);
        go.setBackground(gbg);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Math.round(12 * d);
        card.addView(go, glp);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setTextColor(Color.parseColor("#A8C0E8"));
        cancel.setTextSize(14);
        cancel.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Math.round(2 * d);
        card.addView(cancel, clp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        int side = Math.round(28 * d);
        cardLp.leftMargin = side;
        cardLp.rightMargin = side;
        root.addView(card, cardLp);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        triggerLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        triggerLp.dimAmount = 0.45f;
        triggerLp.gravity = Gravity.CENTER;
        triggerLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        Runnable submit = new Runnable() {
            @Override public void run() {
                if (triggerInput == null) return;
                String phrase = triggerInput.getText() != null
                        ? triggerInput.getText().toString().trim() : "";
                if (phrase.isEmpty()) {
                    toast("Enter a phrase");
                    return;
                }
                if (isTriggerTaken(phrase)) {
                    toast("Already used: \"" + phrase + "\"");
                    return;
                }
                hideTriggerPanel();
                startRecordingFlow(phrase);
            }
        };
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submit.run(); }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideTriggerPanel(); }
        });
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideTriggerPanel(); }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { }
        });
        triggerInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit.run();
                    return true;
                }
                return false;
            }
        });

        try {
            wm.addView(root, triggerLp);
            triggerPanel = root;
            triggerInput.requestFocus();
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null && triggerInput != null) {
                            imm.showSoftInput(triggerInput, InputMethodManager.SHOW_IMPLICIT);
                        }
                    } catch (Exception ignored) { }
                }
            }, 120);
        } catch (Exception e) {
            triggerPanel = null;
            toast("Could not open");
        }
    }

    private void hideTriggerPanel() {
        if (triggerPanel != null && wm != null) {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null && triggerInput != null) {
                    imm.hideSoftInputFromWindow(triggerInput.getWindowToken(), 0);
                }
            } catch (Exception ignored) { }
            try { wm.removeView(triggerPanel); } catch (Exception ignored) { }
        }
        triggerPanel = null;
        triggerInput = null;
        triggerLp = null;
    }

    private void showCancelZone() {
        if (cancelZone != null || wm == null) return;
        int zone = Math.round(64 * dens);
        TextView x = new TextView(this);
        x.setText("\u2715");
        x.setTextColor(Color.WHITE);
        x.setTextSize(28);
        x.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#E11D48"));
        x.setBackground(bg);
        x.setElevation(10 * dens);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        cancelWm = overlayWm();
        cancelLp = new WindowManager.LayoutParams(
                zone, zone, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        cancelLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cancelLp.y = Math.round(36 * dens);

        try {
            cancelWm.addView(x, cancelLp);
            cancelZone = x;
            cancelArmed = false;
        } catch (Exception e) {
            cancelZone = null;
        }
    }

    private void hideCancelZone() {
        if (cancelZone != null) {
            WindowManager w = cancelWm != null ? cancelWm : wm;
            try { w.removeView(cancelZone); } catch (Exception ignored) { }
        }
        cancelZone = null;
        cancelLp = null;
        cancelArmed = false;
    }

    private void refreshCancelZone() {
        if (cancelZone == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(cancelArmed ? Color.parseColor("#BE123C") : Color.parseColor("#E11D48"));
        cancelZone.setBackground(bg);
        cancelZone.setScaleX(cancelArmed ? 1.15f : 1f);
        cancelZone.setScaleY(cancelArmed ? 1.15f : 1f);
    }

    private boolean isOverCancelZone(float rawX, float rawY) {
        if (cancelZone == null) return false;
        int[] loc = new int[2];
        cancelZone.getLocationOnScreen(loc);
        int left = loc[0];
        int top = loc[1];
        int right = left + cancelZone.getWidth();
        int bottom = top + cancelZone.getHeight();
        int pad = Math.round(12 * dens);
        return rawX >= left - pad && rawX <= right + pad && rawY >= top - pad && rawY <= bottom + pad;
    }

    private boolean isTriggerTaken(String phrase) {
        if (phrase == null) return false;
        String want = TriggerMatcher.norm(phrase);
        if (want.isEmpty()) return false;
        try {
            JSONArray arr = new JSONArray(Store.getMacros(this));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (want.equals(TriggerMatcher.norm(o.optString("trigger", "")))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** Accessibility yoqilgan bo'lsa uning oynasi (lock screen ustida ham ko'rinadi), aks holda oddiy overlay. */
    private WindowManager overlayWm() {
        GestureService g = GestureService.instance;
        if (g != null) {
            try {
                WindowManager w = (WindowManager) g.getSystemService(WINDOW_SERVICE);
                if (w != null) return w;
            } catch (Exception ignored) { }
        }
        return wm;
    }

    private int overlayType() {
        if (GestureService.instance != null) return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

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

    /** Accessibility yoqilsa/o'chsa floatni mos oyna turida qayta quradi. */
    static void rebuild(Context ctx) {
        if (!showing) return;
        try {
            ctx.startService(new Intent(ctx, FloatService.class).setAction(ACTION_REBUILD));
        } catch (Exception ignored) { }
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
