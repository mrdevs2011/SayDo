package uz.saydo.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import uz.saydo.app.data.Macro;

/**
 * Makroni haqiqiy qurilmada ishga tushirish.
 * - Oddiy makro: avval Home'ga o'tadi, launcher chiqqach gestlarni bajaradi.
 * - Qulf ekranida yozilgan makro ("locked": true): ekranni uyg'otadi (kerak bo'lsa avval qulflaydi)
 *   va gestlarni qulf ekranining o'zidan boshlaydi (slide + parol kiritish ham ijro etiladi).
 */
final class Runner {
    static final int OK = 0;
    static final int NO_ACCESSIBILITY = 1;
    static final int INVALID = 2;
    static final int LOCKED = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int runToken = 0;
    private static GestureService.Done pendingDone;

    private Runner() {}

    static int run(Context ctx, final Macro macro, final GestureService.Done done) {
        if (macro == null) return INVALID;
        return run(ctx, macro.toJson(), done);
    }

    static int run(Context ctx, final JSONObject macro, final GestureService.Done done) {
        if (ctx == null || macro == null) return INVALID;
        if (!macro.has("gestures")) return INVALID;
        GestureService g = GestureService.instance;
        if (g == null) return NO_ACCESSIBILITY;

        final Context app = ctx.getApplicationContext();
        final boolean macroLocked = macro.optBoolean("locked", false);
        final boolean phoneLocked = Setup.isLocked(ctx);
        // Oddiy makro qulf ekranida ishlamaydi (qulf ochilgach ishga tushadi)
        if (phoneLocked && !macroLocked) return LOCKED;

        final int token = ++runToken;
        GestureService.Done prev = pendingDone;
        pendingDone = done;
        if (prev != null) prev.onDone(false);

        final Runnable start = new Runnable() {
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
        };

        if (!macroLocked) {
            g.goHomeThen(start);
            return OK;
        }

        // Qulf ekranidan boshlanadigan makro
        wake(app);
        if (phoneLocked) {
            MAIN.postDelayed(start, 800);
            return OK;
        }
        g.lockScreenThen(new Runnable() {
            @Override public void run() {
                if (token != runToken) return;
                wake(app);
                MAIN.postDelayed(start, 1000);
            }
        }, new Runnable() {
            @Override public void run() {
                if (token != runToken) return;
                pendingDone = null;
                if (done != null) done.onDone(false);
            }
        });
        return OK;
    }

    /** Ekran o'chiq bo'lsa uyg'otadi (qulf ekrani ko'rinishi uchun). */
    @SuppressWarnings("deprecation")
    private static void wake(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "SayDo:wake");
            wl.acquire(6000);
        } catch (Exception ignored) { }
    }
}
