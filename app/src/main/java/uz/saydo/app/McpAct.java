package uz.saydo.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parsing and validation of the {@code act} tool's steps, and its one-line result.
 * Pure Java (org.json only) so it can be unit tested; execution is in McpCommands.
 */
final class McpAct {
    static final int MAX_STEPS = 15;
    static final int MAX_SLEEP_MS = 3000;
    static final int DEFAULT_TIMEOUT_MS = 4000;
    static final int MAX_TIMEOUT_MS = 15000;
    static final List<String> KEYS = Arrays.asList(
            "home", "back", "recents", "notifications", "quick_settings", "lock_screen", "power_dialog");
    private static final Pattern PKG = Pattern.compile("^[A-Za-z0-9_.]{3,200}$");

    enum Kind { CLICK_INDEX, CLICK_TEXT, TAP, LONG_PRESS, SWIPE, TYPE, PRESS, OPEN_APP, WAIT }

    static final class Step {
        Kind kind;
        int index = -1;          // CLICK_INDEX, TYPE (optional, -1 = focused field)
        String text;             // CLICK_TEXT, TYPE, PRESS key, OPEN_APP package
        int[] n;                 // TAP {x,y}, LONG_PRESS {x,y,ms}, SWIPE {x1,y1,x2,y2,ms}
        boolean append;          // TYPE
        String waitText, waitGone, waitApp;
        int sleepMs;             // WAIT
        int timeoutMs = DEFAULT_TIMEOUT_MS;

        /** Tap-like steps change the screen: the runner pauses briefly after them. */
        boolean changesScreen() {
            return kind != Kind.WAIT;
        }
    }

    private McpAct() {}

    private static final List<String> ACTIONS = Arrays.asList(
            "click", "tap", "long_press", "swipe", "type", "press", "open_app", "wait");

    /** @throws IllegalArgumentException with a short message naming the step */
    static List<Step> parse(JSONArray steps) {
        if (steps == null || steps.length() == 0) throw new IllegalArgumentException("steps is empty");
        if (steps.length() > MAX_STEPS) throw new IllegalArgumentException("max " + MAX_STEPS + " steps");
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject o = steps.optJSONObject(i);
            String at = "step " + (i + 1) + ": ";
            if (o == null) throw new IllegalArgumentException(at + "not an object");
            try {
                out.add(parseOne(o));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(at + e.getMessage());
            }
        }
        return out;
    }

    private static Step parseOne(JSONObject o) {
        String action = null;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (ACTIONS.contains(k)) {
                if (action != null) throw new IllegalArgumentException("more than one action");
                action = k;
            }
        }
        if (action == null) throw new IllegalArgumentException("no action (click, tap, ...)");
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            boolean extra = action.equals("type") && (k.equals("index") || k.equals("append"));
            if (!k.equals(action) && !extra) throw new IllegalArgumentException("unknown key '" + k + "'");
        }
        Step s = new Step();
        Object v = o.opt(action);
        switch (action) {
            case "click":
                if (v instanceof Number) {
                    s.kind = Kind.CLICK_INDEX;
                    s.index = intOf(v, "click number", 0, 100000);
                } else if (v instanceof String && !((String) v).trim().isEmpty() && ((String) v).length() <= 200) {
                    s.kind = Kind.CLICK_TEXT;
                    s.text = ((String) v).trim();
                } else {
                    throw new IllegalArgumentException("click needs a number or text");
                }
                break;
            case "tap":
                s.kind = Kind.TAP;
                s.n = ints(v, 2, 2, "tap [x,y]");
                checkXY(s.n, 2);
                break;
            case "long_press": {
                s.kind = Kind.LONG_PRESS;
                int[] a = ints(v, 2, 3, "long_press [x,y,ms]");
                s.n = new int[]{a[0], a[1], a.length > 2 ? a[2] : 800};
                checkXY(s.n, 2);
                if (s.n[2] < 300 || s.n[2] > 5000) throw new IllegalArgumentException("long_press ms 300-5000");
                break;
            }
            case "swipe": {
                s.kind = Kind.SWIPE;
                int[] a = ints(v, 4, 5, "swipe [x1,y1,x2,y2,ms]");
                s.n = new int[]{a[0], a[1], a[2], a[3], a.length > 4 ? a[4] : 350};
                checkXY(s.n, 4);
                if (s.n[4] < 50 || s.n[4] > 5000) throw new IllegalArgumentException("swipe ms 50-5000");
                break;
            }
            case "type":
                if (!(v instanceof String)) throw new IllegalArgumentException("type needs text");
                if (((String) v).length() > 5000) throw new IllegalArgumentException("type text too long");
                s.kind = Kind.TYPE;
                s.text = (String) v;
                if (o.has("index")) s.index = intOf(o.opt("index"), "type index", 0, 100000);
                if (o.has("append")) {
                    if (!(o.opt("append") instanceof Boolean)) throw new IllegalArgumentException("append must be true/false");
                    s.append = o.optBoolean("append");
                }
                break;
            case "press":
                if (!(v instanceof String) || !KEYS.contains(v)) throw new IllegalArgumentException("press key: " + KEYS);
                s.kind = Kind.PRESS;
                s.text = (String) v;
                break;
            case "open_app":
                if (!(v instanceof String) || !PKG.matcher((String) v).matches()) {
                    throw new IllegalArgumentException("open_app needs a package name");
                }
                s.kind = Kind.OPEN_APP;
                s.text = (String) v;
                break;
            default: { // wait
                if (!(v instanceof JSONObject)) throw new IllegalArgumentException("wait needs an object");
                JSONObject w = (JSONObject) v;
                for (Iterator<String> it = w.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (!k.equals("text") && !k.equals("gone") && !k.equals("app") && !k.equals("ms") && !k.equals("timeout")) {
                        throw new IllegalArgumentException("wait: unknown key '" + k + "'");
                    }
                }
                s.kind = Kind.WAIT;
                s.waitText = str(w, "text");
                s.waitGone = str(w, "gone");
                s.waitApp = str(w, "app");
                if (s.waitApp != null && !PKG.matcher(s.waitApp).matches()) throw new IllegalArgumentException("wait app: bad package");
                if (w.has("ms")) s.sleepMs = Math.min(MAX_SLEEP_MS, intOf(w.opt("ms"), "wait ms", 0, 1000000));
                if (w.has("timeout")) s.timeoutMs = Math.min(MAX_TIMEOUT_MS, intOf(w.opt("timeout"), "wait timeout", 100, 1000000));
                if (s.waitText == null && s.waitGone == null && s.waitApp == null && s.sleepMs <= 0) {
                    throw new IllegalArgumentException("wait needs text, gone, app or ms");
                }
            }
        }
        return s;
    }

    private static String str(JSONObject o, String k) {
        if (!o.has(k)) return null;
        Object v = o.opt(k);
        if (!(v instanceof String) || ((String) v).isEmpty() || ((String) v).length() > 200) {
            throw new IllegalArgumentException("wait " + k + " must be text up to 200 chars");
        }
        return (String) v;
    }

    private static int intOf(Object v, String what, int min, int max) {
        if (!(v instanceof Number)) throw new IllegalArgumentException(what + " must be a number");
        double d = ((Number) v).doubleValue();
        if (d != Math.rint(d) || d < min || d > max) throw new IllegalArgumentException(what + " out of range");
        return (int) d;
    }

    private static int[] ints(Object v, int min, int max, String what) {
        if (!(v instanceof JSONArray)) throw new IllegalArgumentException(what + " expected");
        JSONArray a = (JSONArray) v;
        if (a.length() < min || a.length() > max) throw new IllegalArgumentException(what + " expected");
        int[] r = new int[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = intOf(a.opt(i), what, 0, 100000);
        return r;
    }

    private static void checkXY(int[] n, int count) {
        for (int i = 0; i < count; i++) {
            if (n[i] > 20000) throw new IllegalArgumentException("coordinate too large");
        }
    }

    /** Collects per-step outcomes: "1 ok 2 ok 3 FAIL no element 'X'". */
    static final class Result {
        private final StringBuilder sb = new StringBuilder();
        private boolean failed;

        void add(int stepNo, String error) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(stepNo);
            if (error == null) {
                sb.append(" ok");
            } else {
                failed = true;
                sb.append(" FAIL ").append(McpFormat.clip(error, 100));
            }
        }

        boolean failed() { return failed; }

        @Override
        public String toString() { return sb.toString(); }
    }
}
