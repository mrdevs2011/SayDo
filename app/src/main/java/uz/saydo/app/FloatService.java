package uz.saydo.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
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
import java.util.UUID;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.util.DebugLog;
import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.data.SettingsRepository;

/**
 * OS-level Floating Control & Gesture Capture Overlay.
 * Features edge docking physics, half-dock auto-collapse into translucent pill,
 * multi-device cutout safe area awareness, and zero-latency pass-through event dispatching.
 */
public class FloatService extends Service {
    private static final String TAG = "FloatService";

    static final String ACTION_SHOW = "uz.saydo.app.FLOAT_SHOW";
    static final String ACTION_HIDE = "uz.saydo.app.FLOAT_HIDE";
    static final String ACTION_REFRESH = "uz.saydo.app.FLOAT_REFRESH";
    static final String ACTION_START_REC = "uz.saydo.app.FLOAT_START_REC";
    static final String ACTION_REBUILD = "uz.saydo.app.FLOAT_REBUILD";
    static final String EXTRA_TRIGGER = "trigger";
    static final String EXTRA_RAW = "raw";

    public static volatile boolean showing = false;
    public static volatile boolean recording = false;

    private static final int SIZE_DP = 58;
    private static final int MARGIN_DP = 10;
    private static final float DOCK_PUSH_FRACTION = 0.30f;
    private static final int MAX_GESTURES = 200;
    private static final int MAX_PATH_POINTS = 2000;
    private static final long AUTO_COLLAPSE_DELAY_MS = 3500;

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

    private boolean isDockedCollapsed = false;

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
    /** True: record raw touches with the overlay (games, custom-drawn screens); false: record from accessibility events. */
    private boolean rawMode = false;
    private boolean pendingRaw = false;
    private EventRecorder evRec;
    private boolean lockedRec = false;
    private final List<JSONObject> gestures = new ArrayList<>();
    private long recStartElapsed;
    private JSONObject curStroke;
    private JSONArray curPath;
    private long strokeStartElapsed;

    private final Runnable autoCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            if (bubble == null || recording || isDockedCollapsed) return;
            collapseToPill();
        }
    };

    // While an MCP command runs the bubble must not swallow its taps (it sits on the right edge).
    private static volatile FloatService inst;
    private static volatile boolean mcpBusy;
    private final Runnable mcpBusyTimeout = () -> {
        mcpBusy = false;
        applyMcpBusy();
    };

    /** Makes the bubble untouchable (true) or touchable again (false). Safety: it comes back after safetyMs. */
    static void setMcpBusy(boolean busy, long safetyMs) {
        mcpBusy = busy;
        final FloatService s = inst;
        if (s == null) return;
        s.h.post(() -> {
            s.h.removeCallbacks(s.mcpBusyTimeout);
            if (busy) s.h.postDelayed(s.mcpBusyTimeout, safetyMs);
            s.applyMcpBusy();
        });
    }

    private void applyMcpBusy() {
        if (bubble == null || bubbleLp == null) return;
        if (mcpBusy && !recording) bubbleLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else bubbleLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        updateBubble();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
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
            if (t != null && !t.trim().isEmpty()) {
                pendingRaw = intent.getBooleanExtra(EXTRA_RAW, false);
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
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (bubble != null && bubbleLp != null) {
            int sw = screenW();
            int sh = screenH();
            int margin = Math.round(MARGIN_DP * dens);
            int half = sizePx / 2;
            bubbleLp.x = Math.max(-half, Math.min(sw - sizePx + half, bubbleLp.x));
            bubbleLp.y = Math.max(margin, Math.min(sh - sizePx - margin, bubbleLp.y));
            updateBubble();
            snapBubble();
        }
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        h.removeCallbacks(mcpBusyTimeout);
        h.removeCallbacks(autoCollapseRunnable);
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

    // ============================================================ Bubble Window Management

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

        bubbleWm = overlayWm();
        bubbleLp = new WindowManager.LayoutParams(
                sizePx, sizePx, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        if (mcpBusy) bubbleLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        int screenW = screenW();
        int margin = Math.round(MARGIN_DP * dens);
        bubbleLp.x = screenW - sizePx - margin;
        bubbleLp.y = Math.round(screenH() * 0.55f);

        root.setOnTouchListener(bubbleTouch);
        bubble = root;

        try {
            bubbleWm.addView(bubble, bubbleLp);
            refreshBubble();
            scheduleAutoCollapse();
        } catch (Throwable e) {
            Log.e(TAG, "Error adding bubble view", e);
            bubble = null;
            stopSelf();
        }
    }

    private void removeBubble() {
        h.removeCallbacks(autoCollapseRunnable);
        if (dockAnim != null) { dockAnim.cancel(); dockAnim = null; }
        if (bubble != null) {
            WindowManager w = bubbleWm != null ? bubbleWm : wm;
            try { w.removeView(bubble); } catch (Throwable ignored) { }
        }
        bubble = null;
        showing = false;
    }

    private void refreshBubble() {
        if (bubbleBg == null || bubbleTxt == null) return;
        if (recording) {
            bubbleBg.setColor(Color.parseColor("#FF3B30"));
            bubbleTxt.setText("STOP");
            expandFromPill();
        } else {
            bubbleBg.setColor(Color.parseColor("#34C759"));
            bubbleTxt.setText("START");
            scheduleAutoCollapse();
        }
    }

    private void collapseToPill() {
        if (bubble == null || recording) return;
        isDockedCollapsed = true;
        bubble.animate().alpha(0.55f).setDuration(250).start();
    }

    private void expandFromPill() {
        if (bubble == null) return;
        isDockedCollapsed = false;
        h.removeCallbacks(autoCollapseRunnable);
        bubble.animate().alpha(1.0f).setDuration(150).start();
    }

    private void scheduleAutoCollapse() {
        h.removeCallbacks(autoCollapseRunnable);
        h.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY_MS);
    }

    private final View.OnTouchListener bubbleTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    expandFromPill();
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
                    scheduleAutoCollapse();
                    return true;
                }
            }
            return false;
        }
    };

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
        dockAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                scheduleAutoCollapse();
            }
        });
        dockAnim.start();
    }

    private void updateBubble() {
        if (bubble == null) return;
        WindowManager w = bubbleWm != null ? bubbleWm : wm;
        try { w.updateViewLayout(bubble, bubbleLp); } catch (Throwable ignored) { }
    }

    private int screenW() {
        if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            try {
                Rect b = wm.getMaximumWindowMetrics().getBounds();
                return b.width();
            } catch (Throwable ignored) { }
        }
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int screenH() {
        if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            try {
                Rect b = wm.getMaximumWindowMetrics().getBounds();
                return b.height();
            } catch (Throwable ignored) { }
        }
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private void onBubbleTap() {
        if (recording) {
            stopRecording(true);
            return;
        }
        if (!SettingsRepository.getInstance(this).isMasterOn()) {
            toast("Turn ON first");
            return;
        }
        int miss = Setup.missing(this);
        if (miss != Setup.OK) {
            Setup.route(this, miss);
            return;
        }
        // Always return to Home first when START is pressed
        goHomeThenOpenTrigger();
    }

    private void goHomeThenOpenTrigger() {
        final Runnable open = () -> {
            if (bubble == null) return;
            if (Setup.isLocked(this)) {
                openTriggerActivity();
            } else {
                showTriggerPanel();
            }
        };
        GestureService g = GestureService.instance;
        if (g != null) {
            g.goHomeThen(open);
        } else {
            try {
                Intent home = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
            } catch (Throwable ignored) { }
            h.postDelayed(open, 350);
        }
    }

    private void openTriggerActivity() {
        try {
            startActivity(new Intent(this, TriggerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Throwable e) {
            toast("Could not open");
        }
    }

    private void startRecordingFlow(String trigger) {
        String t = trigger == null ? "" : trigger.trim();
        if (t.isEmpty()) return;
        if (t.length() > 60) t = t.substring(0, 60);
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
            lockedRec = true;
            beginRecording(name);
            return;
        }
        lockedRec = false;
        g.goHomeThen(() -> {
            if (bubble == null) return;
            beginRecording(name);
        });
    }

    private void onCancelDrop() {
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
        SettingsRepository.getInstance(this).setMasterOn(false);
        hide(this);
        toast("SayDo OFF");
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    // ============================================================ Recording & Persistence

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
            } catch (Throwable ignored) { }
            return;
        }
        if (recording) stopRecording(false);
        pendingTrigger = trigger;
        gestures.clear();
        curStroke = null;
        curPath = null;
        recStartElapsed = SystemClock.uptimeMillis();
        // From the lock screen no app events exist, so that is always raw.
        rawMode = pendingRaw || lockedRec;
        pendingRaw = false;
        recording = true;
        refreshBubble();
        if (rawMode) {
            addRecordLayer();
        } else {
            GestureService gs = GestureService.instance;
            evRec = new EventRecorder(gs);
            evRec.start();
            GestureService.recorder = evRec;
            gs.setRecordingEvents(true);
        }
        DebugLog.i("Rec", "start mode=" + (rawMode ? "raw" : "events"));
        toast("Recording: " + trigger);
    }

    private void stopRecording(boolean save) {
        if (!recording && recordView == null) return;
        finishStroke(false, false);
        recording = false;
        List<Gesture> eventSteps = null;
        if (evRec != null) {
            GestureService.recorder = null;
            eventSteps = evRec.stop();
            evRec = null;
            GestureService gs = GestureService.instance;
            if (gs != null) gs.setRecordingEvents(false);
        }
        removeRecordLayer();
        refreshBubble();
        int count = eventSteps != null ? eventSteps.size() : gestures.size();
        DebugLog.i("Rec", "stop save=" + save + " strokes=" + count + " trigger=" + pendingTrigger);

        if (save && count > 0 && !pendingTrigger.isEmpty()) {
            try {
                List<Gesture> domainGestures = new ArrayList<>();
                if (eventSteps != null) {
                    domainGestures.addAll(eventSteps);
                } else {
                    for (JSONObject g : gestures) {
                        Gesture dg = Gesture.fromJson(g);
                        if (dg != null) domainGestures.add(dg);
                    }
                }
                long dur = 0;
                if (!domainGestures.isEmpty()) {
                    dur = Math.max(0, domainGestures.get(domainGestures.size() - 1).endTime
                            - domainGestures.get(0).startTime);
                }
                Macro macro = new Macro(
                        UUID.randomUUID().toString(),
                        pendingTrigger,
                        domainGestures,
                        lockedRec,
                        System.currentTimeMillis(),
                        dur);
                macro = McpMacros.withAutoInfo(this, macro); // description from the steps, money apps -> sensitive
                MacroRepository.getInstance(this).save(macro);
                toast("Saved: " + pendingTrigger);
            } catch (Throwable e) {
                Log.e(TAG, "Error saving recorded macro", e);
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
            if (bubble != null) {
                try {
                    bubbleWm.removeView(bubble);
                    bubbleWm.addView(bubble, bubbleLp);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable e) {
            recordView = null;
            recording = false;
            refreshBubble();
            toast("Could not open recording overlay");
        }
    }

    private void removeRecordLayer() {
        if (recordView != null) {
            WindowManager w = recordWm != null ? recordWm : wm;
            try { w.removeView(recordView); } catch (Throwable ignored) { }
        }
        recordView = null;
        // A pass still in flight must not leave the next recording's overlay swallowing touches.
        passActive = false;
        passStroke = null;
    }

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
            boolean actualSwipe = isActualSwipe(curPath, screenW(), screenH());
            if (globalAction != null) {
                curStroke.put("type", "global");
                curStroke.put("action", globalAction);
            } else if (actualSwipe) {
                curStroke.put("type", "swipe");
                curStroke.put("path", curPath);
            } else {
                curStroke.put("type", d >= 400 ? "hold" : "tap");
            }

            if (GestureService.instance != null && !"global".equals(curStroke.optString("type", "tap"))) {
                // Closed-loop playback: remember which app this touch was made in.
                try {
                    String screenPkg = GestureService.screenPackageForRecording();
                    if (screenPkg != null) curStroke.put("pkg", screenPkg);
                } catch (Throwable ignored) {
                }
                try {
                    android.view.accessibility.AccessibilityNodeInfo root = GestureService.instance.getRootInActiveWindow();
                    if (root != null) {
                        int touchPxX = (int) Math.round(curStroke.optDouble("x", 0.5) * screenW());
                        int touchPxY = (int) Math.round(curStroke.optDouble("y", 0.5) * screenH());
                        android.view.accessibility.AccessibilityNodeInfo targetNode = findNodeAt(root, touchPxX, touchPxY);
                        if (targetNode != null) {
                            uz.saydo.app.kernel.data.NodeAnchor anchor = uz.saydo.app.kernel.gesture.SmartGestureDispatcher.extractAnchor(targetNode);
                            if (anchor != null) {
                                curStroke.put("anchor", anchor.toJson());
                            }
                            targetNode.recycle();
                        }
                        root.recycle();
                    }
                } catch (Throwable ignored) {
                }
            }

            if (gestures.size() < MAX_GESTURES) {
                gestures.add(curStroke);
                saved = curStroke;
                DebugLog.i("Rec", "stroke #" + gestures.size() + " " + curStroke.optString("type")
                        + " x=" + curStroke.optDouble("x") + " y=" + curStroke.optDouble("y")
                        + " dur=" + curStroke.optLong("dur") + " pkg=" + curStroke.optString("pkg")
                        + " anchor=" + curStroke.has("anchor"));
            }
        } catch (Throwable t) {
            DebugLog.e("Rec", "stroke lost: " + t);
        }
        curStroke = null;
        curPath = null;
        if (recordView != null) recordView.clearVisual();
        if (inject && saved != null && recording) passThrough(saved);
    }

    private boolean isActualSwipe(JSONArray path, int sw, int sh) {
        if (path == null || path.length() < 2) return false;
        JSONObject p0 = path.optJSONObject(0);
        if (p0 == null) return false;
        double x0 = p0.optDouble("x", 0) * sw;
        double y0 = p0.optDouble("y", 0) * sh;
        double maxDistSq = 0;
        for (int i = 1; i < path.length(); i++) {
            JSONObject p = path.optJSONObject(i);
            if (p == null) continue;
            double dx = p.optDouble("x", 0) * sw - x0;
            double dy = p.optDouble("y", 0) * sh - y0;
            double dSq = dx * dx + dy * dy;
            if (dSq > maxDistSq) maxDistSq = dSq;
        }
        double touchSlopPx = 12 * dens;
        return maxDistSq >= touchSlopPx * touchSlopPx;
    }

    private String edgeAction(double nx, double ny, long durMs) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float px = (float) (nx * dm.widthPixels);
        float py = (float) (ny * dm.heightPixels);
        if (py > dm.heightPixels - 64f * dens) return durMs >= 450 ? "recents" : "home";
        if (px < 32f * dens || px > dm.widthPixels - 32f * dens) return "back";
        return null;
    }

    private android.view.accessibility.AccessibilityNodeInfo findNodeAt(android.view.accessibility.AccessibilityNodeInfo root, int x, int y) {
        if (root == null) return null;
        android.graphics.Rect rect = new android.graphics.Rect();
        root.getBoundsInScreen(rect);
        if (!rect.contains(x, y)) return null;

        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                android.view.accessibility.AccessibilityNodeInfo found = findNodeAt(child, x, y);
                if (found != null) {
                    if (child != found) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return android.view.accessibility.AccessibilityNodeInfo.obtain(root);
    }

    private int passToken = 0;

    private void setRecordTouchable(boolean touchable) {
        if (recordView == null || recordLp == null) return;
        if (touchable) recordLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else recordLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager w = recordWm != null ? recordWm : wm;
        try { w.updateViewLayout(recordView, recordLp); } catch (Throwable ignored) { }
    }

    /*
     * While recording, a touch lands on our overlay and is then re-injected into the app below.
     * The FLAG_NOT_TOUCHABLE change reaches the input system asynchronously, so injecting right
     * away made the overlay catch its own injected tap: one real tap was recorded 3 times and
     * replayed 3 times. So: make the overlay untouchable, wait for that to land, inject, and
     * ignore anything the overlay still receives during the pass.
     */
    private static final long PASS_SETTLE_MS = 70;
    private static final long PASS_RESTORE_MS = 60;
    private boolean passActive = false;
    private boolean passRetried = false;
    private JSONObject passStroke;

    private void passThrough(JSONObject stroke) {
        if (GestureService.instance == null || recordView == null) return;
        final int tok = ++passToken;
        passActive = true;
        passRetried = false;
        passStroke = stroke;
        setRecordTouchable(false);
        schedulePass(tok, stroke);
    }

    private void schedulePass(final int tok, final JSONObject stroke) {
        h.postDelayed(() -> injectPass(tok, stroke), PASS_SETTLE_MS);
        long guard = Math.max(stroke.optLong("dur", 60), 60) + 900;
        h.postDelayed(() -> endPass(tok), guard);
    }

    private void injectPass(final int tok, JSONObject stroke) {
        if (tok != passToken) return;
        GestureService svc = GestureService.instance;
        boolean ok = svc != null && svc.injectNow(stroke,
                () -> h.postDelayed(() -> endPass(tok), PASS_RESTORE_MS));
        if (!ok) endPass(tok);
    }

    private void endPass(int tok) {
        if (tok != passToken || !passActive) return;
        passActive = false;
        passStroke = null;
        setRecordTouchable(true);
    }

    /** The overlay caught the injected touch anyway: it never reached the app, so send it again. */
    private void onPassSwallowed() {
        if (passRetried || passStroke == null) return;
        passRetried = true;
        DebugLog.w("Rec", "injected touch caught by overlay, re-injecting");
        schedulePass(++passToken, passStroke);
    }

    // ============================================================ Transparent Record View

    private final class RecordView extends View {
        private boolean fingerDown;

        RecordView(Context ctx) {
            super(ctx);
            setBackgroundColor(Color.TRANSPARENT);
            setClickable(true);
        }

        void clearVisual() {
            fingerDown = false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (passActive) {
                int am = e.getActionMasked();
                if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_CANCEL) onPassSwallowed();
                return true; // never record our own injected touch
            }
            int sw = screenW();
            int sh = screenH();

            float x = e.getRawX();
            float y = e.getRawY();
            double nx = clamp01(x / (double) sw);
            double ny = clamp01(y / (double) sh);
            long tRel = SystemClock.uptimeMillis() - recStartElapsed;

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    finishStroke(false, false);
                    fingerDown = true;
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
                    } catch (Throwable ignored) { }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!fingerDown) return true;
                    int histCount = e.getHistorySize();
                    long strokeStartAbs = recStartElapsed + strokeStartElapsed;
                    for (int hi = 0; hi < histCount; hi++) {
                        float hx = e.getHistoricalX(hi);
                        float hy = e.getHistoricalY(hi);
                        float hrx = hx + (e.getRawX() - e.getX());
                        float hry = hy + (e.getRawY() - e.getY());
                        if (curPath != null && curPath.length() < MAX_PATH_POINTS) {
                            long htAbs = e.getHistoricalEventTime(hi);
                            long strokeT = Math.max(0, htAbs - strokeStartAbs);
                            try {
                                JSONObject p = new JSONObject();
                                p.put("x", clamp01(hrx / (double) sw));
                                p.put("y", clamp01(hry / (double) sh));
                                p.put("t", strokeT);
                                curPath.put(p);
                            } catch (Throwable ignored) { }
                        }
                    }
                    if (curPath != null && curPath.length() < MAX_PATH_POINTS) {
                        try {
                            JSONObject p = new JSONObject();
                            p.put("x", nx);
                            p.put("y", ny);
                            p.put("t", Math.max(0, tRel - strokeStartElapsed));
                            curPath.put(p);
                        } catch (Throwable ignored) { }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (fingerDown) {
                        finishStroke(e.getActionMasked() == MotionEvent.ACTION_CANCEL,
                                e.getActionMasked() == MotionEvent.ACTION_UP);
                        fingerDown = false;
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

    // ============================================================ Trigger Panel

    private void showTriggerPanel() {
        if (triggerPanel != null || wm == null) return;

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
        title.setText("Name this macro");
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

        final android.widget.CheckBox rawBox = new android.widget.CheckBox(this);
        rawBox.setText("Raw touches (games, custom screens)");
        rawBox.setTextColor(Color.parseColor("#A8C0E8"));
        rawBox.setTextSize(13);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Math.round(8 * d);
        card.addView(rawBox, rlp);

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

        Runnable submit = () -> {
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
            boolean raw = rawBox.isChecked();
            hideTriggerPanel();
            pendingRaw = raw;
            startRecordingFlow(phrase);
        };
        go.setOnClickListener(v -> submit.run());
        cancel.setOnClickListener(v -> hideTriggerPanel());
        root.setOnClickListener(v -> hideTriggerPanel());
        card.setOnClickListener(v -> {});

        triggerInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });

        try {
            wm.addView(root, triggerLp);
            triggerPanel = root;
            triggerInput.requestFocus();
            h.postDelayed(() -> {
                try {
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null && triggerInput != null) {
                        imm.showSoftInput(triggerInput, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Throwable ignored) { }
            }, 120);
        } catch (Throwable e) {
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
            } catch (Throwable ignored) { }
            try { wm.removeView(triggerPanel); } catch (Throwable ignored) { }
        }
        triggerPanel = null;
        triggerInput = null;
        triggerLp = null;
    }

    // ============================================================ Cancel Zone

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
        } catch (Throwable e) {
            cancelZone = null;
        }
    }

    private void hideCancelZone() {
        if (cancelZone != null) {
            WindowManager w = cancelWm != null ? cancelWm : wm;
            try { w.removeView(cancelZone); } catch (Throwable ignored) { }
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
        return MacroRepository.getInstance(this).getByTrigger(want) != null;
    }

    private WindowManager overlayWm() {
        GestureService g = GestureService.instance;
        if (g != null) {
            try {
                WindowManager w = (WindowManager) g.getSystemService(WINDOW_SERVICE);
                if (w != null) return w;
            } catch (Throwable ignored) { }
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
        DebugLog.i("Toast", msg);
        h.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
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

    static void rebuild(Context ctx) {
        if (!showing) return;
        try {
            ctx.startService(new Intent(ctx, FloatService.class).setAction(ACTION_REBUILD));
        } catch (Throwable ignored) { }
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
