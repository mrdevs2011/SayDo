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
import uz.saydo.app.metrics.Metrics;
import uz.saydo.app.util.DebugLog;
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

    static final class G {
        String type = "tap";
        String action = "";
        double x, y;
        long st, et, dur;
        List<TouchPoint> pts = new ArrayList<>();
        NodeAnchor anchor;
        String varSlot = "";
        String textToInject = "";
        String pkg = "";
        String winCls = "";
        int scrollDx, scrollDy;
        boolean sem;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int token = 0;
    private volatile boolean running = false;
    private Done current;
    private ExecutionHudOverlay hud;

    // Metrics for the current run (measurement only — does not change playback)
    private long runId = 0;
    private long runStartedAt = 0;
    private int firstFail = -1;

    /** 0-based step where the last run first failed, or -1. Used by MCP run_macro. */
    int lastRunFirstFail() {
        return firstFail;
    }
    private int runFailures = 0;
    private String currentMacroName = "Macro";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        hud = new ExecutionHudOverlay(this);
        sem = new SemPlayer(this, handler);
        // View ids are only reported to a service that asks for them; anchors are stronger with ids.
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info != null && (info.flags & android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) == 0) {
                info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            DebugLog.w("Rec", "could not add report-view-ids flag: " + t);
        }
        hud.setAbortCallback(this::cancel);
        FloatService.rebuild(getApplicationContext());
        McpService.startIfEnabled(getApplicationContext());
        // A reconnect (another accessibility client such as uiautomator, or a system rebind) resets the
        // event types; a recording that is still running must keep receiving clicks.
        EventRecorder rec = recorder;
        if (rec != null && rec.isActive()) setRecordingEvents(true);
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

    /** Receives the events while a recording from accessibility events is on (see EventRecorder). */
    static volatile EventRecorder recorder;
    volatile long lastWindowChangeAt;
    private SemPlayer sem;

    /** Turns the extra event types on only while recording, so idle cost stays as before. */
    void setRecordingEvents(boolean on) {
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            int add = AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
            if (on) {
                info.eventTypes |= add;
                info.notificationTimeout = 0;
            } else {
                info.eventTypes &= ~add;
                info.notificationTimeout = 200;
            }
            setServiceInfo(info);
            DebugLog.i("Rec", "event types " + (on ? "on" : "off"));
        } catch (Throwable t) {
            DebugLog.e("Rec", "setServiceInfo failed: " + t);
        }
    }

    Set<String> ignoredPackagesPublic() {
        return ignoredPackages();
    }

    /** Roots of the windows of one app (dialogs and popups too), top first; falls back to the active window. */
    List<AccessibilityNodeInfo> appRoots(String pkg) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> ws =
                    new ArrayList<>(getWindows());
            Collections.sort(ws, (a, b) -> b.getLayer() - a.getLayer());
            for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                int t = w.getType();
                if (t == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                        || t == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence cp = r.getPackageName();
                boolean mine = cp != null && cp.toString().equals(getPackageName());
                if (!mine && (pkg == null || pkg.isEmpty() || (cp != null && pkg.equals(cp.toString())))) out.add(r);
                else {
                    try { r.recycle(); } catch (Throwable ignoredErr) {}
                }
            }
        } catch (Throwable ignoredErr) {
        }
        if (out.isEmpty() && (pkg == null || pkg.isEmpty())) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) out.add(r);
        }
        return out;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        EventRecorder rec = recorder;
        if (rec != null) rec.onEvent(event);
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowChangeAt = SystemClock.uptimeMillis();
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

    /**
     * The home app. Not every package that declares a HOME activity: Settings declares one too
     * (FallbackHome), which made Settings count as "the launcher".
     */
    Set<String> launcherPackages() {
        Set<String> s = new HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo def = getPackageManager().resolveActivity(home, 0);
            if (def != null && def.activityInfo != null && !"android".equals(def.activityInfo.packageName)) {
                s.add(def.activityInfo.packageName);
                return s;
            }
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null && !"com.android.settings".equals(ri.activityInfo.packageName)) {
                    s.add(ri.activityInfo.packageName);
                }
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    /**
     * True only if the launcher is really in front. This used to read lastPkg, which ignores
     * SayDo's own windows: with SayDo open it still said "home", so recording started on the
     * SayDo screen and the play button tapped on SayDo instead of the home screen.
     */
    boolean onHome() {
        String top = topApplicationPkg();
        boolean home = top != null ? launcherPackages().contains(top) : launcherPackages().contains(lastPkg);
        return home;
    }

    /** Package of the top full application window, including SayDo; null if unknown. */
    String topApplicationPkg() {
        try {
            String best = null;
            int bestLayer = Integer.MIN_VALUE;
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                try {
                    CharSequence cp = r.getPackageName();
                    if (cp != null && w.getLayer() > bestLayer) {
                        bestLayer = w.getLayer();
                        best = cp.toString();
                    }
                } finally {
                    try { r.recycle(); } catch (Throwable ignored) {}
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Goes to the launcher's default page, so recording and playback always start from the
     * same screen. Home from an app returns to the last launcher page; a second Home press
     * on the launcher moves to the default page.
     */
    void goHomeThen(final Runnable after) {
        performGlobalAction(GLOBAL_ACTION_HOME);
        final long t0 = SystemClock.uptimeMillis();
        homeHandler.postDelayed(new Runnable() {
            boolean resent = false;
            @Override
            public void run() {
                long el = SystemClock.uptimeMillis() - t0;
                boolean home = el >= 150 && onHome();
                if (home || el > 2000) {
                    DebugLog.i("Home", "reached=" + home + " top=" + topApplicationPkg() + " in " + el + " ms");
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    homeHandler.postDelayed(after, 500);
                    return;
                }
                if (!resent && el > 800) {
                    resent = true;
                    performGlobalAction(GLOBAL_ACTION_HOME);
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
        if (running) {
            Metrics.runEnd(runId, false, runFailures, firstFail,
                    SystemClock.uptimeMillis() - runStartedAt, true);
        }
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
        runId = Metrics.runStart(macro != null ? macro.optString("id", "") : "", currentMacroName, list.size());
        runStartedAt = SystemClock.uptimeMillis();
        firstFail = -1;
        runFailures = 0;
        if (hud != null) hud.show(currentMacroName, 1, list.size());
        openFirstApp(list.get(0));
        step(list, 0, tok, 0);
        return true;
    }

    /**
     * A macro whose first step is inside an app (not on the launcher) used to need that app to be
     * opened by hand within 8 s. Open it here, as the launcher icon would.
     */
    private void openFirstApp(G first) {
        try {
            String pkg = first.pkg;
            if (pkg.isEmpty() || launcherPackages().contains(pkg) || pkg.equals(getPackageName())) return;
            if (pkg.equals(foregroundAppPkg())) return;
            Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
            if (li == null) return;
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(li);
            DebugLog.i("Play", "opening first app " + pkg);
        } catch (Throwable t) {
            DebugLog.w("Play", "could not open first app: " + t);
        }
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

    private void finish(int tok, boolean ok, int failures) {
        if (tok != token) return;
        Metrics.runEnd(runId, ok, failures, firstFail, SystemClock.uptimeMillis() - runStartedAt, false);
        DebugLog.i("Play", "finished ok=" + ok + " failures=" + failures + " in "
                + (SystemClock.uptimeMillis() - runStartedAt) + " ms");
        running = false;
        if (hud != null) hud.dismiss();
        Done d = current;
        current = null;
        if (d != null) d.onDone(ok);
    }

    /*
     * Closed-loop playback.
     *
     * Each step first waits the recorded pause, then waits until the screen it was recorded on
     * (the app package saved at recording time) is in front, and briefly for its target view
     * (anchor) to appear. If the expected app never appears, playback STOPS instead of tapping
     * on the wrong screen — a wrong tap at step 9 used to ruin steps 10..N.
     *
     * Macros recorded before this change have no "pkg" and play as before (time-based).
     */
    private static final long SCREEN_WAIT_MS = 8000;
    private static final long ANCHOR_WAIT_MS = 2500;
    private static final long POLL_MS = 120;
    private static final long RETRY_DELAY_MS = 300;

    private void step(final List<G> list, final int i, final int tok, final int failures) {
        if (tok != token) return;
        if (i >= list.size()) {
            // Report "done" only when the last action has finished changing the screen.
            if (list.get(list.size() - 1).sem) {
                sem.settle(() -> tok == token, () -> finish(tok, failures == 0, failures));
            } else {
                finish(tok, failures == 0, failures);
            }
            return;
        }
        final G g = list.get(i);
        if (hud != null) hud.update(currentMacroName, i + 1, list.size());
        long recordedGap = (i == 0) ? Math.min(g.st, 60) : Math.max(0, g.st - list.get(i - 1).et);
        long gap = (i == 0) ? recordedGap
                : uz.saydo.app.util.PlaybackGap.effective(recordedGap, !g.pkg.isEmpty());
        // Steps recorded from events wait for their view anyway: a long recorded pause is thinking time.
        if (g.sem && i > 0) gap = Math.min(recordedGap, SEM_GAP_CAP_MS);
        if (gap != recordedGap) {
            DebugLog.i("Play", "step " + (i + 1) + " pause capped " + recordedGap + " -> " + gap + " ms");
        }
        DebugLog.i("Play", "step " + (i + 1) + " waiting " + gap + " ms");

        handler.postDelayed(() -> {
            if (tok != token) return;
            if ("global".equals(g.type)) {
                int act = "home".equals(g.action) ? GLOBAL_ACTION_HOME
                        : "recents".equals(g.action) ? GLOBAL_ACTION_RECENTS
                        : GLOBAL_ACTION_BACK;
                final long actAt = SystemClock.uptimeMillis();
                final boolean okGlobal = performGlobalAction(act);
                if (!okGlobal) recordFail(i, "global_" + g.action);
                DebugLog.i("Play", "step " + (i + 1) + " global " + g.action + " ok=" + okGlobal);
                // Wait for the screen change this caused, so the next step does not look at the old screen.
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (tok != token) return;
                        long el = SystemClock.uptimeMillis() - actAt;
                        if (g.sem && lastWindowChangeAt < actAt && el < 900) {
                            handler.postDelayed(this, 60);
                            return;
                        }
                        step(list, i + 1, tok, okGlobal ? failures : failures + 1);
                    }
                }, 250);
                return;
            }
            if (g.sem) {
                semStep(list, i, tok, failures);
                return;
            }
            long now = SystemClock.uptimeMillis();
            awaitScreen(list, i, tok, failures, now + SCREEN_WAIT_MS, now + ANCHOR_WAIT_MS);
        }, gap);
    }

    private static final long SEM_GAP_CAP_MS = 1200;

    /** A step recorded from accessibility events: act on the view itself, see SemPlayer. */
    private void semStep(final List<G> list, final int i, final int tok, final int failures) {
        final G g = list.get(i);
        // The next real target (scroll steps are skipped over): used to skip a scroll that is not needed.
        G nx = null;
        for (int j = i + 1; j < list.size(); j++) {
            if (!"scroll".equals(list.get(j).type)) { nx = list.get(j); break; }
        }
        final G next = nx;
        final G prev = i > 0 ? list.get(i - 1) : null;
        sem.run(g, next, prev, () -> tok == token, (ok, why) -> {
            if (tok != token) return;
            if (ok) {
                step(list, i + 1, tok, failures);
            } else {
                recordFail(i, "sem_" + g.type);
                stopRun(tok, i, failures + 1, why);
            }
        }, i);
    }

    private String lastPollLog = "";
    private long lastPollLogAt;

    /** Logs a wait-loop line only when its state changed or about once per second (poll runs every 125 ms). */
    private void pollLog(String state) {
        long now = SystemClock.uptimeMillis();
        // Compare without the scan time so that a changing millisecond count is not a "state change".
        String key = state.replaceAll("\\(scan \\d+ ms\\)", "");
        if (!key.equals(lastPollLog) || now - lastPollLogAt >= 1000) {
            lastPollLog = key;
            lastPollLogAt = now;
            DebugLog.i("Play", state);
        }
    }

    /** Polls until the recorded app is in front (or stops), then briefly for the anchor view. */
    private void awaitScreen(final List<G> list, final int i, final int tok, final int failures,
                             final long screenDeadline, final long anchorDeadline) {
        if (tok != token) return;
        final G g = list.get(i);
        long now = SystemClock.uptimeMillis();

        if (!g.pkg.isEmpty()) {
            String cur = currentScreenPkg();
            pollLog("step " + (i + 1) + " " + g.type + " want=" + g.pkg + " cur=" + cur);
            if (cur != null && !cur.equals(g.pkg)) {
                if (now < screenDeadline) {
                    handler.postDelayed(() -> awaitScreen(list, i, tok, failures, screenDeadline,
                            Math.max(anchorDeadline, SystemClock.uptimeMillis() + ANCHOR_WAIT_MS)), POLL_MS);
                    return;
                }
                recordFail(i, "wrong_screen");
                DebugLog.w("Play", "step " + (i + 1) + " expected " + g.pkg + " but screen is " + cur);
                stopRun(tok, i, failures + 1, "kutilgan ilova ochilmadi");
                return;
            }
            // cur == null: window not readable right now; do not block on it.
        }

        if (stepAwaitStart == 0 || stepAwaitIndex != i) {
            stepAwaitStart = now;
            stepAwaitIndex = i;
        }
        if (usableAnchor(g.anchor) && now < anchorDeadline) {
            long t0 = SystemClock.uptimeMillis();
            boolean present = anchorPresent(g.anchor);
            pollLog("step " + (i + 1) + " anchor present=" + present + " (scan "
                    + (SystemClock.uptimeMillis() - t0) + " ms)");
            if (!present) {
                // The view may still be loading; wait a little, then fall back to coordinates.
                handler.postDelayed(() -> awaitScreen(list, i, tok, failures, screenDeadline, anchorDeadline), POLL_MS);
                return;
            }
        }

        perform(list, i, tok, failures, 0);
    }

    private void perform(final List<G> list, final int i, final int tok, final int failures, final int attempt) {
        if (tok != token) return;
        final G g = list.get(i);

        DebugLog.i("Play", "step " + (i + 1) + " ready after " + (SystemClock.uptimeMillis() - stepAwaitStart)
                + " ms wait, anchor usable=" + usableAnchor(g.anchor));

        // Self-healing UI node anchor resolution
        if (usableAnchor(g.anchor)) {
            long r0 = SystemClock.uptimeMillis();
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
                    DebugLog.i("Play", "step " + (i + 1) + " resolve " + (SystemClock.uptimeMillis() - r0)
                            + " ms found=" + res.foundAnchor + " direct=" + res.handledDirectly);
                    if (res.handledDirectly) {
                        DebugLog.i("Play", "step " + (i + 1) + " clicked node directly");
                        handler.postDelayed(() -> step(list, i + 1, tok, failures), 60);
                        return;
                    } else if (res.foundAnchor) {
                        g.x = (double) res.adjustedX / w;
                        g.y = (double) res.adjustedY / h;
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        root.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        DebugLog.i("Play", String.format(java.util.Locale.US, "step %d dispatch %s x=%.4f y=%.4f dur=%d anchor=%b try=%d",
                i + 1, g.type, g.x, g.y, g.dur, g.anchor != null, attempt));
        GestureDescription gd;
        try {
            gd = build(g);
        } catch (Throwable e) {
            recordFail(i, "build_error");
            stopRun(tok, i, failures + 1, "harakatni qurib bo'lmadi");
            return;
        }

        boolean accepted = dispatchGesture(gd, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription description) {
                step(list, i + 1, tok, failures);
            }

            @Override
            public void onCancelled(GestureDescription description) {
                // Interrupted (e.g. a real touch or system dialog): the step did not happen.
                if (attempt == 0) {
                    handler.postDelayed(() -> perform(list, i, tok, failures, 1), RETRY_DELAY_MS);
                } else {
                    recordFail(i, "cancelled");
                    stopRun(tok, i, failures + 1, "harakat bekor qilindi");
                }
            }
        }, null);

        if (!accepted) {
            if (attempt == 0) {
                handler.postDelayed(() -> perform(list, i, tok, failures, 1), RETRY_DELAY_MS);
            } else {
                recordFail(i, "not_accepted");
                stopRun(tok, i, failures + 1, "tizim harakatni qabul qilmadi");
            }
        }
    }

    /** Stop the macro at step i instead of continuing on a wrong screen, and tell the user. */
    private void stopRun(int tok, int i, int failures, String why) {
        if (tok != token) return;
        final String msg = "SayDo: \"" + currentMacroName + "\" " + (i + 1) + "-qadamda to'xtadi — " + why;
        handler.post(() -> {
            try {
                android.widget.Toast.makeText(getApplicationContext(), msg, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
        finish(tok, false, failures);
    }

    /** App in front during playback (same rules as recording). */
    private String currentScreenPkg() {
        return foregroundAppPkg();
    }

    private Set<String> ignoredCache;
    private long ignoredCacheAt;

    private Set<String> ignoredCached() {
        long now = SystemClock.uptimeMillis();
        if (ignoredCache == null || now - ignoredCacheAt > 5000) {
            ignoredCache = ignoredPackages(); // SayDo, systemui, "android", keyboards
            ignoredCacheAt = now;
        }
        return ignoredCache;
    }

    /**
     * Package of the app the user is working in, or null if unknown.
     * Keyboards, the notification shade/system UI and SayDo's own overlays are skipped, so
     * typing on the keyboard or pulling the shade still counts as "the same app".
     * Used identically for recording and playback so the two always agree.
     */
    String foregroundAppPkg() {
        Set<String> ignored = ignoredCached();
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String p = root.getPackageName().toString();
                if (!ignored.contains(p)) return p;
            }
        } catch (Throwable ignoredErr) {
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Throwable ignoredErr) {}
            }
        }
        // Active window is a keyboard / shade / overlay: use the top application window.
        try {
            String best = null;
            int bestLayer = Integer.MIN_VALUE;
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                try {
                    CharSequence cp = r.getPackageName();
                    if (cp == null || ignored.contains(cp.toString())) continue;
                    if (w.getLayer() > bestLayer) {
                        bestLayer = w.getLayer();
                        best = cp.toString();
                    }
                } finally {
                    try { r.recycle(); } catch (Throwable ignoredErr) {}
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Screen package for a stroke being recorded, or null (then that step plays time-based).
     * Not recorded on the lock screen: what is "behind" the keyguard differs between runs.
     */
    static String screenPackageForRecording() {
        GestureService s = instance;
        if (s == null) return null;
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) s.getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return null;
        } catch (Throwable ignored) {
        }
        return s.foregroundAppPkg();
    }

    private boolean anchorPresent(NodeAnchor anchor) {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return true; // cannot check -> do not wait
            AccessibilityNodeInfo n = SmartGestureDispatcher.findMatchingNode(root, anchor);
            if (n != null) {
                if (n != root) {
                    try { n.recycle(); } catch (Throwable ignored) {}
                }
                return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Dispatch one stroke for MCP (tap / hold / swipe). Callback runs on the main thread. */
    boolean dispatchStroke(Path path, long durationMs, final java.util.function.Consumer<Boolean> done) {
        try {
            long dur = Math.max(1, Math.min(durationMs, GestureDescription.getMaxGestureDuration()));
            GestureDescription gd = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, dur))
                    .build();
            boolean ok = dispatchGesture(gd, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { done.accept(true); }
                @Override public void onCancelled(GestureDescription d) { done.accept(false); }
            }, null);
            if (!ok) done.accept(false);
            return ok;
        } catch (Throwable t) {
            done.accept(false);
            return false;
        }
    }

    private long stepAwaitStart = 0;
    private int stepAwaitIndex = -1;

    /**
     * An anchor only helps if it can ever be found: the matcher needs a score above 0.5, and an
     * anchor with no id, no text and no description tops out at 0.3. Waiting for such an anchor
     * (and scanning the whole screen for it) only slowed every step down.
     */
    static boolean usableAnchor(NodeAnchor a) {
        return a != null && a.canBeMatched();
    }

    private void recordFail(int stepIndex, String reason) {
        if (firstFail < 0) firstFail = stepIndex;
        runFailures++;
        Metrics.runFail(runId, stepIndex, reason);
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

    int[] screenSize() {
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
            g.pkg = o.optString("pkg", "");
            g.winCls = o.optString("wcls", "");
            g.scrollDx = o.optInt("sdx", 0);
            g.scrollDy = o.optInt("sdy", 0);
            g.sem = o.optBoolean("sem", false);
            out.add(g);
        }
        Collections.sort(out, Comparator.comparingLong(a -> a.st));
        return out;
    }
}
