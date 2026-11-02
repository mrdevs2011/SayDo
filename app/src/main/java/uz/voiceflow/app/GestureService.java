package uz.voiceflow.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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

    private static final long TAP_MS = 60;
    private static final long MIN_HOLD_MS = 400;
    private static final long MIN_SWIPE_MS = 40;
    private static final long FIRST_DELAY_MS = 300;
    private static final long MIN_GAP_MS = 30;
    private static final float MIN_POINT_STEP_PX = 3f;

    private static final class G {
        String type = "tap";
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
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cancel();
        if (instance == this) instance = null;
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
        // Ekran mazmuni o'qilmaydi — bizga faqat gest yuborish kerak.
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
        long gap = (i == 0) ? FIRST_DELAY_MS : Math.max(g.st - list.get(i - 1).et, MIN_GAP_MS);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tok != token) return;
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

    private GestureDescription build(G g) {
        int[] size = screenSize();
        int w = size[0], h = size[1];
        long maxDur = GestureDescription.getMaxGestureDuration();

        Path path = new Path();
        long dur;

        if ("swipe".equals(g.type) && g.pts.size() > 1) {
            float lx = 0, ly = 0;
            boolean first = true;
            int n = g.pts.size();
            for (int i = 0; i < n; i++) {
                double[] p = g.pts.get(i);
                float x = clamp((float) (p[0] * w), w);
                float y = clamp((float) (p[1] * h), h);
                if (first) {
                    path.moveTo(x, y);
                    lx = x;
                    ly = y;
                    first = false;
                    continue;
                }
                boolean last = (i == n - 1);
                if (last || Math.hypot(x - lx, y - ly) >= MIN_POINT_STEP_PX) {
                    path.lineTo(x, y);
                    lx = x;
                    ly = y;
                }
            }
            dur = (long) (g.pts.get(n - 1)[2] - g.pts.get(0)[2]);
            if (dur < MIN_SWIPE_MS) dur = MIN_SWIPE_MS;
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
