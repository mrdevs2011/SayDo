package uz.saydo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import uz.saydo.app.util.DebugLog;

/**
 * Asks the owner on the phone before an AI-requested sensitive macro runs: a heads-up notification with
 * Allow and Deny. No answer within 60 s counts as Deny. Blocks the calling (worker) thread.
 * Not verified on a phone yet. The notification shows only the macro trigger, never steps or screen content.
 */
final class McpApproval {
    private McpApproval() {}

    static final long TIMEOUT_MS = 60_000;
    private static final String CHANNEL = "saydo_mcp_approval";
    private static final String ACTION = "uz.saydo.app.MCP_APPROVAL";
    private static final int NOTIF_ID = 7041;
    private static int seq = 0;

    /** True only if the owner tapped Allow in time. */
    static boolean ask(Context ctx, String trigger) {
        final Context app = ctx.getApplicationContext();
        final NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            DebugLog.i("MCP", "approval: notification permission missing, denying");
            return false;
        }
        final int id;
        synchronized (McpApproval.class) {
            id = ++seq;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean allowed = new AtomicBoolean(false);
        final String action = ACTION + "." + id;
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                allowed.set(i.getBooleanExtra("allow", false));
                latch.countDown();
            }
        };
        try {
            IntentFilter f = new IntentFilter(action);
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(rx, f);

            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "SayDo AI approval",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            PendingIntent yes = PendingIntent.getBroadcast(app, id * 2,
                    new Intent(action).setPackage(app.getPackageName()).putExtra("allow", true),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent no = PendingIntent.getBroadcast(app, id * 2 + 1,
                    new Intent(action).setPackage(app.getPackageName()).putExtra("allow", false),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String text = McpGuard.approvalText(trigger);
            Notification n = new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_mic)
                    .setContentTitle("SayDo: AI request")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setTimeoutAfter(TIMEOUT_MS)
                    .setAutoCancel(true)
                    .addAction(new Notification.Action.Builder(null, "Allow", yes).build())
                    .addAction(new Notification.Action.Builder(null, "Deny", no).build())
                    .build();
            nm.notify(NOTIF_ID, n);
            boolean answered = latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            DebugLog.i("MCP", "approval: " + (answered ? (allowed.get() ? "allowed" : "denied") : "timeout"));
            return answered && allowed.get();
        } catch (Throwable t) {
            DebugLog.e("MCP", "approval failed: " + t.getClass().getSimpleName());
            return false;
        } finally {
            try {
                nm.cancel(NOTIF_ID);
            } catch (Throwable ignored) {
            }
            try {
                app.unregisterReceiver(rx);
            } catch (Throwable ignored) {
            }
        }
    }
}
