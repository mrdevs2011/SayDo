package uz.saydo.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.data.SettingsRepository;
import uz.saydo.app.data.TouchPoint;
import uz.saydo.app.kernel.data.NodeAnchor;
import uz.saydo.app.kernel.gesture.SmartGestureDispatcher;
import uz.saydo.app.kernel.ui.ExecutionHudOverlay;
import uz.saydo.app.util.GestureResampler;

/**
 * High-Precision Accessibility Gesture Dispatcher.
 * Executes recorded gestures (tap, hold, swipe, global actions) across system windows
 * with sub-millisecond fidelity, Catmull-Rom spline smoothing, and multi-stroke duration segmentation.
 */
public class GestureService extends AccessibilityService {
    private static final String TAG = "GestureService";

    static volatile GestureService instance;

    interface Done {
        void onDone(boolean completed);
    }

    private static final long TAP_MS = 20;
    private static final long MIN_HOLD_MS = 380;
    private static final long MIN_SWIPE_MS = 20;

    private static final class G {
        String type = "tap";
        String action = "";
        double x, y;
        long st, et, dur;
        List<TouchPoint> pts = new ArrayList<>();
        NodeAnchor anchor;
        String varSlot = "";
        String textToInject = "";
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int token = 0;
    private volatile boolean running = false;
    private Done current;
    private ExecutionHudOverlay hud;
    private String currentMacroName = "Macro";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        hud = new ExecutionHudOverlay(this);
        hud.setAbortCallback(this::cancel);
        FloatService.rebuild(getApplicationContext());
        Log.i(TAG, "GestureService connected");
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (running && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
            cancel();
            return true;
        }
        return super.onKeyEvent(event);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cancel();
        if (instance == this) instance = null;
        FloatService.rebuild(getApplicationContext());
        Log.i(TAG, "GestureService unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cancel();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence p = event.getPackageName();
            if (p != null) {
                String pkg = p.toString();
                if (!ignoredPackages().contains(pkg)) lastPkg = pkg;
            }
        }
        // Background resilience: revive VoiceService if master is active
        if (SettingsRepository.getInstance(this).isMasterOn() && !VoiceService.running && Setup.missing(this) == Setup.OK) {
            try {
                Intent vi = new Intent(this, VoiceService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(vi);
                else startService(vi);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- Home Navigation

    private final Handler homeHandler = new Handler(Looper.getMainLooper());
    private volatile String lastPkg = "";

    private Set<String> ignoredPackages() {
        Set<String> s = new HashSet<>();
        s.add(getPackageName());
        s.add("com.android.systemui");
        s.add("android");
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (InputMethodInfo i : imm.getEnabledInputMethodList()) s.add(i.getPackageName());
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    private Set<String> launcherPackages() {
        Set<String> s = new HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    boolean onHome() {
        return launcherPackages().contains(lastPkg);
    }

    void goHomeThen(final Runnable after) {
        if (onHome()) {
            homeHandler.postDelayed(after, 60);
            return;
        }
        performGlobalAction(GLOBAL_ACTION_HOME);
        final long t0 = SystemClock.uptimeMillis();
        homeHandler.postDelayed(new Runnable() {
            boolean resent = false;
            @Override
            public void run() {
                long el = SystemClock.uptimeMillis() - t0;
                if (el >= 180 && onHome()) {
                    homeHandler.postDelayed(after, 80);
                    return;
                }
                if (!resent && el > 700) {
                    resent = true;
                    performGlobalAction(GLOBAL_ACTION_HOME);
                }
                if (el > 1600) {
                    homeHandler.postDelayed(after, 80);
                    return;
                }
                homeHandler.postDelayed(this, 50);
            }
        }, 80);
    }

    void lockScreenThen(final Runnable ok, final Runnable fail) {
        if (Build.VERSION.SDK_INT < 28 || !performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) {
            homeHandler.post(fail);
            return;
        }
        final long t0 = SystemClock.uptimeMillis();
        homeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (Setup.isLocked(GestureService.this)) {
                    ok.run();
                    return;
                }
                if (SystemClock.uptimeMillis() - t0 > 2500) {
                    fail.run();
                    return;
                }
                homeHandler.postDelayed(this, 150);
            }
        }, 400);
    }

    @Override
    public void onInterrupt() {
        cancel();
    }

    boolean isRunning() {
        return running;
    }

    void cancel() {
        token++;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (hud != null) hud.dismiss();
        Done d = current;
        current = null;
        if (d != null) d.onDone(false);
    }

    boolean play(Macro macro, Done done) {
        if (macro == null) {
            if (done != null) done.onDone(false);
            return false;
        }
        return play(macro.toJson(), done);
    }

    boolean play(JSONObject macro, Done done) {
        List<G> list = parse(macro);
        cancel();
        if (list.isEmpty()) {
            if (done != null) done.onDone(false);
            return false;
        }
        final int tok = ++token;
        current = done;
        running = true;
        currentMacroName = (macro != null) ? macro.optString("trigger", "Macro") : "Macro";
        if (hud != null) hud.show(currentMacroName, 1, list.size());
        step(list, 0, tok, 0);
        return true;
    }

    boolean injectNow(JSONObject stroke, final Runnable after) {
        try {
            JSONObject wrap = new JSONObject();
            JSONArray one = new JSONArray();
            one.put(stroke);
            wrap.put("gestures", one);
            List<G> list = parse(wrap);
            if (list.isEmpty() || "global".equals(list.get(0).type)) return false;
            GestureDescription gd = build(list.get(0));
            return dispatchGesture(gd, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { if (after != null) after.run(); }
                @Override public void onCancelled(GestureDescription d) { if (after != null) after.run(); }
            }, null);
        } catch (Throwable e) {
            return false;
        }
    }

    private void finish(int tok, boolean ok) {
        if (tok != token) return;
        running = false;
        if (hud != null) hud.dismiss();
        Done d = current;
        current = null;
        if (d != null) d.onDone(ok);
    }

    private void step(final List<G> list, final int i, final int tok, final int failures) {
        if (tok != token) return;
        if (i >= list.size()) {
            finish(tok, failures < list.size());
            return;
        }
        final G g = list.get(i);
        if (hud != null) hud.update(currentMacroName, i + 1, list.size());
        long gap = (i == 0) ? Math.min(g.st, 60) : Math.max(0, g.st - list.get(i - 1).et);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tok != token) return;
                if ("global".equals(g.type)) {
                    int act = "home".equals(g.action) ? GLOBAL_ACTION_HOME
                            : "recents".equals(g.action) ? GLOBAL_ACTION_RECENTS
                            : GLOBAL_ACTION_BACK;
                    final boolean okGlobal = performGlobalAction(act);
                    handler.postDelayed(() -> step(list, i + 1, tok, okGlobal ? failures : failures + 1), 250);
                    return;
                }

                // Self-healing UI node anchor resolution
                if (g.anchor != null) {
                    AccessibilityNodeInfo root = null;
                    try {
                        root = getRootInActiveWindow();
                    } catch (Throwable ignored) {
                    }
                    if (root != null) {
                        try {
                            int[] size = screenSize();
                            int w = size[0], h = size[1];
                            Gesture domainGesture = new Gesture(g.type, g.action, g.x, g.y, g.st, g.et, g.dur, g.pts, g.anchor, g.varSlot, g.textToInject);
                            SmartGestureDispatcher.ResolveResult res =
                                    SmartGestureDispatcher.resolveAndExecute(root, domainGesture, w, h, null);
                            if (res.handledDirectly) {
                                handler.postDelayed(() -> step(list, i + 1, tok, failures), 60);
                                return;
                            } else if (res.foundAnchor) {
                                g.x = (double) res.adjustedX / w;
                                g.y = (double) res.adjustedY / h;
                            }
                        } finally {
                            try {
                                root.recycle();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }

                GestureDescription gd;
                try {
                    gd = build(g);
                } catch (Throwable e) {
                    step(list, i + 1, tok, failures + 1);
                    return;
                }

                boolean accepted = dispatchGesture(gd, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription description) {
                        step(list, i + 1, tok, failures);
                    }

                    @Override
                    public void onCancelled(GestureDescription description) {
                        step(list, i + 1, tok, failures + 1);
                    }
                }, null);

                if (!accepted) {
                    step(list, i + 1, tok, failures + 1);
                }
            }
        }, gap);
    }

    // ---------------------------------------------------------------- Gesture Construction

    private GestureDescription build(G g) {
        int[] size = screenSize();
        int w = size[0], h = size[1];
        long maxDur = GestureDescription.getMaxGestureDuration();
        GestureDescription.Builder builder = new GestureDescription.Builder();

        if ("swipe".equals(g.type) && g.pts.size() > 1) {
            // 1. Resample time-proportional
            List<TouchPoint> resampled = GestureResampler.resampleTimeProportional(g.pts, 200);
            // 2. Smooth via Catmull-Rom spline
            List<TouchPoint> smoothed = GestureResampler.smoothSpline(resampled);

            // 3. Segment by duration limits (continueStroke)
            List<List<TouchPoint>> segments = GestureResampler.segmentByDuration(smoothed, maxDur);

            GestureDescription.StrokeDescription strokeDesc = null;
            for (int si = 0; si < segments.size(); si++) {
                List<TouchPoint> segPts = segments.get(si);
                if (segPts.isEmpty()) continue;

                Path segPath = new Path();
                for (int pi = 0; pi < segPts.size(); pi++) {
                    TouchPoint p = segPts.get(pi);
                    float px = clamp((float) (p.x * w), w);
                    float py = clamp((float) (p.y * h), h);
                    if (pi == 0) segPath.moveTo(px, py);
                    else segPath.lineTo(px, py);
                }

                long segDur = segPts.get(segPts.size() - 1).t - segPts.get(0).t;
                segDur = Math.max(MIN_SWIPE_MS, Math.min(segDur, maxDur));
                boolean willContinue = (si < segments.size() - 1);

                if (strokeDesc == null) {
                    strokeDesc = new GestureDescription.StrokeDescription(segPath, 0, segDur, willContinue);
                } else {
                    strokeDesc = strokeDesc.continueStroke(segPath, 0, segDur, willContinue);
                }
                builder.addStroke(strokeDesc);
            }
        } else {
            Path path = new Path();
            float x = clamp((float) (g.x * w), w);
            float y = clamp((float) (g.y * h), h);
            path.moveTo(x, y);

            long dur = "hold".equals(g.type) ? Math.max(g.dur, MIN_HOLD_MS) : TAP_MS;
            if (dur > maxDur) dur = maxDur;
            if (dur < 1) dur = 1;

            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, dur));
        }

        return builder.build();
    }

    private static float clamp(float v, int max) {
        if (v < 1f) return 1f;
        if (v > max - 1) return max - 1;
        return v;
    }

    private int[] screenSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Rect b = wm.getMaximumWindowMetrics().getBounds();
                return new int[]{b.width(), b.height()};
            } catch (Throwable ignored) {
            }
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    // ---------------------------------------------------------------- JSON Deserialization

    private static List<G> parse(JSONObject macro) {
        List<G> out = new ArrayList<>();
        if (macro == null) return out;
        JSONArray arr = macro.optJSONArray("gestures");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            G g = new G();
            g.type = o.optString("type", "tap");
            g.action = o.optString("action", "");
            g.x = o.optDouble("x", 0.5);
            g.y = o.optDouble("y", 0.5);
            g.st = o.optLong("st", 0);
            g.et = o.optLong("et", g.st);
            g.dur = o.optLong("dur", Math.max(0, g.et - g.st));
            JSONArray p = o.optJSONArray("path");
            if (p != null) {
                for (int j = 0; j < p.length(); j++) {
                    JSONObject q = p.optJSONObject(j);
                    if (q == null) continue;
                    g.pts.add(new TouchPoint(
                            q.optDouble("x", 0),
                            q.optDouble("y", 0),
                            q.optLong("t", 0)));
                }
            }
            JSONObject ancObj = o.optJSONObject("anchor");
            if (ancObj != null) {
                g.anchor = NodeAnchor.fromJson(ancObj);
            }
            g.varSlot = o.optString("var", "");
            g.textToInject = o.optString("txt", "");
            out.add(g);
        }
        Collections.sort(out, Comparator.comparingLong(a -> a.st));
        return out;
    }
}
