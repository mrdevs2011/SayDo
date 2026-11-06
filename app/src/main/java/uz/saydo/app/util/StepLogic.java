package uz.saydo.app.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Pure decision logic for event-based recording and closed-loop replay. No Android classes, so it
 * can be unit-tested on the JVM.
 */
public final class StepLogic {
    private StepLogic() {}

    // ------------------------------------------------------------ screen signature

    /** Order-independent signature of the labels visible on a screen. */
    public static long signature(Collection<String> labels) {
        long h = 1125899906842597L;
        long sum = 0;
        for (String s : labels) {
            if (s == null || s.isEmpty()) continue;
            long e = 0;
            for (int i = 0; i < s.length(); i++) e = 31 * e + s.charAt(i);
            sum += e * 0x9E3779B97F4A7C15L;
            h ^= e;
        }
        return h * 31 + sum;
    }

    /** Share of labels of {@code a} that are also in {@code b} (1.0 when a is empty). */
    public static double overlap(Set<String> a, Set<String> b) {
        if (a == null || a.isEmpty()) return 1.0;
        int hit = 0;
        for (String s : a) if (b != null && b.contains(s)) hit++;
        return hit / (double) a.size();
    }

    // ------------------------------------------------------------ scroll recording

    /** Collects the many small scroll events of one finger movement into one step. */
    public static final class ScrollAccum {
        public static final long QUIET_MS = 450;
        public static final int MIN_PX = 24;
        private String key = "";
        private long dx, dy, lastAt;
        private boolean open;

        /** Returns true when this event belongs to a different scroll container or came after a pause: flush first. */
        public boolean needsFlush(String k, long now) {
            return open && (!key.equals(k) || now - lastAt > QUIET_MS);
        }

        public void add(String k, int ddx, int ddy, long now) {
            if (!open) { key = k; dx = 0; dy = 0; open = true; }
            dx += ddx; dy += ddy; lastAt = now;
        }

        public boolean isOpen() { return open; }
        public long lastAt() { return lastAt; }
        public String key() { return key; }
        public int dx() { return (int) dx; }
        public int dy() { return (int) dy; }

        /** True if the movement was large enough to be a deliberate scroll. */
        public boolean significant() {
            return Math.abs(dx) >= MIN_PX || Math.abs(dy) >= MIN_PX;
        }

        public void reset() { open = false; dx = 0; dy = 0; key = ""; }
    }

    // ------------------------------------------------------------ text recording

    /** A new text step is needed unless the last recorded step is a text step on the same field. */
    public static boolean mergeText(String lastStepType, String lastFieldKey, String fieldKey) {
        return "text".equals(lastStepType) && lastFieldKey != null && lastFieldKey.equals(fieldKey);
    }

    // ------------------------------------------------------------ back / home detection

    public static final int NAV_NONE = 0, NAV_BACK = 1, NAV_HOME = 2, NAV_RECENTS = 3;

    /**
     * Guesses a back/home gesture from window changes. A system back or home swipe produces no click
     * event, only a window change, so: a window change that no click of ours explains, which returns to
     * the window seen two steps ago, is "back"; a change to the launcher is "home".
     */
    public static final class NavDetector {
        public static final long CLICK_WINDOW_MS = 1500;
        private final List<String> stack = new ArrayList<>();
        private long lastClickAt = -100000;

        public void onClick(long now) { lastClickAt = now; }

        public void reset() { stack.clear(); lastClickAt = -100000; }

        public int onWindow(String cls, boolean launcher, boolean recents, long now) {
            if (cls == null) cls = "";
            if (!stack.isEmpty() && stack.get(stack.size() - 1).equals(cls)) return NAV_NONE;
            boolean explained = now - lastClickAt <= CLICK_WINDOW_MS;
            if (launcher || recents) {
                stack.clear();
                stack.add(cls);
                if (explained) return NAV_NONE;
                return recents ? NAV_RECENTS : NAV_HOME;
            }
            if (!explained && stack.size() >= 2 && stack.get(stack.size() - 2).equals(cls)) {
                stack.remove(stack.size() - 1);
                return NAV_BACK;
            }
            stack.add(cls);
            if (stack.size() > 8) stack.remove(0);
            return NAV_NONE;
        }
    }

    // ------------------------------------------------------------ scroll replay

    /** Start/end points (x1,y1,x2,y2) of a swipe that scrolls a container by (dx, dy); inside bounds. */
    public static int[] swipeFor(int l, int t, int r, int b, int dx, int dy) {
        int w = r - l, h = b - t;
        int cx = (l + r) / 2, cy = (t + b) / 2;
        int maxY = (int) (h * 0.7), maxX = (int) (w * 0.7);
        int sy = Math.max(-maxY, Math.min(maxY, dy));
        int sx = Math.max(-maxX, Math.min(maxX, dx));
        // finger moves opposite to the content: content up (dy > 0) = finger up
        return new int[]{cx + sx / 2, cy + sy / 2, cx - sx / 2, cy - sy / 2};
    }

    /** Milliseconds for a swipe of this length: slow enough not to fling. */
    public static long swipeMs(int px) {
        return Math.max(300, Math.min(900, Math.abs(px) * 1L));
    }

    /**
     * Scroll direction for the n-th attempt to bring a target into view (0-based): forward first
     * (the way the recording scrolled), then back the other way.
     */
    public static int searchDirection(int attempt, int maxForward) {
        return attempt < maxForward ? 1 : -1;
    }

    /** The list did not move: the end was reached. */
    public static boolean stuck(long sigBefore, long sigAfter) {
        return sigBefore == sigAfter;
    }
}
