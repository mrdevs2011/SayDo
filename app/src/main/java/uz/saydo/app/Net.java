package uz.saydo.app;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Internet holati. Tarmoq bo'lmasa SayDo umuman ishlamaydi:
 * float ham chiqmaydi, ovoz tinglash ham to'xtaydi.
 */
final class Net {

    interface Listener {
        void onChanged(boolean online);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private static volatile boolean started = false;
    private static volatile Boolean last = null;
    private static Context app;

    private Net() {}

    /** Haqiqiy internet bormi (tarmoq ulangan va tekshirilgan). */
    static boolean isOnline(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** Application.onCreate ichida bir marta chaqiriladi. */
    static void start(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { schedule(); }
                @Override public void onLost(Network network) { schedule(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { schedule(); }
            });
        } catch (Exception e) {
            started = false;
        }
        schedule();
    }

    private static final Runnable CHECK = new Runnable() {
        @Override public void run() {
            if (app == null) return;
            boolean now = isOnline(app);
            Boolean prev = last;
            if (prev != null && prev == now) return;
            last = now;
            react(app, now);
            for (Listener l : LISTENERS) {
                try { l.onChanged(now); } catch (Exception ignored) { }
            }
        }
    };

    private static void schedule() {
        MAIN.removeCallbacks(CHECK);
        MAIN.postDelayed(CHECK, 500);
    }

    /** Internet yo'qolsa hammasini o'chiradi, qaytsa (ON bo'lsa) tiklaydi. */
    private static void react(final Context ctx, boolean online) {
        if (!online) {
            try { ctx.stopService(new Intent(ctx, FloatService.class)); } catch (Exception ignored) { }
            try { ctx.stopService(new Intent(ctx, VoiceService.class)); } catch (Exception ignored) { }
            FloatService.showing = false;
            FloatService.recording = false;
            VoiceService.running = false;
            return;
        }
        if (!Store.isEnabled(ctx)) return;
        if (Setup.missing(ctx) != Setup.OK) return;
        try { FloatService.show(ctx); } catch (Exception ignored) { }
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (!Store.isEnabled(ctx) || !isOnline(ctx) || VoiceService.running) return;
                    ctx.startForegroundService(new Intent(ctx, VoiceService.class));
                } catch (Exception ignored) { }
            }
        }, 700);
    }
}
