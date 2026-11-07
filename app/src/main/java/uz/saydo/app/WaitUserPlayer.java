package uz.saydo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.List;
import java.util.function.BooleanSupplier;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.safety.AuthDetector;
import uz.saydo.app.safety.WaitUserLogic;
import uz.saydo.app.util.DebugLog;

/**
 * Replays a wait_user step: pauses the macro while the owner passes a fingerprint / face / lock screen.
 * Tells the owner (vibration, toast, notification), continues when the prompt is gone, stops after 60 s.
 * Uses window events only, never screen content, so FLAG_SECURE screens do not matter.
 * NOT verified on a phone.
 */
final class WaitUserPlayer {
    interface Result {
        void done(boolean ok, String why);
    }

    static final String TYPE = Gesture.TYPE_WAIT_USER;
    static final String MESSAGE = "Barmoq izingizni qo'ying";
    static final String TIMEOUT_MESSAGE = "kutish vaqti tugadi (60 s): barmoq izi yoki ekran qulfi tasdiqlanmadi";
    private static final String CHANNEL = "saydo_wait_user";
    private static final int NOTIF_ID = 7042;
    private static final long POLL_MS = 200;
    private static final long REMIND_MS = 15_000;
    private static final long SETTLE_MS = 700;

    private final GestureService svc;
    private final Handler h;
    private volatile boolean authVisible;
    private volatile WaitUserLogic active;

    WaitUserPlayer(GestureService svc, Handler h) {
        this.svc = svc;
        this.h = h;
    }

    /** Called for every accessibility event so that an auth prompt that opened before the step started is known. */
    void onEvent(AccessibilityEvent e) {
        try {
            int type = e.getEventType();
            boolean windowChange = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            if (!windowChange && type != AccessibilityEvent.TYPE_VIEW_CLICKED
                    && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
            CharSequence pc = e.getPackageName();
            String pkg = pc != null ? pc.toString() : "";
            String cls = "";
            StringBuilder title = new StringBuilder();
            if (windowChange) {
                CharSequence cc = e.getClassName();
                cls = cc != null ? cc.toString() : "";
                List<CharSequence> parts = e.getText();
                if (parts != null) for (CharSequence c : parts) if (c != null) title.append(c);
            }
            boolean auth = windowChange && AuthDetector.isAuthWindow(pkg, cls, title.toString());
            if (!auth && AuthDetector.isNeutralDuringAuth(pkg, cls)) return;
            long now = SystemClock.uptimeMillis();
            if (auth) authVisible = true;
            else authVisible = false;
            WaitUserLogic w = active;
            if (w != null) w.onWindow(auth, now);
        } catch (Throwable ignored) {
        }
    }

    void run(String reason, final BooleanSupplier alive, final Result cb) {
        final long start = SystemClock.uptimeMillis();
        final WaitUserLogic logic = new WaitUserLogic(start, authVisible);
        active = logic;
        DebugLog.i("Play", "wait_user (" + reason + ") started, prompt already visible=" + authVisible);
        h.post(new Runnable() {
            long lastRemind;

            @Override public void run() {
                if (!alive.getAsBoolean()) {
                    active = null;
                    cancelNotice();
                    return;
                }
                long now = SystemClock.uptimeMillis();
                WaitUserLogic.State s = logic.tick(now);
                if (logic.shouldNotify()) {
                    lastRemind = now;
                    notifyOwner(true);
                } else if (s == WaitUserLogic.State.WAITING_USER && now - lastRemind >= REMIND_MS) {
                    lastRemind = now;
                    notifyOwner(false);
                }
                switch (s) {
                    case DONE:
                        active = null;
                        cancelNotice();
                        DebugLog.i("Play", "wait_user done after " + (now - start) + " ms prompt="
                                + logic.promptWasShown());
                        // Let the screen behind the prompt settle before the next step looks at it.
                        h.postDelayed(() -> { if (alive.getAsBoolean()) cb.done(true, ""); }, SETTLE_MS);
                        return;
                    case TIMEOUT:
                        active = null;
                        cancelNotice();
                        DebugLog.w("Play", "wait_user timeout");
                        cb.done(false, TIMEOUT_MESSAGE);
                        return;
                    default:
                        h.postDelayed(this, POLL_MS);
                }
            }
        });
    }

    private void notifyOwner(boolean first) {
        final Context ctx = svc.getApplicationContext();
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 200, 300}, -1));
            }
        } catch (Throwable ignored) {
        }
        try {
            Toast.makeText(ctx, MESSAGE, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "SayDo: waiting for you",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_mic)
                    .setContentTitle("SayDo")
                    .setContentText(MESSAGE)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setOnlyAlertOnce(!first)
                    .setTimeoutAfter(WaitUserLogic.TIMEOUT_MS + 5000)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (Throwable ignored) {
        }
    }

    private void cancelNotice() {
        try {
            NotificationManager nm = svc.getApplicationContext().getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Throwable ignored) {
        }
    }
}
