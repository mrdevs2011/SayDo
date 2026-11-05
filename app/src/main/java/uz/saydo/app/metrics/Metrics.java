package uz.saydo.app.metrics;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Persistent metrics event log (JSON lines) for measuring real-device reliability.
 * All disk I/O runs on one background thread; every call is safe before init() (no-op).
 *
 * File: <external files dir>/metrics.jsonl (pull with adb), rotated to metrics.old.jsonl at 1 MB.
 */
public final class Metrics {
    private static final String TAG = "SayDoMetrics";
    private static final long MAX_BYTES = 1024 * 1024;

    /** Where the next macro run comes from: "voice", "manual", "bench". */
    public static volatile String nextRunSource = "manual";

    /** Id of the most recently started run (for attaching a human verdict). */
    public static volatile long lastRunId = 0;

    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SayDo-metrics");
        t.setDaemon(true);
        return t;
    });
    private static volatile File dir;
    private static volatile String deviceInfo = "";

    private Metrics() {}

    public static void init(Context ctx) {
        if (ctx == null || dir != null) return;
        Context app = ctx.getApplicationContext();
        File d = null;
        try { d = app.getExternalFilesDir(null); } catch (Throwable ignored) {}
        if (d == null) d = app.getFilesDir();
        dir = d;
        deviceInfo = buildDeviceInfo(app);
    }

    public static File file() {
        return dir == null ? null : new File(dir, "metrics.jsonl");
    }

    public static String deviceInfo() {
        return deviceInfo;
    }

    // ------------------------------------------------------------------ voice

    public static void voiceHeard(String text, String matchedTrigger, boolean partial, long latencyMs) {
        log(ev("voice_heard")
                .putOpt("text", text)
                .putOpt("trig", matchedTrigger == null ? "" : matchedTrigger)
                .putOpt("partial", partial)
                .putOpt("lat", latencyMs));
    }

    /** Voice gate opened (level over threshold) and a recognition session started. */
    public static void gateOpen(String mode, double db) {
        log(ev("gate_open").putOpt("mode", mode).putOpt("db", Math.round(db * 10) / 10.0));
    }

    /** Stream mode did not work on this phone; switched to restart mode. */
    public static void gateFallback(String reason) {
        log(ev("gate_fallback").putOpt("reason", reason));
    }

    public static void voiceError(int code, String name) {
        log(ev("voice_error").putOpt("code", code).putOpt("name", name));
    }

    // ------------------------------------------------------------------ macro runs

    /** @return run id used to correlate later events */
    public static long runStart(String macroId, String trigger, int steps) {
        long run = System.currentTimeMillis();
        String src = nextRunSource;
        nextRunSource = "manual";
        lastRunId = run;
        log(ev("run_start")
                .putOpt("run", run)
                .putOpt("id", macroId)
                .putOpt("trig", trigger)
                .putOpt("steps", steps)
                .putOpt("src", src));
        return run;
    }

    public static void runFail(long run, int stepIndex, String reason) {
        log(ev("run_fail").putOpt("run", run).putOpt("i", stepIndex).putOpt("reason", reason));
    }

    public static void runEnd(long run, boolean reportedOk, int failures, int firstFail, long durationMs, boolean cancelled) {
        log(ev("run_end")
                .putOpt("run", run)
                .putOpt("ok", reportedOk)
                .putOpt("fails", failures)
                .putOpt("first", firstFail)
                .putOpt("ms", durationMs)
                .putOpt("cancelled", cancelled));
    }

    /** Human ground truth: did the macro really reach the right end state? */
    public static void verdict(long run, boolean ok, int brokeAtStep) {
        log(ev("verdict").putOpt("run", run).putOpt("ok", ok).putOpt("broke", brokeAtStep));
    }

    public static void benchVoice(VoiceBench.Trial t) {
        if (t == null) return;
        log(ev("bench_voice")
                .putOpt("exp", t.expected)
                .putOpt("got", t.got)
                .putOpt("res", t.res.name())
                .putOpt("lat", t.latencyMs));
    }

    // ------------------------------------------------------------------ read / report

    /** Blocking read of all events (call off the main thread, or accept a short stall on the debug screen). */
    public static List<JSONObject> readAll() {
        List<JSONObject> out = new ArrayList<>();
        if (dir == null) return out;
        try {
            // Wait for queued writes so the report includes the latest events.
            Future<?> f = io.submit(() -> {});
            f.get();
        } catch (Throwable ignored) {}
        readInto(new File(dir, "metrics.old.jsonl"), out);
        readInto(new File(dir, "metrics.jsonl"), out);
        return out;
    }

    public static String report() {
        return MetricsSummary.format(MetricsSummary.compute(readAll()), deviceInfo);
    }

    public static void clear() {
        if (dir == null) return;
        io.execute(() -> {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, "metrics.jsonl").delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, "metrics.old.jsonl").delete();
        });
    }

    // ------------------------------------------------------------------ internals

    private static Ev ev(String type) {
        return new Ev(type);
    }

    private static void log(Ev e) {
        final File d = dir;
        if (d == null) return;
        final String line = e.o.toString() + "\n";
        io.execute(() -> {
            try {
                File f = new File(d, "metrics.jsonl");
                if (f.length() > MAX_BYTES) {
                    File old = new File(d, "metrics.old.jsonl");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(old);
                }
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable t) {
                Log.w(TAG, "metrics write failed", t);
            }
        });
    }

    private static void readInto(File f, List<JSONObject> out) {
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                try { out.add(new JSONObject(line)); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "metrics read failed", t);
        }
    }

    private static String buildDeviceInfo(Context c) {
        String ver = "?";
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            ver = pi.versionName;
        } catch (Throwable ignored) {}
        boolean onDevice = false;
        try {
            onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c);
        } catch (Throwable ignored) {}
        return "device=" + Build.MANUFACTURER + " " + Build.MODEL
                + " android=" + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")"
                + " app=" + ver
                + " onDeviceSTT=" + onDevice;
    }

    /** Small builder so putOpt chains don't need try/catch at each call site. */
    private static final class Ev {
        final JSONObject o = new JSONObject();

        Ev(String type) {
            putOpt("t", System.currentTimeMillis());
            putOpt("e", type);
        }

        Ev putOpt(String k, Object v) {
            try { o.put(k, v == null ? JSONObject.NULL : v); } catch (Throwable ignored) {}
            return this;
        }
    }
}
