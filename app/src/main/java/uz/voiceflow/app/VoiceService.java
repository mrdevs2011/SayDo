package uz.voiceflow.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.os.PowerManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground (mikrofon) servis: ovozni doimiy tinglaydi, trigger so'z aytilsa
 * mos makroni GestureService orqali bajaradi.
 */
public class VoiceService extends Service {

    static final String ACTION_STOP = "uz.voiceflow.app.STOP";
    private static final String CHANNEL = "vf_listen";
    private static final int NOTIF_ID = 17;
    private static final long COOLDOWN_MS = 4000;

    /** Servis hozir ishlayaptimi — UI shu bayroqni o'qiydi. */
    static volatile boolean running = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private SpeechRecognizer recognizer;
    private boolean destroyed = false;
    private boolean paused = false;      // makro bajarilayotganda tinglamaymiz
    private long cooldownUntil = 0;
    private int errorStreak = 0;

    private final List<JSONObject> macros = new ArrayList<JSONObject>();
    private final List<String> triggers = new ArrayList<String>();

    private final Runnable listenRunnable = new Runnable() {
        @Override public void run() { listen(); }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "VoiceFlow tinglash", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification n = buildNotification("Ishga tushmoqda…");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            toast("Mikrofon xizmatini ishga tushirib bo'lmadi");
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        destroyed = false;
        acquireWakeLock();
        FloatService.refresh(this);

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Telefonda ovoz tanish xizmati (Google) topilmadi");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (recognizer == null) createRecognizer();
        h.removeCallbacks(listenRunnable);
        h.post(listenRunnable);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        releaseWakeLock();
        FloatService.refresh(this);
        h.removeCallbacksAndMessages(null);
        destroyRecognizer();
        super.onDestroy();
    }

    // ------------------------------------------------------------- recognizer

    private void createRecognizer() {
        destroyRecognizer();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            if (recognizer != null) recognizer.setRecognitionListener(listener);
        } catch (Exception e) {
            recognizer = null;
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private Intent buildIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Store.getLang(this));
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        return i;
    }

    private void loadMacros() {
        macros.clear();
        triggers.clear();
        try {
            JSONArray arr = new JSONArray(Store.getMacros(this));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                macros.add(o);
                triggers.add(TriggerMatcher.norm(o.optString("trigger", "")));
            }
        } catch (Exception ignored) { }
    }

    private void scheduleListen(long delayMs) {
        if (destroyed) return;
        h.removeCallbacks(listenRunnable);
        h.postDelayed(listenRunnable, delayMs);
    }

    private void listen() {
        if (destroyed || paused) return;
        if (!Store.isEnabled(this)) {
            stopForeground(true);
            stopSelf();
            return;
        }
        if (FloatService.recording) {
            scheduleListen(800);
            return;
        }
        loadMacros();
        if (triggers.isEmpty()) {
            updateNotification("Shortcut yo'q — ilovada qo'shing");
            scheduleListen(3000);
            return;
        }
        if (recognizer == null) createRecognizer();
        try {
            acquireWakeLock();
            if (recognizer == null) createRecognizer();
            if (recognizer == null) {
                scheduleListen(2000);
                return;
            }
            recognizer.startListening(buildIntent());
            updateNotification("Tinglanmoqda · " + langLabel());
        } catch (Exception e) {
            createRecognizer();
            errorStreak++;
            scheduleListen(1000);
        }
    }

    private String langLabel() {
        String l = Store.getLang(this);
        return l.length() >= 2 ? l.substring(0, 2).toUpperCase() : l;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partialResults) {
            // Qisman natijada faqat juda aniq moslikda darhol ishga tushiramiz.
            handleResults(partialResults, TriggerMatcher.EXACT);
        }

        @Override
        public void onResults(Bundle results) {
            errorStreak = 0;
            if (!handleResults(results, TriggerMatcher.FUZZY)) {
                scheduleListen(200);
            }
        }

        @Override
        public void onError(int error) {
            if (destroyed || paused) return;
            long delay;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    errorStreak = 0;
                    delay = 200;
                    break;
                case SpeechRecognizer.ERROR_CLIENT:
                    delay = 400;
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    createRecognizer();
                    delay = 1000;
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    toast("Mikrofon ruxsati yo'q");
                    stopForeground(true);
                    stopSelf();
                    return;
                default:
                    errorStreak++;
                    delay = Math.min(500L << Math.min(errorStreak, 4), 8000L);
                    if (errorStreak >= 3) createRecognizer();
                    break;
            }
            scheduleListen(delay);
        }
    };

    private boolean handleResults(Bundle b, double threshold) {
        if (b == null || destroyed || paused || FloatService.recording) return false;
        ArrayList<String> heard = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (heard == null || heard.isEmpty()) return false;
        if (System.currentTimeMillis() < cooldownUntil) return false;
        int idx = TriggerMatcher.best(heard, triggers, threshold);
        if (idx < 0 || idx >= macros.size()) return false;
        fire(idx);
        return true;
    }

    // ------------------------------------------------------------- makro ishga tushirish

    private void fire(int idx) {
        if (paused || destroyed || idx < 0 || idx >= macros.size()) return;
        final JSONObject m = macros.get(idx);
        if (m == null) return;
        final String name = m.optString("trigger", "");
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
        paused = true;
        h.removeCallbacks(listenRunnable);
        try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) { }

        updateNotification("Bajarilmoqda: " + name);
        int st = Runner.run(this, m, new GestureService.Done() {
            @Override
            public void onDone(boolean completed) {
                updateNotification(completed ? "Bajarildi: " + name : "To'xtatildi: " + name);
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        paused = false;
                        errorStreak = 0;
                        listen();
                    }
                }, 1200);
            }
        });
        if (st == Runner.NO_ACCESSIBILITY) {
            toast("Avval Accessibility ni yoqing");
            updateNotification("Accessibility yoqilmagan");
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    paused = false;
                    listen();
                }
            }, 2000);
        } else if (st == Runner.INVALID) {
            paused = false;
            scheduleListen(500);
        }
    }

    // ------------------------------------------------------------- bildirishnoma

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, VoiceService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_close), "To'xtatish", pStop).build();

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("VoiceFlow")
                .setContentText(text)
                .setContentIntent(pOpen)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(stopAction)
                .build();
    }

    private void updateNotification(String text) {
        if (destroyed) return;
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) { }
    }


    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceFlow:listen");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(60 * 60 * 1000L);
        } catch (Exception ignored) { }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) { }
    }

    private void toast(final String msg) {
        h.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
