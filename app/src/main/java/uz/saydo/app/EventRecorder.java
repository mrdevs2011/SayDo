package uz.saydo.app;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.kernel.data.NodeAnchor;
import uz.saydo.app.kernel.gesture.SmartGestureDispatcher;
import uz.saydo.app.util.DebugLog;
import uz.saydo.app.util.StepLogic;

/**
 * Records a macro from accessibility events instead of from a touch-catching overlay, so the real
 * touch reaches the app untouched (no delay, scrolling follows the finger). A click, long click,
 * text entry, scroll or window change becomes a step that names the view, not a raw point.
 *
 * What it cannot capture (no event is sent for it): taps on views that are not accessible (games,
 * canvas, some web views), the on-screen keyboard's own keys (the typed text is captured instead),
 * Enter/Search on the keyboard, and the Samsung Edge panel handle. Use raw mode for those.
 */
final class EventRecorder {
    private static final long TEXT_QUIET_MS = 0;

    private final GestureService svc;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Gesture> steps = new ArrayList<>();
    private final StepLogic.ScrollAccum scroll = new StepLogic.ScrollAccum();
    private final StepLogic.NavDetector nav = new StepLogic.NavDetector();
    private final Set<String> launchers = new HashSet<>();
    private final Set<String> ignored = new HashSet<>();
    private long t0;
    private boolean active;
    private int sw = 1080, sh = 2340;

    private boolean leftLauncher = false;
    private String curCls = "";
    private String curPkg = "";
    private String lastType = "";
    private String lastFieldKey = "";
    // scroll bookkeeping
    private String scrollPkg = "";
    private String scrollCls = "";
    private NodeAnchor scrollAnchor;
    private int lastScrollY = Integer.MIN_VALUE, lastScrollX = Integer.MIN_VALUE;
    private int dropped = 0;

    private final Runnable flushScrollRunnable = new Runnable() {
        @Override public void run() {
            flushScroll(SystemClock.uptimeMillis());
        }
    };

    EventRecorder(GestureService svc) {
        this.svc = svc;
    }

    void start() {
        steps.clear();
        scroll.reset();
        nav.reset();
        lastType = "";
        lastFieldKey = "";
        dropped = 0;
        curCls = "";
        curPkg = "";
        leftLauncher = false;
        int[] size = svc.screenSize();
        sw = size[0];
        sh = size[1];
        launchers.clear();
        launchers.addAll(svc.launcherPackages());
        ignored.clear();
        ignored.addAll(svc.ignoredPackagesPublic());
        t0 = SystemClock.uptimeMillis();
        active = true;
    }

    /** Ends recording and returns the steps. */
    List<Gesture> stop() {
        flushScroll(SystemClock.uptimeMillis());
        active = false;
        h.removeCallbacks(flushScrollRunnable);
        DebugLog.i("Rec", "events stop steps=" + steps.size() + " dropped=" + dropped);
        return new ArrayList<>(steps);
    }

    boolean isActive() {
        return active;
    }

    int count() {
        return steps.size();
    }

    void onEvent(AccessibilityEvent e) {
        if (!active || e == null) return;
        CharSequence pc = e.getPackageName();
        String pkg = pc != null ? pc.toString() : "";
        if (pkg.isEmpty()) return;
        int type = e.getEventType();
        long now = SystemClock.uptimeMillis();
        boolean launcher = launchers.contains(pkg);

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (ignored.contains(pkg) && !launcher) return;
            CharSequence cc = e.getClassName();
            String cls = cc != null ? cc.toString() : "";
            // Popups, menus and toasts-as-windows are not screens.
            if (cls.startsWith("android.widget.Toast") || cls.startsWith("android.inputmethodservice")) return;
            flushScroll(now);
            curPkg = pkg;
            curCls = cls;
            boolean recents = launcher && cls.toLowerCase().contains("recent");
            // Home/recents only mean something when we come from an app (the recording starts on the launcher).
            if (launcher && !leftLauncher) { curPkg = pkg; curCls = cls; return; }
            leftLauncher = !launcher;
            // Settings uses one class for every sub page, so the window title is part of the identity.
            StringBuilder title = new StringBuilder();
            List<CharSequence> tparts = e.getText();
            if (tparts != null) for (CharSequence c : tparts) if (c != null) title.append(c);
            int navDecision = nav.onWindow(pkg + "/" + cls + "|" + title, launcher, recents, now);
            if (navDecision == StepLogic.NAV_BACK) addGlobal("back", now);
            else if (navDecision == StepLogic.NAV_HOME) addGlobal("home", now);
            else if (navDecision == StepLogic.NAV_RECENTS) addGlobal("recents", now);
            return;
        }

        if (ignored.contains(pkg)) return;

        switch (type) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                onClick(e, pkg, now, Gesture.TYPE_TAP);
                break;
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED:
                onClick(e, pkg, now, Gesture.TYPE_HOLD);
                break;
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                onText(e, pkg, now);
                break;
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                onScroll(e, pkg, now);
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- click

    private void onClick(AccessibilityEvent e, String pkg, long now, String type) {
        flushScroll(now);
        AccessibilityNodeInfo src = e.getSource();
        // The event itself carries the labels of the clicked view; the node may already be gone when a
        // click opens a new screen, so the event text is the fallback.
        String evLabel = firstEventLabel(e);
        NodeAnchor anchor = null;
        double x = 0.5, y = 0.5;
        try {
            if (src != null) {
                anchor = SmartGestureDispatcher.extractAnchor(src, true);
                Rect b = new Rect();
                src.getBoundsInScreen(b);
                if (b.width() > 0 && b.height() > 0) {
                    x = clamp01(b.centerX() / (double) sw);
                    y = clamp01(b.centerY() / (double) sh);
                }
            }
        } finally {
            if (src != null) recycleQuietly(src);
        }
        if ((anchor == null || !anchor.canBeMatched()) && !evLabel.isEmpty()) {
            CharSequence cc = e.getClassName();
            anchor = new NodeAnchor(anchor != null ? anchor.getViewIdResourceName() : "",
                    anchor != null && !anchor.getClassName().isEmpty() ? anchor.getClassName()
                            : (cc != null ? cc.toString() : ""),
                    anchor != null ? anchor.getText() : "", anchor != null ? anchor.getContentDescription() : "",
                    anchor != null ? anchor.getLeft() : 0, anchor != null ? anchor.getTop() : 0,
                    anchor != null ? anchor.getRight() : 0, anchor != null ? anchor.getBottom() : 0,
                    true, false, false, anchor != null ? anchor.getAncestryHash() : 0, evLabel);
        }
        if (anchor == null) {
            dropped++;
            DebugLog.w("Rec", "click without source node or label (not recorded)");
            return;
        }
        long st = Math.max(0, now - t0);
        nav.onClick(now);
        add(Gesture.createSemantic(type, x, y, st, st + (Gesture.TYPE_HOLD.equals(type) ? 400 : 20),
                anchor, "", pkg, curCls, 0, 0));
        lastType = type;
        lastFieldKey = "";
        DebugLog.i("Rec", "step #" + steps.size() + " " + type + " pkg=" + pkg
                + " id=" + !anchor.getViewIdResourceName().isEmpty()
                + " label=" + (!anchor.getText().isEmpty() || !anchor.getContentDescription().isEmpty()
                || !anchor.getChildText().isEmpty()) + " matchable=" + anchor.canBeMatched());
    }

    private static String firstEventLabel(AccessibilityEvent e) {
        try {
            List<CharSequence> parts = e.getText();
            if (parts != null) {
                for (CharSequence c : parts) {
                    if (c != null && c.toString().trim().length() > 0) return c.toString().trim();
                }
            }
            CharSequence d = e.getContentDescription();
            if (d != null && d.toString().trim().length() > 0) return d.toString().trim();
        } catch (Throwable ignoredErr) {
        }
        return "";
    }

    // ---------------------------------------------------------------- text

    private void onText(AccessibilityEvent e, String pkg, long now) {
        if (!keyboardShowing()) return; // text changed by the app itself, or typed on an app-drawn keypad
        AccessibilityNodeInfo src = e.getSource();
        if (src == null) return;
        try {
            if (src.isPassword() || !src.isEditable()) return;
            // Typed text is stored in the macro, but never logged.
            StringBuilder sb = new StringBuilder();
            List<CharSequence> parts = e.getText();
            if (parts != null) for (CharSequence c : parts) if (c != null) sb.append(c);
            String text = sb.toString();
            Rect b = new Rect();
            src.getBoundsInScreen(b);
            String id = src.getViewIdResourceName();
            String key = (id != null ? id : "") + "|" + b.left + "," + b.top;
            if (StepLogic.mergeText(lastType, lastFieldKey, key) && !steps.isEmpty()) {
                Gesture last = steps.get(steps.size() - 1);
                steps.set(steps.size() - 1, last.withText(text));
                return;
            }
            flushScroll(now);
            NodeAnchor anchor = SmartGestureDispatcher.extractAnchor(src, false);
            long st = Math.max(0, now - t0);
            add(Gesture.createSemantic(Gesture.TYPE_TEXT, clamp01(b.centerX() / (double) sw),
                    clamp01(b.centerY() / (double) sh), st, st + 50, anchor, text, pkg, curCls, 0, 0));
            lastType = Gesture.TYPE_TEXT;
            lastFieldKey = key;
            DebugLog.i("Rec", "step #" + steps.size() + " text pkg=" + pkg + " (" + text.length() + " chars)");
        } finally {
            recycleQuietly(src);
        }
    }

    private boolean keyboardShowing() {
        try {
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Throwable ignoredErr) {
        }
        return false;
    }

    // ---------------------------------------------------------------- scroll

    private void onScroll(AccessibilityEvent e, String pkg, long now) {
        int ddx = 0, ddy = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            ddx = e.getScrollDeltaX();
            ddy = e.getScrollDeltaY();
        }
        int sx = e.getScrollX(), sy = e.getScrollY();
        if (ddx == 0 && ddy == 0) {
            if (lastScrollY != Integer.MIN_VALUE && sy >= 0) ddy = sy - lastScrollY;
            if (lastScrollX != Integer.MIN_VALUE && sx >= 0) ddx = sx - lastScrollX;
        }
        lastScrollY = sy;
        lastScrollX = sx;
        CharSequence cc = e.getClassName();
        String cls = cc != null ? cc.toString() : "";
        String key = pkg + "/" + cls;
        if (scroll.needsFlush(key, now)) flushScroll(now);
        if (!scroll.isOpen()) {
            // First event of a movement: take the container's anchor once, not for every event.
            scrollPkg = pkg;
            scrollCls = cls;
            scrollAnchor = null;
            AccessibilityNodeInfo src = e.getSource();
            if (src != null) {
                try {
                    scrollAnchor = SmartGestureDispatcher.extractAnchor(src, false);
                } finally {
                    recycleQuietly(src);
                }
            }
            scrollStart = now;
        }
        scroll.add(key, ddx, ddy, now);
        h.removeCallbacks(flushScrollRunnable);
        h.postDelayed(flushScrollRunnable, StepLogic.ScrollAccum.QUIET_MS + 50);
    }

    private long scrollStart;

    private void flushScroll(long now) {
        h.removeCallbacks(flushScrollRunnable);
        if (!scroll.isOpen()) return;
        if (scroll.significant() && scrollAnchor != null) {
            long st = Math.max(0, scrollStart - t0);
            double x = clamp01(scrollAnchor.getCenterX() / (double) sw);
            double y = clamp01(scrollAnchor.getCenterY() / (double) sh);
            add(Gesture.createSemantic(Gesture.TYPE_SCROLL, x, y, st, Math.max(st + 20, scroll.lastAt() - t0),
                    scrollAnchor, "", scrollPkg, curCls, scroll.dx(), scroll.dy()));
            lastType = Gesture.TYPE_SCROLL;
            lastFieldKey = "";
            DebugLog.i("Rec", "step #" + steps.size() + " scroll dx=" + scroll.dx() + " dy=" + scroll.dy());
        }
        scroll.reset();
        scrollAnchor = null;
    }

    // ---------------------------------------------------------------- global

    private void addGlobal(String action, long now) {
        long st = Math.max(0, now - t0);
        add(Gesture.createGlobal(action, st));
        lastType = Gesture.TYPE_GLOBAL;
        lastFieldKey = "";
        DebugLog.i("Rec", "step #" + steps.size() + " global " + action);
    }

    private void add(Gesture g) {
        if (steps.size() >= 200) return;
        steps.add(g);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static void recycleQuietly(AccessibilityNodeInfo n) {
        try {
            n.recycle();
        } catch (Throwable ignoredErr) {
        }
    }
}
