package uz.saydo.app.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Loudness gate on the microphone.
 *
 * Reads 16 kHz mono PCM in 20 ms frames on its own thread and:
 *  - measures the level of every frame (dBFS),
 *  - keeps the last {@link #PREROLL_MS} ms in a ring buffer,
 *  - when armed and the level stays above the threshold, disarms itself and calls
 *    {@link Callback#onGateOpen(double)} once (on the capture thread),
 *  - when streaming, writes the pre-roll and then the live audio to an OutputStream
 *    (the SpeechRecognizer pipe), so the start of the word is not lost.
 *
 * The gate only runs while it is started; the owner (VoiceService) starts it only when
 * listening is switched on in the app.
 */
public final class VoiceGate {
    private static final String TAG = "VoiceGate";

    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_MS = 20;
    public static final int FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000; // 640
    public static final int PREROLL_MS = 1200;
    /** Loud frames in a row needed to open (3 × 20 ms = 60 ms). */
    public static final int OPEN_FRAMES = 3;
    private static final int LEVEL_EVERY_FRAMES = 3; // ~60 ms UI updates

    public interface Callback {
        /** Called once per arm, on the capture thread. */
        void onGateOpen(double db);

        /** Capture ended without stop() (mic taken by a call/another app). Capture thread. */
        void onGateLost();
    }

    public interface LevelListener {
        /** Called on the capture thread roughly every 60 ms. */
        void onLevel(double db);
    }

    private final AudioLevel.Detector detector =
            new AudioLevel.Detector(AudioLevel.DEFAULT_THRESHOLD_DB, OPEN_FRAMES);
    private final AudioLevel.PreRoll preRoll =
            new AudioLevel.PreRoll(SAMPLE_RATE * 2 * PREROLL_MS / 1000);

    private volatile Callback callback;
    private volatile LevelListener levelListener;
    private volatile boolean armed;
    private volatile boolean running;
    private volatile OutputStream stream;
    private volatile boolean flushPreRoll;
    private volatile double lastDb = AudioLevel.MIN_DB;

    private AudioRecord record;
    private Thread thread;

    public void setCallback(Callback cb) {
        callback = cb;
    }

    public void setLevelListener(LevelListener l) {
        levelListener = l;
    }

    public boolean isRunning() {
        return running;
    }

    public double lastLevel() {
        return lastDb;
    }

    /**
     * Opens the microphone and starts the capture thread.
     * @return false if the mic could not be opened (no permission, or busy)
     */
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    public synchronized boolean start() {
        if (running) return true;
        if (record != null || thread != null) stop(); // clean up after a lost mic
        AudioRecord r = null;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min, FRAME_BYTES * 8);
            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return false;
            }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "mic open failed", t);
            if (r != null) {
                try { r.release(); } catch (Throwable ignored) {}
            }
            return false;
        }
        record = r;
        preRoll.clear();
        detector.reset();
        running = true;
        final AudioRecord rec = r;
        thread = new Thread(() -> loop(rec), "SayDo-gate");
        thread.start();
        return true;
    }

    /** Stops capture and releases the microphone (blocks briefly for the thread to exit). */
    public synchronized void stop() {
        if (record == null && thread == null) return;
        running = false;
        armed = false;
        AudioRecord r = record;
        record = null;
        try { if (r != null) r.stop(); } catch (Throwable ignored) {}
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(400); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try { if (r != null) r.release(); } catch (Throwable ignored) {}
        closeStream();
    }

    public void arm(float thresholdDb) {
        detector.setThreshold(AudioLevel.clampThreshold(thresholdDb));
        detector.reset();
        armed = true;
    }

    public void disarm() {
        armed = false;
    }

    public void setThreshold(float thresholdDb) {
        detector.setThreshold(AudioLevel.clampThreshold(thresholdDb));
    }

    /** Start sending pre-roll + live audio to {@code out}. Closed by {@link #stopStreaming()}. */
    public void startStreaming(OutputStream out) {
        armed = false;
        flushPreRoll = true;
        stream = out;
    }

    public void stopStreaming() {
        closeStream();
    }

    private void closeStream() {
        OutputStream s = stream;
        stream = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void loop(AudioRecord rec) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignored) {}
        byte[] buf = new byte[FRAME_BYTES];
        int frames = 0;
        while (running) {
            int n;
            try {
                n = rec.read(buf, 0, buf.length);
            } catch (Throwable t) {
                break;
            }
            if (n <= 0) {
                if (!running) break;
                // ERROR_INVALID_OPERATION / DEAD_OBJECT: mic was taken away.
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break;
                continue;
            }

            OutputStream out = stream;
            if (out != null) {
                try {
                    if (flushPreRoll) {
                        flushPreRoll = false;
                        byte[] pre = preRoll.snapshot();
                        out.write(pre);
                    }
                    out.write(buf, 0, n);
                } catch (IOException e) {
                    // Recognizer closed its end (session finished) — stop streaming.
                    if (stream == out) closeStream();
                }
                preRoll.clear();
                continue;
            }

            preRoll.write(buf, 0, n);
            double db = AudioLevel.dbfs(buf, 0, n);
            lastDb = db;
            if (++frames % LEVEL_EVERY_FRAMES == 0) {
                LevelListener l = levelListener;
                if (l != null) {
                    try { l.onLevel(db); } catch (Throwable ignored) {}
                }
            }
            if (armed && detector.feed(db)) {
                armed = false;
                Callback cb = callback;
                if (cb != null) {
                    try { cb.onGateOpen(db); } catch (Throwable ignored) {}
                }
            }
        }
        boolean lost = running; // still supposed to run -> mic was taken away
        running = false;
        if (lost) {
            Callback cb = callback;
            if (cb != null) {
                try { cb.onGateLost(); } catch (Throwable ignored) {}
            }
        }
    }
}
