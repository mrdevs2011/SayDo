package uz.saydo.app;

import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import uz.saydo.app.kernel.data.NodeAnchor;
import uz.saydo.app.kernel.gesture.SmartGestureDispatcher;
import uz.saydo.app.util.DebugLog;
import uz.saydo.app.util.StepLogic;

/**
 * Replays steps recorded from accessibility events: finds the view by id/text/description, waits for
 * it (scrolling the list until it shows), acts on the view itself (click, long click, set text) and
 * falls back to the view's current position only for a click that the view refuses. If the view
 * never shows, the run stops with a message instead of tapping somewhere else.
 */
final class SemPlayer {
    interface Result {
        void done(boolean ok, String why);
    }

    /** Screen text for the log, only from the test apps; other apps may show personal text. */
    static String lbl(String pkg, NodeAnchor a) {
        if (a == null || pkg == null) return "";
        boolean ok = pkg.startsWith("com.android.settings") || pkg.equals("com.sec.android.app.clockpackage")
                || pkg.equals("com.sec.android.app.popupcalculator");
        if (!ok) return "<hidden>";
        String t = !a.getText().isEmpty() ? a.getText()
                : !a.getContentDescription().isEmpty() ? a.getContentDescription() : a.getChildText();
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    static final long FIND_MS = 8000;
    static final long SEARCH_AFTER_MS = 1200;
    static final long POLL_MS = 150;
    static final long WINDOW_QUIET_MS = 350;
    static final int MAX_SCROLL_TRIES = 12;

    private final GestureService svc;
    private final Handler h;

    SemPlayer(GestureService svc, Handler h) {
        this.svc = svc;
        this.h = h;
    }

    void run(final GestureService.G g, final GestureService.G next, final GestureService.G prev,
             final BooleanSupplier alive, final Result cb, final int index) {
        final long t0 = SystemClock.uptimeMillis();
        switch (g.type) {
            case "scroll":
                doScroll(g, next, alive, cb, index);
                break;
            case "text":
                waitQuiet(alive, () -> pollText(g, alive, cb, index, t0, 0));
                break;
            default:
                waitQuiet(alive, () -> pollNode(g, prev, alive, cb, index, t0, new SearchState()));
                break;
        }
    }

    /** Runs {@code then} once the screen has been quiet for a moment (at most 2 s). */
    void settle(final BooleanSupplier alive, final Runnable then) {
        final long start = SystemClock.uptimeMillis();
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!alive.getAsBoolean()) return;
                long now = SystemClock.uptimeMillis();
                if (now - svc.lastWindowChangeAt >= 600 || now - start > 2000) then.run();
                else h.postDelayed(this, 100);
            }
        }, 300);
    }

    /** Lets a page transition that just started finish before the next view is looked up. */
    private void waitQuiet(final BooleanSupplier alive, final Runnable then) {
        final long start = SystemClock.uptimeMillis();
        h.post(new Runnable() {
            @Override public void run() {
                if (!alive.getAsBoolean()) return;
                long now = SystemClock.uptimeMillis();
                if (now - svc.lastWindowChangeAt >= WINDOW_QUIET_MS || now - start > 1500) {
                    then.run();
                } else {
                    h.postDelayed(this, 80);
                }
            }
        });
    }

    // ------------------------------------------------------------ tap / hold

    private static final class SearchState {
        int tries;
        int dir;
        boolean forwardDone;
        boolean busy;
        long sigBefore;
        boolean started;
        int noBox;
        int nudges;
        int stuckRun;
        int flips;
    }

    private void pollNode(final GestureService.G g, final GestureService.G prev, final BooleanSupplier alive,
                          final Result cb, final int index, final long t0, final SearchState ss) {
        if (!alive.getAsBoolean()) return;
        if (!GestureService.usableAnchor(g.anchor)) {
            // Nothing to look for: the recorded place is all there is.
            DebugLog.i("Play", "step " + (index + 1) + " no usable anchor, recorded position");
            tapAt(g, g.x * svc.screenSize()[0], g.y * svc.screenSize()[1], alive, cb);
            return;
        }
        long t1 = SystemClock.uptimeMillis();
        AccessibilityNodeInfo n = find(g.anchor, g.pkg);
        long now = SystemClock.uptimeMillis();
        if (n != null) {
            DebugLog.i("Play", "step " + (index + 1) + " found after " + (now - t0) + " ms (scan " + (now - t1)
                    + " ms) tries=" + ss.tries + " want='" + lbl(g.pkg, g.anchor) + "'");
            act(g, n, alive, cb, index);
            return;
        }
        // Results of a search or filter can take a while to show; give a step after typing longer.
        long limit = (prev != null && "text".equals(prev.type)) ? 15000 : FIND_MS;
        // Search results that never appear after SET_TEXT: write the text again (empty, then full) to wake the watcher.
        if (prev != null && "text".equals(prev.type) && !prev.textToInject.isEmpty()
                && ((now - t0 > 4000 && ss.nudges == 0) || (now - t0 > 9000 && ss.nudges == 1))) {
            ss.nudges++;
            DebugLog.i("Play", "step " + (index + 1) + " results not showing, typing the text again");
            nudgeText(prev);
        }
        if (now - t0 > limit) {
            DebugLog.w("Play", "step " + (index + 1) + " target not found after " + (now - t0) + " ms");
            cb.done(false, "ko'rinmadi");
            return;
        }
        if (now - t0 > SEARCH_AFTER_MS && ss.tries < MAX_SCROLL_TRIES && !ss.busy) {
            scrollSearch(g, prev, alive, cb, index, t0, ss);
            return;
        }
        h.postDelayed(() -> pollNode(g, prev, alive, cb, index, t0, ss), POLL_MS);
    }

    private void scrollSearch(final GestureService.G g, final GestureService.G prev, final BooleanSupplier alive,
                              final Result cb, final int index, final long t0, final SearchState ss) {
        if (!ss.started) {
            ss.started = true;
            ss.dir = (prev != null && "scroll".equals(prev.type) && prev.scrollDy < 0) ? -1 : 1;
        }
        AccessibilityNodeInfo box = findScrollable(g.pkg, g.anchor);
        if (box == null) {
            // No scrollable view right now (a fling or a transition may hide it): look again before giving up.
            if (++ss.noBox > 25) ss.tries = MAX_SCROLL_TRIES;
            h.postDelayed(() -> pollNode(g, prev, alive, cb, index, t0, ss), POLL_MS);
            return;
        }
        Rect b = new Rect();
        box.getBoundsInScreen(b);
        long before = signature(box);
        recycleQuietly(box);
        ss.busy = true;
        ss.tries++;
        int dist = (int) ((b.height()) * 0.5);
        int dy = ss.dir * dist;
        int[] p = StepLogic.swipeFor(b.left, b.top, b.right, b.bottom, 0, dy);
        DebugLog.i("Play", "step " + (index + 1) + " target not visible, scroll try " + ss.tries
                + " dir=" + ss.dir);
        swipe(p, StepLogic.swipeMs(dist), alive, ok -> h.postDelayed(() -> {
            if (!alive.getAsBoolean()) return;
            AccessibilityNodeInfo box2 = findScrollable(g.pkg, g.anchor);
            long after = box2 != null ? signature(box2) : before;
            recycleQuietly(box2);
            if (StepLogic.stuck(before, after)) {
                // The swipe did not move the list: try once more, then turn round; give up after both ways.
                if (++ss.stuckRun >= 2) {
                    ss.stuckRun = 0;
                    ss.flips++;
                    ss.dir = -ss.dir;
                    if (ss.flips >= 3) ss.tries = MAX_SCROLL_TRIES;
                }
            } else {
                ss.stuckRun = 0;
            }
            ss.busy = false;
            pollNode(g, prev, alive, cb, index, t0, ss);
        }, 450));
    }

    private void act(final GestureService.G g, AccessibilityNodeInfo n, final BooleanSupplier alive,
                     final Result cb, final int index) {
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        final boolean hold = "hold".equals(g.type);
        boolean ok = false;
        AccessibilityNodeInfo cur = n;
        try {
            for (int up = 0; up < 5 && cur != null; up++) {
                if (hold ? cur.isLongClickable() : cur.isClickable()) {
                    ok = cur.performAction(hold ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                            : AccessibilityNodeInfo.ACTION_CLICK);
                    break;
                }
                AccessibilityNodeInfo p = cur.getParent();
                if (cur != n) recycleQuietly(cur);
                cur = p;
            }
        } finally {
            if (cur != null && cur != n) recycleQuietly(cur);
            recycleQuietly(n);
        }
        if (ok) {
            DebugLog.i("Play", "step " + (index + 1) + " clicked node directly");
            final long clickAt = SystemClock.uptimeMillis();
            if (isNavUp(g.anchor)) {
                // An "up" button pressed while the page is still opening can be ignored: if no screen change
                // followed, press it once more (safe: it is a navigation button, not a toggle).
                h.postDelayed(() -> {
                    if (!alive.getAsBoolean()) return;
                    if (svc.lastWindowChangeAt < clickAt) {
                        AccessibilityNodeInfo again = find(g.anchor, g.pkg);
                        if (again != null) {
                            DebugLog.i("Play", "step " + (index + 1) + " up button had no effect, pressing again");
                            again.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            recycleQuietly(again);
                        }
                    }
                    h.postDelayed(() -> { if (alive.getAsBoolean()) cb.done(true, ""); }, 250);
                }, 1300);
                return;
            }
            h.postDelayed(() -> { if (alive.getAsBoolean()) cb.done(true, ""); }, 250);
            return;
        }
        DebugLog.i("Play", "step " + (index + 1) + " node not clickable, gesture on its position");
        tapAt(g, b.centerX(), b.centerY(), alive, cb);
    }

    private static boolean isNavUp(NodeAnchor a) {
        if (a == null) return false;
        String d = a.getContentDescription();
        return "Navigate up".equalsIgnoreCase(d) || "Navigate back".equalsIgnoreCase(d);
    }

    private void tapAt(GestureService.G g, double x, double y, BooleanSupplier alive, Result cb) {
        final boolean hold = "hold".equals(g.type);
        Path path = new Path();
        path.moveTo((float) x, (float) y);
        DebugLog.i("Play", "dispatch " + g.type + " at node/recorded position");
        svc.dispatchStroke(path, hold ? 600 : 30, ok -> {
            if (!alive.getAsBoolean()) return;
            if (ok) h.postDelayed(() -> { if (alive.getAsBoolean()) cb.done(true, ""); }, 120);
            else cb.done(false, "harakat bajarilmadi");
        });
    }

    // ------------------------------------------------------------ text

    private void pollText(final GestureService.G g, final BooleanSupplier alive, final Result cb,
                          final int index, final long t0, final int attempt) {
        if (!alive.getAsBoolean()) return;
        AccessibilityNodeInfo n = findField(g);
        long now = SystemClock.uptimeMillis();
        if (n == null) {
            if (now - t0 > FIND_MS) {
                DebugLog.w("Play", "step " + (index + 1) + " text field not found");
                cb.done(false, "yozish maydoni topilmadi");
                return;
            }
            h.postDelayed(() -> pollText(g, alive, cb, index, t0, attempt), POLL_MS);
            return;
        }
        try {
            if (!n.isFocused()) {
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, g.textToInject);
            boolean set = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            n.refresh();
            CharSequence now2 = n.getText();
            boolean same = set && (now2 == null ? g.textToInject.isEmpty() : now2.toString().equals(g.textToInject));
            DebugLog.i("Play", "step " + (index + 1) + " text set=" + set + " verified=" + same
                    + " (" + g.textToInject.length() + " chars) try=" + attempt);
            if (same) {
                h.postDelayed(() -> { if (alive.getAsBoolean()) cb.done(true, ""); }, 250);
                return;
            }
        } finally {
            recycleQuietly(n);
        }
        if (attempt >= 3 || now - t0 > FIND_MS) {
            cb.done(false, "matn yozilmadi");
            return;
        }
        h.postDelayed(() -> pollText(g, alive, cb, index, t0, attempt + 1), 300);
    }

    private void nudgeText(final GestureService.G t) {
        AccessibilityNodeInfo n = findField(t);
        if (n == null) return;
        try {
            Bundle empty = new Bundle();
            empty.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, empty);
        } finally {
            recycleQuietly(n);
        }
        h.postDelayed(() -> {
            AccessibilityNodeInfo m = findField(t);
            if (m == null) return;
            try {
                Bundle a = new Bundle();
                a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t.textToInject);
                m.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
            } finally {
                recycleQuietly(m);
            }
        }, 250);
    }

    /** The recorded field, else the one that has input focus, else the only editable field on screen. */
    private AccessibilityNodeInfo findField(GestureService.G g) {
        if (GestureService.usableAnchor(g.anchor)) {
            AccessibilityNodeInfo n = find(g.anchor, g.pkg);
            if (n != null && n.isEditable()) return n;
            recycleQuietly(n);
        }
        List<AccessibilityNodeInfo> roots = svc.appRoots(g.pkg);
        AccessibilityNodeInfo result = null;
        try {
            for (AccessibilityNodeInfo r : roots) {
                AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f != null && f.isEditable()) { result = f; break; }
                recycleQuietly(f);
            }
        } catch (Throwable ignored) {
        }
        for (AccessibilityNodeInfo r : roots) recycleQuietly(r);
        return result;
    }

    // ------------------------------------------------------------ scroll step

    private void doScroll(final GestureService.G g, final GestureService.G next, final BooleanSupplier alive,
                          final Result cb, final int index) {
        waitQuiet(alive, () -> doScrollNow(g, next, alive, cb, index, SystemClock.uptimeMillis()));
    }

    private void doScrollNow(final GestureService.G g, final GestureService.G next, final BooleanSupplier alive,
                             final Result cb, final int index, final long t0) {
        if (!alive.getAsBoolean()) return;
        // If the next target is already on screen, the scroll is not needed (the list may be elsewhere).
        if (next != null && ("tap".equals(next.type) || "hold".equals(next.type)) && GestureService.usableAnchor(next.anchor)) {
            AccessibilityNodeInfo t = find(next.anchor, next.pkg);
            if (t != null) {
                boolean vis = t.isVisibleToUser();
                recycleQuietly(t);
                if (vis) {
                    DebugLog.i("Play", "step " + (index + 1) + " scroll skipped, next target already visible");
                    cb.done(true, "");
                    return;
                }
            }
        }
        AccessibilityNodeInfo box = findScrollable(g.pkg, g.anchor);
        if (box == null) {
            // The app may still be opening: wait for its list before giving up.
            if (SystemClock.uptimeMillis() - t0 < 6000) {
                h.postDelayed(() -> doScrollNow(g, next, alive, cb, index, t0), POLL_MS);
                return;
            }
            DebugLog.w("Play", "step " + (index + 1) + " scroll: no scrollable view after 6 s, skipped");
            cb.done(true, "");
            return;
        }
        Rect b = new Rect();
        box.getBoundsInScreen(b);
        final long before = signature(box);
        recycleQuietly(box);
        int[] p = StepLogic.swipeFor(b.left, b.top, b.right, b.bottom, g.scrollDx, g.scrollDy);
        int len = Math.max(Math.abs(g.scrollDx), Math.abs(g.scrollDy));
        DebugLog.i("Play", "step " + (index + 1) + " scroll dx=" + g.scrollDx + " dy=" + g.scrollDy);
        swipe(p, StepLogic.swipeMs(len), alive, ok -> h.postDelayed(() -> {
            if (!alive.getAsBoolean()) return;
            AccessibilityNodeInfo box2 = findScrollable(g.pkg, g.anchor);
            long after = box2 != null ? signature(box2) : before;
            recycleQuietly(box2);
            DebugLog.i("Play", "step " + (index + 1) + " scroll moved=" + !StepLogic.stuck(before, after));
            cb.done(true, "");
        }, 500));
    }

    private void swipe(int[] p, long ms, BooleanSupplier alive, java.util.function.Consumer<Boolean> done) {
        Path path = new Path();
        path.moveTo(p[0], p[1]);
        path.lineTo(p[2], p[3]);
        svc.dispatchStroke(path, ms, ok -> {
            if (alive.getAsBoolean()) done.accept(ok);
        });
    }

    // ------------------------------------------------------------ tree helpers

    /** Best match in the windows of the app (dialogs and popups included), top window first. */
    AccessibilityNodeInfo find(NodeAnchor anchor, String pkg) {
        List<AccessibilityNodeInfo> roots = svc.appRoots(pkg);
        AccessibilityNodeInfo found = null;
        try {
            for (AccessibilityNodeInfo r : roots) {
                found = SmartGestureDispatcher.findMatchingNode(r, anchor);
                if (found != null) break;
            }
        } catch (Throwable ignored) {
        } finally {
            for (AccessibilityNodeInfo r : roots) recycleQuietly(r);
        }
        return found;
    }

    /** The scrollable view that matches the recorded one, else the largest visible scrollable view. */
    private AccessibilityNodeInfo findScrollable(String pkg, NodeAnchor hint) {
        List<AccessibilityNodeInfo> roots = svc.appRoots(pkg);
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        try {
            if (hint != null && hint.isScrollable() && GestureService.usableAnchor(hint)) {
                for (AccessibilityNodeInfo r : roots) {
                    AccessibilityNodeInfo m = SmartGestureDispatcher.findMatchingNode(r, hint);
                    if (m != null && m.isScrollable()) return m;
                    recycleQuietly(m);
                }
            }
            Rect b = new Rect();
            for (AccessibilityNodeInfo r : roots) {
                ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
                q.add(r);
                while (!q.isEmpty()) {
                    AccessibilityNodeInfo c = q.poll();
                    if (c == null) continue;
                    if (c != r && !c.isVisibleToUser()) { recycleQuietly(c); continue; }
                    if (c.isScrollable()) {
                        c.getBoundsInScreen(b);
                        long area = (long) b.width() * b.height();
                        if (area > bestArea) {
                            recycleQuietly(best);
                            bestArea = area;
                            best = AccessibilityNodeInfo.obtain(c);
                        }
                    }
                    for (int i = 0; i < c.getChildCount(); i++) {
                        AccessibilityNodeInfo ch = c.getChild(i);
                        if (ch != null) q.add(ch);
                    }
                    if (c != r) recycleQuietly(c);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            for (AccessibilityNodeInfo r : roots) recycleQuietly(r);
        }
        return best;
    }

    /** Signature of the texts inside a view (changes when the list scrolled). */
    private long signature(AccessibilityNodeInfo box) {
        Set<String> labels = new HashSet<>();
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(AccessibilityNodeInfo.obtain(box));
        int budget = 400;
        while (!q.isEmpty() && budget-- > 0) {
            AccessibilityNodeInfo c = q.poll();
            if (c == null) continue;
            CharSequence t = c.getText();
            if (t != null && t.length() > 0) labels.add(t.toString());
            else {
                CharSequence d = c.getContentDescription();
                if (d != null && d.length() > 0) labels.add(d.toString());
            }
            for (int i = 0; i < c.getChildCount(); i++) {
                AccessibilityNodeInfo ch = c.getChild(i);
                if (ch != null && ch.isVisibleToUser()) q.add(ch);
                else recycleQuietly(ch);
            }
            recycleQuietly(c);
        }
        while (!q.isEmpty()) recycleQuietly(q.poll());
        return StepLogic.signature(labels);
    }

    private static void recycleQuietly(AccessibilityNodeInfo n) {
        if (n == null) return;
        try {
            n.recycle();
        } catch (Throwable ignored) {
        }
    }
}
