package uz.saydo.app.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory advanced debug log — especially recording / replay. */
public final class DebugLog {
    public interface Listener { void onLog(); }

    private static final String TAG = "SayDoDebug";
    private static final int MAX = 400;
    private static final List<String> lines = new ArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private DebugLog() {}

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static synchronized void i(String tag, String msg) {
        append("I", tag, msg);
    }

    public static synchronized void w(String tag, String msg) {
        append("W", tag, msg);
    }

    public static synchronized void e(String tag, String msg) {
        append("E", tag, msg);
    }

    public static synchronized void match(String msg) {
        append("MATCH", "Trigger", msg);
    }

    private static void append(String level, String tag, String msg) {
        if (msg == null) msg = "";
        String line = FMT.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        lines.add(line);
        while (lines.size() > MAX) lines.remove(0);
        if ("E".equals(level)) Log.e(TAG, tag + ": " + msg);
        else if ("W".equals(level)) Log.w(TAG, tag + ": " + msg);
        else Log.d(TAG, tag + ": " + msg);
        main.post(() -> {
            for (Listener l : listeners) {
                try { l.onLog(); } catch (Throwable ignored) {}
            }
        });
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder(lines.size() * 64);
        sb.append("=== SayDo Debug Dump ===\n");
        sb.append("time=").append(new Date()).append('\n');
        sb.append("lines=").append(lines.size()).append("\n\n");
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(lines);
    }

    public static synchronized void clear() {
        lines.clear();
        main.post(() -> {
            for (Listener l : listeners) {
                try { l.onLog(); } catch (Throwable ignored) {}
            }
        });
    }

    public static boolean copyToClipboard(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("SayDo Debug", dump()));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
