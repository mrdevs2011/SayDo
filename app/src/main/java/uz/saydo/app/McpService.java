package uz.saydo.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import uz.saydo.app.util.DebugLog;

/**
 * Keeps this phone connected to the SayDo relay while MCP is switched on:
 * register → long-poll for a command → execute → post the result → poll again.
 *
 * Runs only when the user switched MCP on (per device, SayDo → MCP). A notification
 * shows that the phone can be controlled and the last command, with a Stop action.
 */
public class McpService extends Service {
    private static final String TAG = "McpService";
    static final String ACTION_STOP = "uz.saydo.app.MCP_STOP";
    private static final String CHANNEL = "saydo_mcp";
    private static final int NOTIF_ID = 31;

    // ---- observable status for the settings screen
    interface Listener { void onMcpStatus(); }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    static volatile boolean running;
    static volatile boolean connected;
    static volatile String lastError = "";
    static volatile String lastCommand = "";
    static volatile long lastPollOkAt;

    static void addListener(Listener l) { if (l != null) listeners.addIfAbsent(l); }
    static void removeListener(Listener l) { listeners.remove(l); }

    private static void changed() {
        for (Listener l : listeners) {
            try { l.onMcpStatus(); } catch (Throwable ignored) {}
        }
    }

    /** Start the service if MCP is switched on (safe to call from anywhere). */
    static void startIfEnabled(Context c) {
        try {
            if (!new McpConfig(c).enabled() || running) return;
            Intent i = new Intent(c, McpService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            // Android may refuse a background start; it is retried when SayDo is opened.
            Log.w(TAG, "start refused: " + t);
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, McpService.class));
        } catch (Throwable ignored) {
        }
    }

    private volatile boolean stopRequested;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        McpConfig cfg = new McpConfig(this);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            cfg.setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification n = notification("Ulanmoqda…");
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!cfg.enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            stopRequested = false;
            running = true;
            acquireWakeLock();
            worker = new Thread(this::loop, "SayDo-mcp");
            worker.start();
            changed();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        running = false;
        connected = false;
        Thread w = worker;
        if (w != null) w.interrupt();
        releaseWakeLock();
        changed();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ main loop

    private void loop() {
        McpConfig cfg = new McpConfig(this);
        McpRelay relay = new McpRelay(cfg);
        McpCommands commands = new McpCommands(this, cfg);
        long backoff = 1000;

        while (!stopRequested) {
            try {
                if (!cfg.enabled()) break;
                if (!cfg.isRegistered()) {
                    McpRelay.Response r = relay.register();
                    if (r.code != 200) throw new RelayError("Ro'yxatdan o'tmadi (HTTP " + r.code + ")");
                    cfg.markRegistered();
                    DebugLog.i("MCP", "registered as " + cfg.deviceId());
                }

                McpRelay.Response r = relay.poll(commands.pollStatus());
                if (r.code == 401) {
                    // Relay forgot us (expired / new relay): register again.
                    cfg.clearRegistered();
                    continue;
                }
                if (r.code != 200 && r.code != 204) throw new RelayError("Relay xatosi (HTTP " + r.code + ")");

                backoff = 1000;
                lastPollOkAt = System.currentTimeMillis();
                if (!connected || !lastError.isEmpty()) {
                    connected = true;
                    lastError = "";
                    updateNotification();
                    changed();
                }
                if (r.code == 204) continue;

                JSONObject cmd = r.json().optJSONObject("command");
                if (cmd == null) continue;
                handle(cmd, commands, relay);
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable t) {
                if (stopRequested) break;
                connected = false;
                lastError = t instanceof RelayError ? t.getMessage() : "Internet yoki relay bilan aloqa yo'q";
                Log.w(TAG, "loop error: " + t);
                updateNotification();
                changed();
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    break;
                }
                backoff = Math.min(backoff * 2, 60000);
            }
        }
        running = false;
        connected = false;
        changed();
        stopSelf();
    }

    private void handle(JSONObject cmd, McpCommands commands, McpRelay relay) throws Exception {
        String tool = cmd.optString("tool", "?");
        long exp = cmd.optLong("exp", 0);
        if (exp > 0 && exp < System.currentTimeMillis()) return; // AI already gave up

        lastCommand = tool + " · " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        DebugLog.i("MCP", "command " + tool + " " + cmd.optJSONObject("args"));
        updateNotification();
        changed();

        long t0 = SystemClock.uptimeMillis();
        JSONObject result = commands.execute(cmd);
        DebugLog.i("MCP", tool + " -> ok=" + result.optBoolean("ok") + " in "
                + (SystemClock.uptimeMillis() - t0) + " ms");

        // One retry: the result is what the AI is waiting for.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                McpRelay.Response rr = relay.result(result);
                if (rr.code == 413) {
                    JSONObject small = new JSONObject();
                    small.put("id", cmd.optString("id"));
                    small.put("ok", false);
                    small.put("error", "Result too large to send.");
                    relay.result(small);
                }
                return;
            } catch (java.io.IOException e) {
                if (attempt == 1) throw e;
                Thread.sleep(1000);
            }
        }
    }

    private static final class RelayError extends Exception {
        RelayError(String m) { super(m); }
    }

    // ------------------------------------------------------------------ notification

    private void updateNotification() {
        String text;
        if (connected) {
            text = "Ulangan — AI bu telefonni boshqara oladi"
                    + (lastCommand.isEmpty() ? "" : " · oxirgi: " + lastCommand);
        } else {
            text = lastError.isEmpty() ? "Ulanmoqda…" : lastError + " · qayta urinilmoqda";
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, notification(text));
        } catch (Throwable ignored) {
        }
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "SayDo MCP", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 31,
                new Intent(this, McpActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 32,
                new Intent(this, McpService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("SayDo MCP")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "O'chirish", stop).build())
                .build();
    }

    // ------------------------------------------------------------------ wake lock

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null) {
                // Keeps the long-poll alive with the screen off; held only while MCP is on.
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SayDo:mcp");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
    }
}
