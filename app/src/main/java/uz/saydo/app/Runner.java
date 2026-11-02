package uz.saydo.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/** Makroni haqiqiy qurilmada ishga tushirish (Activity va ovoz servisi uchun umumiy). */
final class Runner {
    static final int OK = 0;
    static final int NO_ACCESSIBILITY = 1;
    static final int INVALID = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int runToken = 0;

    private Runner() {}

    static int run(Context ctx, final JSONObject macro, final GestureService.Done done) {
        if (ctx == null || macro == null) return INVALID;
        if (!macro.has("gestures")) return INVALID;
        if (GestureService.instance == null) return NO_ACCESSIBILITY;

        final int token = ++runToken;
        final MainActivity act = MainActivity.current;
        long delay = 250;
        if (act != null) {
            delay = 900;
            MAIN.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (MainActivity.current == act) act.moveTaskToBack(true);
                    } catch (Exception ignored) { }
                }
            });
        }
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                if (token != runToken) {
                    // Yangiroq run kelgan — eski bekor
                    if (done != null) done.onDone(false);
                    return;
                }
                GestureService s = GestureService.instance;
                if (s == null) {
                    if (done != null) done.onDone(false);
                    return;
                }
                try {
                    s.play(macro, done);
                } catch (Exception e) {
                    if (done != null) done.onDone(false);
                }
            }
        }, delay);
        return OK;
    }
}
