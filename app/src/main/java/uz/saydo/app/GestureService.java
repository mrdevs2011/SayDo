package uz.saydo.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
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

/**
 * Yozib olingan tap / hold / swipe harakatlarini butun tizim bo'ylab (boshqa ilovalar ustida)
 * vaqt oralig'larini saqlagan holda ketma-ket bajaradi.
 */
public class GestureService extends AccessibilityService {

    /** Xizmat yoqilgan bo'lsa nomsiz (null emas) bo'ladi. */
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
        List<double[]> pts = new ArrayList<double[]>(); // {x, y, t}
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int token = 0;
    private volatile boolean running = false;
    private Done current;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // Float endi accessibility qatlamida (lock screen ustida ham ko'rinadi)
        FloatService.rebuild(getApplicationContext());
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cancel();
        if (instance == this) instance = null;
        FloatService.rebuild(getApplicationContext());
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
        // Background resilience: agar SayDo yoqilgan bo'lsa va VoiceService to'xtab qolgan bo'lsa, qayta ishga tushiramiz
        if (Store.isEnabled(this) && !VoiceService.running && Setup.missing(this) == Setup.OK) {
            try {
                Intent vi = new Intent(this, VoiceService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(vi);
                else startService(vi);
            } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- Home

    private final Handler homeHandler = new Handler(Looper.getMainLooper());
    private volatile String lastPkg = "";

    private Set<String> ignoredPackages() {
        Set<String> s = new HashSet<String>();
        s.add(getPackageName());
        s.add("com.android.systemui");
        s.add("android");
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (InputMethodInfo i : imm.getEnabledInputMethodList()) s.add(i.getPackageName());
            }
        } catch (Exception ignored) { }
        return s;
    }

    private Set<String> launcherPackages() {
        Set<String> s = new HashSet<String>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) { }
        return s;
    }

    /** Hozir Home (launcher) oldindami. */
    boolean onHome() {
        return launcherPackages().contains(lastPkg);
    }

    /**
     * Home'ga o'tadi va launcher haqiqatan oldinga chiqquncha kutadi
     * (kerak bo'lsa Home'ni qayta bosadi), keyin {@code after} ni ishga tushiradi.
     */
    void goHomeThen(final Runnable after) {
        if (onHome()) {
            homeHandler.postDelayed(after, 60);
            return;
        }
        performGlobalAction(GLOBAL_ACTION_HOME);
        final long t0 = SystemClock.uptimeMillis();
        homeHandler.postDelayed(new Runnable() {
            boolean resent = false;
            @Override public void run() {
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

    /**
     * Ekranni qulflaydi (Android 9+) va qulf ekrani chiqquncha kutadi.
     * Qulflab bo'lmasa {@code fail} chaqiriladi.
     */
    void lockScreenThen(final Runnable ok, final Runnable fail) {
        if (Build.VERSION.SDK_INT < 28 || !performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) {
            homeHandler.post(fail);
            return;
        }
        final long t0 = SystemClock.uptimeMillis();
        homeHandler.postDelayed(new Runnable() {
            @Override public void run() {
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

    /** Joriy bajarilayotgan makroni to'xtatadi (agar bor bo'lsa). */
    void cancel() {
        token++;
        running = false;
        handler.removeCallbacksAndMessages(null);
        Done d = current;
        current = null;
        if (d != null) d.onDone(false);
    }

    /** Makroni boshlaydi. Oldingi bajarilayotgani bekor qilinadi. */
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
        step(list, 0, tok, 0);
        return true;
    }

    /**
     * Yozish paytida: foydalanuvchining bitta gestini (tap/hold/swipe) kechiktirmasdan
     * tagidagi ilovaga yuboradi, shunda yozish vaqtida ilovalar odatdagidek ishlaydi.
     * @param after gest tugagach (yoki bekor bo'lganda) chaqiriladi
     * @return false bo'lsa — yuborib bo'lmadi, after chaqirilmaydi
     */
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
        } catch (Exception e) {
            return false;
        }
    }

    private void finish(int tok, boolean ok) {
        if (tok != token) return;
        running = false;
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
                    // Tizim animatsiyasi tugashini kutamiz
                    handler.postDelayed(new Runnable() {
                        @Override public void run() { step(list, i + 1, tok, okGlobal ? failures : failures + 1); }
                    }, 250);
                    return;
                }
                GestureDescription gd;
                try {
                    gd = build(g);
                } catch (Exception e) {
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

    // ---------------------------------------------------------------- gest qurish

    /**
     * Vaqtga proporsional resample qilingan path bilan gest quradi.
     * Android StrokeDescription path segmentlariga vaqtni TENG taqsimlaydi,
     * shuning uchun biz path nuqtalarini vaqtga proporsional qayta taqsimlaymiz —
     * shunda sekin harakatda ko'p nuqta, tez harakatda kam nuqta bo'ladi.
     */
    private GestureDescription build(G g) {
        int[] size = screenSize();
        int w = size[0], h = size[1];
        long maxDur = GestureDescription.getMaxGestureDuration();

        Path path = new Path();
        long dur;

        if ("swipe".equals(g.type) && g.pts.size() > 1) {
            int n = g.pts.size();
            dur = (long) (g.pts.get(n - 1)[2] - g.pts.get(0)[2]);
            if (dur < MIN_SWIPE_MS) dur = MIN_SWIPE_MS;
            if (dur > maxDur) dur = maxDur;

            // Vaqtga proporsional resample: N ta teng oraliqli nuqtaga qayta taqsimlash
            int segments = Math.min(n, 200);
            if (segments < 2) segments = 2;
            double totalTime = g.pts.get(n - 1)[2] - g.pts.get(0)[2];
            if (totalTime <= 0) totalTime = 1;
            double t0 = g.pts.get(0)[2];

            boolean first = true;
            int srcIdx = 0;
            for (int si = 0; si < segments; si++) {
                // Har bir segment uchun maqsad vaqtni hisoblash
                double targetT = t0 + (totalTime * si / (segments - 1));
                // Tegishli asl nuqtani topish (linear interpolation)
                while (srcIdx < n - 2 && g.pts.get(srcIdx + 1)[2] <= targetT) srcIdx++;
                double[] p1 = g.pts.get(srcIdx);
                double[] p2 = g.pts.get(Math.min(srcIdx + 1, n - 1));
                double segT = p2[2] - p1[2];
                double frac = (segT > 0) ? (targetT - p1[2]) / segT : 0;
                frac = Math.max(0, Math.min(1, frac));

                float px = clamp((float) ((p1[0] + (p2[0] - p1[0]) * frac) * w), w);
                float py = clamp((float) ((p1[1] + (p2[1] - p1[1]) * frac) * h), h);

                if (first) {
                    path.moveTo(px, py);
                    first = false;
                } else {
                    path.lineTo(px, py);
                }
            }
        } else {
            float x = clamp((float) (g.x * w), w);
            float y = clamp((float) (g.y * h), h);
            path.moveTo(x, y);
            if ("hold".equals(g.type)) {
                dur = Math.max(g.dur, MIN_HOLD_MS);
            } else {
                dur = TAP_MS;
            }
        }

        if (dur > maxDur) dur = maxDur;
        if (dur < 1) dur = 1;

        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, dur));
        return b.build();
    }

    private static float clamp(float v, int max) {
        if (v < 1f) return 1f;
        if (v > max - 1) return max - 1;
        return v;
    }

    private int[] screenSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    // ---------------------------------------------------------------- JSON o'qish

    private static List<G> parse(JSONObject macro) {
        List<G> out = new ArrayList<G>();
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
                    g.pts.add(new double[]{q.optDouble("x", 0), q.optDouble("y", 0), q.optDouble("t", 0)});
                }
            }
            out.add(g);
        }
        Collections.sort(out, new Comparator<G>() {
            @Override
            public int compare(G a, G b) {
                return Long.compare(a.st, b.st);
            }
        });
        return out;
    }
}
