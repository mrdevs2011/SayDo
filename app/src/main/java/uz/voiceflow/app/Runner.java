package uz.voiceflow.app;

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

    private Runner() {}

    static int run(Context ctx, final JSONObject macro, final GestureService.Done done) {
        if (macro == null) return INVALID;
        if (GestureService.instance == null) return NO_ACCESSIBILITY;

        final MainActivity act = MainActivity.current;
        long delay = 250;
        if (act != null) {
            // Ilova ochiq bo'lsa, avval orqaga o'tkazamiz — aks holda o'z oynamizga bosib yuboramiz.
            delay = 900;
            MAIN.post(new Runnable() {
                @Override public void run() { act.moveTaskToBack(true); }
            });
        }
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                GestureService s = GestureService.instance;
                if (s == null) {
                    if (done != null) done.onDone(false);
                    return;
                }
                s.play(macro, done);
            }
        }, delay);
        return OK;
    }
}
