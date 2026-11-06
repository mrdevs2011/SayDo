package uz.saydo.app;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.metrics.Metrics;
import uz.saydo.app.util.DebugLog;

/**
 * Executes MCP tool calls on the phone. Called on the MCP worker thread; anything that
 * touches accessibility or UI is posted to the main thread and awaited with a timeout.
 *
 * Every result is {ok, content:[{type:text|image,...}], error} as the relay expects.
 * Tool names/arguments must match relay/lib/tools.ts.
 */
final class McpCommands {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int DEFAULT_ELEMENTS = 200;

    /** Elements from the latest read_screen, for click_element / type_text by number. */
    private static final class Element {
        final Rect bounds;
        final String text, desc, viewId;
        final boolean editable;

        Element(Rect b, String text, String desc, String viewId, boolean editable) {
            this.bounds = b;
            this.text = text;
            this.desc = desc;
            this.viewId = viewId;
            this.editable = editable;
        }
    }

    private static volatile List<Element> lastScreen = Collections.emptyList();

    private final Context ctx;
    private final McpConfig cfg;

    McpCommands(Context ctx, McpConfig cfg) {
        this.ctx = ctx.getApplicationContext();
        this.cfg = cfg;
    }

    /** Thrown for user-facing failures; the message goes to the AI. */
    static final class Fail extends Exception {
        Fail(String m) { super(m); }
    }

    JSONObject execute(JSONObject cmd) {
        String id = cmd.optString("id", "");
        String tool = cmd.optString("tool", "");
        JSONObject args = cmd.optJSONObject("args");
        if (args == null) args = new JSONObject();
        JSONObject out = new JSONObject();
        try {
            out.put("id", id);
            JSONArray content = new JSONArray();
            run(tool, args, content);
            out.put("ok", true);
            out.put("content", content);
        } catch (Fail f) {
            putErr(out, f.getMessage());
        } catch (Throwable t) {
            DebugLog.e("MCP", tool + " crashed: " + t);
            putErr(out, "Phone error in " + tool + ": " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " — " + t.getMessage()));
        }
        return out;
    }

    private static void putErr(JSONObject out, String msg) {
        try {
            out.put("ok", false);
            out.put("error", msg);
        } catch (Exception ignored) {
        }
    }

    private void run(String tool, JSONObject a, JSONArray content) throws Exception {
        switch (tool) {
            case "device_status": text(content, status().toString(2)); return;
            case "list_macros": text(content, listMacros()); return;
            case "run_macro": text(content, runMacro(a.optString("macro", ""))); return;
            default: break;
        }
        if (!cfg.fullControl()) {
            throw new Fail("Screen control is switched off on the phone (SayDo → MCP → Full control). "
                    + "Only device_status, list_macros and run_macro are allowed.");
        }
        switch (tool) {
            case "read_screen": text(content, readScreen(a.optInt("max_elements", DEFAULT_ELEMENTS))); return;
            case "screenshot": screenshot(content, a.optInt("max_width", 720), a.optInt("quality", 60)); return;
            case "click_element": text(content, clickElement(a)); return;
            case "tap": text(content, stroke("tap", a.getInt("x"), a.getInt("y"), a.getInt("x"), a.getInt("y"), 60)); return;
            case "long_press": text(content, stroke("long_press", a.getInt("x"), a.getInt("y"), a.getInt("x"), a.getInt("y"),
                    a.optInt("duration_ms", 800))); return;
            case "swipe": text(content, stroke("swipe", a.getInt("x1"), a.getInt("y1"), a.getInt("x2"), a.getInt("y2"),
                    a.optInt("duration_ms", 350))); return;
            case "type_text": text(content, typeText(a)); return;
            case "press": text(content, press(a.optString("key", ""))); return;
            case "open_app": text(content, openApp(a.optString("package", ""))); return;
            case "list_apps": text(content, listApps()); return;
            case "wake_screen": text(content, wake()); return;
            default: throw new Fail("Unknown tool: " + tool);
        }
    }

    private static void text(JSONArray content, String t) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "text");
        o.put("text", t);
        content.put(o);
    }

    // ------------------------------------------------------------------ helpers

    private static GestureService service() throws Fail {
        GestureService s = GestureService.instance;
        if (s == null) {
            throw new Fail("SayDo Accessibility service is off on the phone, so the screen cannot be "
                    + "read or controlled. Ask the user to enable it (SayDo → START).");
        }
        return s;
    }

    /** Run on the main thread and wait. */
    private static <T> T onMain(long timeoutMs, MainTask<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return task.run();
        AtomicReference<T> res = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch l = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                res.set(task.run());
            } catch (Exception e) {
                err.set(e);
            } finally {
                l.countDown();
            }
        });
        if (!l.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new Fail("Timed out on the phone.");
        if (err.get() != null) throw err.get();
        return res.get();
    }

    private interface MainTask<T> {
        T run() throws Exception;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static String clip(String s, int max) {
        s = s.replace('\n', ' ').trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ------------------------------------------------------------------ status & macros

    private JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("name", cfg.name());
        o.put("android", Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")");
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        o.put("screen_on", pm != null && pm.isInteractive());
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        o.put("locked", km != null && km.isKeyguardLocked());
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            o.put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            o.put("charging", bm.isCharging());
        }
        GestureService s = GestureService.instance;
        o.put("accessibility_on", s != null);
        o.put("full_control", cfg.fullControl());
        o.put("voice_listening", VoiceService.running);
        o.put("macros", MacroRepository.getInstance(ctx).getAll().size());
        if (s != null) {
            int[] wh = onMain(3000, s::screenSize);
            o.put("screen_px", wh[0] + "x" + wh[1]);
            String pkg = onMain(3000, s::foregroundAppPkg);
            o.put("app_in_front", pkg == null ? JSONObject.NULL : pkg);
        }
        return o;
    }

    /** Short status sent with every poll (shown by the relay as presence). */
    JSONObject pollStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("accessibility_on", GestureService.instance != null);
            o.put("full_control", cfg.fullControl());
        } catch (Exception ignored) {
        }
        return o;
    }

    private String listMacros() {
        List<Macro> all = MacroRepository.getInstance(ctx).getAll();
        if (all.isEmpty()) return "No macros recorded on this phone.";
        StringBuilder sb = new StringBuilder();
        for (Macro m : all) {
            sb.append("- id=").append(m.id)
              .append(" trigger=\"").append(m.trigger).append('"')
              .append(" steps=").append(m.gestures.size())
              .append(m.locked ? " (lock-screen macro)" : "")
              .append('\n');
        }
        return sb.toString().trim();
    }

    private Macro findMacro(String key) throws Fail {
        if (key == null || key.trim().isEmpty()) throw new Fail("Give a macro id or trigger.");
        List<Macro> all = MacroRepository.getInstance(ctx).getAll();
        String k = key.trim();
        for (Macro m : all) if (m.id.equals(k)) return m;
        String nk = TriggerMatcher.norm(k);
        for (Macro m : all) if (TriggerMatcher.norm(m.trigger).equals(nk)) return m;
        Macro best = null;
        double bestScore = 0;
        for (Macro m : all) {
            double sc = TriggerMatcher.score(nk, TriggerMatcher.norm(m.trigger));
            if (sc > bestScore) {
                bestScore = sc;
                best = m;
            }
        }
        if (best != null && bestScore >= TriggerMatcher.EXACT) return best;
        throw new Fail("No macro matches \"" + key + "\". Use list_macros.");
    }

    private String runMacro(String key) throws Exception {
        final Macro m = findMacro(key);
        service(); // must be on
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] completed = {false};
        int code = onMain(5000, () -> {
            Metrics.nextRunSource = "mcp";
            return Runner.run(ctx, m, ok -> {
                completed[0] = ok;
                done.countDown();
            });
        });
        if (code == Runner.NO_ACCESSIBILITY) throw new Fail("Accessibility is off.");
        if (code == Runner.LOCKED) throw new Fail("The phone is locked; this macro needs it unlocked.");
        if (code != Runner.OK) throw new Fail("Macro could not start (code " + code + ").");
        if (!done.await(230, TimeUnit.SECONDS)) {
            return "Macro \"" + m.trigger + "\" is still running after 230 s.";
        }
        if (completed[0]) return "Macro \"" + m.trigger + "\" completed (" + m.gestures.size() + " steps).";
        GestureService s = GestureService.instance;
        int f = s == null ? -1 : s.lastRunFirstFail();
        throw new Fail("Macro \"" + m.trigger + "\" stopped" + (f >= 0 ? " at step " + (f + 1) : "")
                + ". Use read_screen to see where the phone is.");
    }

    // ------------------------------------------------------------------ screen

    private String readScreen(int max) throws Exception {
        final GestureService s = service();
        final int limit = Math.max(10, Math.min(600, max));
        return onMain(8000, () -> {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) throw new Fail("The screen cannot be read right now (screen off or a secure window).");
            int[] wh = s.screenSize();
            List<Element> elements = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            sb.append("Screen ").append(wh[0]).append('x').append(wh[1])
              .append(" px, app: ").append(str(root.getPackageName())).append('\n');
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            int visited = 0;
            boolean truncated = false;
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.poll();
                visited++;
                if (visited > 4000) { truncated = true; break; }
                try {
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo c = n.getChild(i);
                        if (c != null) q.add(c);
                    }
                    if (!n.isVisibleToUser()) continue;
                    String t = str(n.getText());
                    String d = str(n.getContentDescription());
                    String vid = str(n.getViewIdResourceName());
                    boolean interesting = !t.isEmpty() || !d.isEmpty() || n.isClickable() || n.isEditable()
                            || n.isScrollable() || n.isCheckable() || n.isLongClickable();
                    if (!interesting) continue;
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    if (b.isEmpty()) continue;
                    if (elements.size() >= limit) { truncated = true; continue; }
                    int idx = elements.size();
                    elements.add(new Element(b, t, d, vid, n.isEditable()));
                    String cls = str(n.getClassName());
                    int dot = cls.lastIndexOf('.');
                    sb.append('[').append(idx).append("] ").append(dot >= 0 ? cls.substring(dot + 1) : cls);
                    if (!t.isEmpty()) sb.append(" \"").append(clip(t, 80)).append('"');
                    if (!d.isEmpty() && !d.equals(t)) sb.append(" desc=\"").append(clip(d, 60)).append('"');
                    if (!vid.isEmpty()) sb.append(" id=").append(vid.substring(vid.indexOf('/') + 1));
                    if (n.isClickable()) sb.append(" clickable");
                    if (n.isEditable()) sb.append(" editable");
                    if (n.isScrollable()) sb.append(" scrollable");
                    if (n.isCheckable()) sb.append(n.isChecked() ? " checked" : " unchecked");
                    if (n.isFocused()) sb.append(" focused");
                    if (!n.isEnabled()) sb.append(" disabled");
                    sb.append(" [").append(b.left).append(',').append(b.top).append("][")
                      .append(b.right).append(',').append(b.bottom).append("]\n");
                } finally {
                    if (n != root) n.recycle();
                }
            }
            root.recycle();
            for (AccessibilityNodeInfo left : q) left.recycle();
            lastScreen = elements;
            if (elements.isEmpty()) sb.append("(no readable elements — try screenshot)\n");
            if (truncated) sb.append("(list truncated)\n");
            return sb.toString().trim();
        });
    }

    private void screenshot(JSONArray content, int maxWidth, int quality) throws Exception {
        if (Build.VERSION.SDK_INT < 30) throw new Fail("Screenshots need Android 11 or newer.");
        final GestureService s = service();
        final AtomicReference<Bitmap> bmp = new AtomicReference<>();
        final AtomicReference<String> err = new AtomicReference<>();
        final CountDownLatch l = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, ctx.getMainExecutor(),
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(AccessibilityService.ScreenshotResult r) {
                                HardwareBuffer hb = r.getHardwareBuffer();
                                try {
                                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                                    bmp.set(hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false));
                                } finally {
                                    hb.close();
                                }
                                l.countDown();
                            }

                            @Override
                            public void onFailure(int code) {
                                err.set(code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                                        ? "The screen shows a secure window (e.g. banking); Android blocks screenshots."
                                        : code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                                        ? "Screenshots too fast; wait a second."
                                        : "Screenshot failed (code " + code + ").");
                                l.countDown();
                            }
                        });
            } catch (Throwable t) {
                err.set("Screenshot failed: " + t.getClass().getSimpleName());
                l.countDown();
            }
        });
        if (!l.await(15, TimeUnit.SECONDS)) throw new Fail("Screenshot timed out.");
        if (err.get() != null) throw new Fail(err.get());
        Bitmap b = bmp.get();
        if (b == null) throw new Fail("Screenshot returned no image.");
        int w = Math.max(240, Math.min(1440, maxWidth));
        Bitmap scaled = b;
        if (b.getWidth() > w) {
            scaled = Bitmap.createScaledBitmap(b, w, Math.round(b.getHeight() * (w / (float) b.getWidth())), true);
            b.recycle();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, Math.max(20, Math.min(95, quality)), out);
        int origW = scaled.getWidth();
        scaled.recycle();
        JSONObject img = new JSONObject();
        img.put("type", "image");
        img.put("mimeType", "image/jpeg");
        img.put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
        content.put(img);
        int[] wh = onMain(3000, s::screenSize);
        text(content, "Screenshot scaled to width " + origW + " px; screen is " + wh[0] + "x" + wh[1]
                + " px — multiply image coordinates by " + String.format(Locale.US, "%.3f", wh[0] / (float) origW)
                + " before tap/swipe.");
    }

    private String clickElement(JSONObject a) throws Exception {
        final GestureService s = service();
        if (a.has("index")) {
            List<Element> els = lastScreen;
            int i = a.getInt("index");
            if (i < 0 || i >= els.size()) throw new Fail("No element [" + i + "]. Call read_screen first.");
            Element e = els.get(i);
            return clickAt(s, e.bounds, "[" + i + "] " + label(e));
        }
        final String needle = a.optString("text", "").trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) throw new Fail("Give index or text.");
        Rect found = onMain(8000, () -> {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return null;
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            Rect hit = null;
            int visited = 0;
            while (!q.isEmpty() && hit == null && visited++ < 4000) {
                AccessibilityNodeInfo n = q.poll();
                try {
                    if (n.isVisibleToUser()) {
                        String hay = (str(n.getText()) + "\u0001" + str(n.getContentDescription()) + "\u0001"
                                + str(n.getViewIdResourceName())).toLowerCase(Locale.ROOT);
                        if (hay.contains(needle)) {
                            Rect b = new Rect();
                            n.getBoundsInScreen(b);
                            if (!b.isEmpty()) hit = b;
                        }
                    }
                    for (int i = 0; i < n.getChildCount(); i++) {
                        AccessibilityNodeInfo c = n.getChild(i);
                        if (c != null) q.add(c);
                    }
                } finally {
                    if (n != root) n.recycle();
                }
            }
            for (AccessibilityNodeInfo left : q) left.recycle();
            root.recycle();
            return hit;
        });
        if (found == null) throw new Fail("Nothing on screen contains \"" + a.optString("text") + "\".");
        return clickAt(s, found, "\"" + a.optString("text") + "\"");
    }

    private static String label(Element e) {
        if (!e.text.isEmpty()) return "\"" + clip(e.text, 40) + "\"";
        if (!e.desc.isEmpty()) return "\"" + clip(e.desc, 40) + "\"";
        return e.viewId.isEmpty() ? "element" : e.viewId;
    }

    /** Clicks the clickable node at these bounds if there is one, else taps the center. */
    private String clickAt(GestureService s, Rect b, String what) throws Exception {
        Boolean clicked = onMain(5000, () -> {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo n = nodeAt(root, b);
            boolean ok = false;
            AccessibilityNodeInfo cur = n;
            int hops = 0;
            while (cur != null && hops++ < 6) {
                if (cur.isClickable() && cur.isEnabled()) {
                    ok = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    break;
                }
                AccessibilityNodeInfo p = cur.getParent();
                if (cur != root && cur != n) cur.recycle();
                cur = p;
            }
            root.recycle();
            return ok;
        });
        if (clicked != null && clicked) return "Clicked " + what + ".";
        return stroke("tap", b.centerX(), b.centerY(), b.centerX(), b.centerY(), 60).replace("Tapped", "Tapped " + what + " at");
    }

    /** Smallest visible node whose bounds equal (or best contain) the target. */
    private static AccessibilityNodeInfo nodeAt(AccessibilityNodeInfo root, Rect target) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        int visited = 0;
        while (!q.isEmpty() && visited++ < 4000) {
            AccessibilityNodeInfo n = q.poll();
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.equals(target)) return n;
            if (b.contains(target.centerX(), target.centerY())) {
                long area = (long) b.width() * b.height();
                if (area < bestArea) {
                    bestArea = area;
                    best = n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return best;
    }

    private String stroke(String kind, int x1, int y1, int x2, int y2, int durMs) throws Exception {
        final GestureService s = service();
        int[] wh = onMain(3000, s::screenSize);
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 || x1 >= wh[0] || x2 >= wh[0] || y1 >= wh[1] || y2 >= wh[1]) {
            throw new Fail("Coordinates outside the screen (" + wh[0] + "x" + wh[1] + ").");
        }
        final Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        else p.lineTo(x1 + 0.5f, y1 + 0.5f); // a zero-length path is rejected on some versions
        final CountDownLatch l = new CountDownLatch(1);
        final boolean[] ok = {false};
        MAIN.post(() -> s.dispatchStroke(p, durMs, r -> {
            ok[0] = r;
            l.countDown();
        }));
        if (!l.await(durMs + 8000L, TimeUnit.MILLISECONDS)) throw new Fail(kind + " timed out.");
        if (!ok[0]) throw new Fail(kind + " was cancelled by the system (another touch or a system dialog).");
        switch (kind) {
            case "swipe": return "Swiped (" + x1 + "," + y1 + ") → (" + x2 + "," + y2 + ").";
            case "long_press": return "Long-pressed (" + x1 + "," + y1 + ") for " + durMs + " ms.";
            default: return "Tapped (" + x1 + "," + y1 + ").";
        }
    }

    private String typeText(JSONObject a) throws Exception {
        final GestureService s = service();
        final String txt = a.optString("text", "");
        final boolean append = a.optBoolean("append", false);
        final Rect target = a.has("index") ? elementBounds(a.getInt("index")) : null;
        String r = onMain(6000, () -> {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) throw new Fail("The screen cannot be read right now.");
            AccessibilityNodeInfo field = target != null ? nodeAt(root, target)
                    : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (field == null || !field.isEditable()) {
                root.recycle();
                return null;
            }
            if (!field.isFocused()) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            String value = append ? str(field.getText()) + txt : txt;
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            root.recycle();
            return ok ? "Typed " + txt.length() + " characters." : "";
        });
        if (r == null) {
            throw new Fail(target != null ? "That element is not a text field."
                    : "No text field is focused. Tap/click the field first, or pass its read_screen number.");
        }
        if (r.isEmpty()) throw new Fail("The app refused the text input.");
        return r;
    }

    private static Rect elementBounds(int i) throws Fail {
        List<Element> els = lastScreen;
        if (i < 0 || i >= els.size()) throw new Fail("No element [" + i + "]. Call read_screen first.");
        return els.get(i).bounds;
    }

    private String press(String key) throws Exception {
        final GestureService s = service();
        final int action;
        switch (key) {
            case "home": action = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "back": action = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "recents": action = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": action = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power_dialog": action = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG; break;
            case "lock_screen":
                if (Build.VERSION.SDK_INT < 28) throw new Fail("lock_screen needs Android 9+.");
                action = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
                break;
            default: throw new Fail("Unknown key: " + key);
        }
        boolean ok = onMain(3000, () -> s.performGlobalAction(action));
        if (!ok) throw new Fail("The system refused '" + key + "'.");
        return "Pressed " + key + ".";
    }

    private String openApp(String pkg) throws Exception {
        final GestureService s = service();
        final Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) throw new Fail("No launchable app with package " + pkg + ". Use list_apps.");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        onMain(4000, () -> {
            s.startActivity(i);
            return true;
        });
        return "Opened " + pkg + ". Call read_screen to see it.";
    }

    private String listApps() {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        List<String> rows = new ArrayList<>();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            rows.add(str(ri.loadLabel(pm)) + " — " + ri.activityInfo.packageName);
        }
        Collections.sort(rows, String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String r : rows) {
            if (r.equals(prev)) continue;
            sb.append(r).append('\n');
            prev = r;
        }
        return sb.length() == 0 ? "No apps found." : sb.toString().trim();
    }

    @SuppressWarnings("deprecation")
    private String wake() {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return "Power service unavailable.";
        if (pm.isInteractive()) return "Screen is already on.";
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "SayDo:mcpWake");
        wl.acquire(3000);
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        boolean locked = km != null && km.isKeyguardLocked();
        return "Screen turned on" + (locked ? "; the phone is still locked." : ".");
    }
}
