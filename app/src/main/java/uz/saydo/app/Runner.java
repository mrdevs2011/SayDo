package uz.saydo.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Makroni haqiqiy qurilmada ishga tushirish.
 * Har doim Home'dan boshlanadi: avval Home'ga o'tadi, launcher chiqqach gestlarni bajaradi.
 */
final class Runner {
    static final int OK = 0;
    static final int NO_ACCESSIBILITY = 1;
    static final int INVALID = 2;
    static final int LOCKED = 3;
    static final int OFFLINE = 4;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int runToken = 0;
    private static GestureService.Done pendingDone;

    private Runner() {}

    static int run(Context ctx, final JSONObject macro, final GestureService.Done done) {
        if (ctx == null || macro == null) return INVALID;
        if (!macro.has("gestures")) return INVALID;
        GestureService g = GestureService.instance;
        if (g == null) return NO_ACCESSIBILITY;
        if (!Net.isOnline(ctx)) return OFFLINE;
        if (Setup.isLocked(ctx)) return LOCKED;

        final int token = ++runToken;
        GestureService.Done prev = pendingDone;
        pendingDone = done;
        if (prev != null) prev.onDone(false);

        g.goHomeThen(new Runnable() {
            @Override public void run() {
                if (token != runToken) return; // yangiroq run kelgan, eskisi allaqachon bekor qilingan
                pendingDone = null;
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
        });
        return OK;
    }
}
