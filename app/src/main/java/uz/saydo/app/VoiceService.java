package uz.saydo.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground (mikrofon) servis: ovozni 100% offline va fonda doimiy tinglaydi,
 * trigger so'z aytilsa mos makroni GestureService orqali bajaradi.
 */
public class VoiceService extends Service {

    static final String ACTION_STOP = "uz.saydo.app.STOP";
    private static final String CHANNEL = "vf_listen";
    private static final int NOTIF_ID = 17;
    private static final long COOLDOWN_MS = 3000;
    private static final long WATCHDOG_MS = 10000;

    /** Servis hozir ishlayaptimi — UI shu bayroqni o'qiydi. */
    static volatile boolean running = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private SpeechRecognizer recognizer;
    private AudioManager audioManager;
    private boolean forceStandardRecognizer = false;
    private boolean volumeMuted = false;
    private int originalSysVol = -1;
    private int originalNotifVol = -1;

    private boolean destroyed = false;
    private boolean paused = false;      // makro bajarilayotganda tinglamaymiz
    private long cooldownUntil = 0;
    private int errorStreak = 0;

    private final List<JSONObject> macros = new ArrayList<JSONObject>();
    private final List<String> triggers = new ArrayList<String>();
    private final List<String> rawTriggers = new ArrayList<String>();

    // Telefon qulfda bo'lganda aytilgan trigger: qulf ochilgach Home'dan ishga tushadi
    private JSONObject queued;
    private long queuedUntil;
    private BroadcastReceiver screenRx;

    private final Runnable listenRunnable = new Runnable() {
        @Override public void run() { listen(); }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || paused) return;
            // Recognizer hech qanday callback bermay qotib qolsa, qayta tiklaymiz
            destroyRecognizer();
            createRecognizer();
            scheduleListen(100);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "SayDo listening", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification n = buildNotification("Listening · English (offline)");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            toast("Could not start microphone service");
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        destroyed = false;
        acquireWakeLock();
        FloatService.refresh(this);

        boolean canRecognize = SpeechRecognizer.isRecognitionAvailable(this)
                || (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
        if (!canRecognize) {
            toast("Speech recognition not available on this device");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (recognizer == null) createRecognizer();
        h.removeCallbacks(listenRunnable);
        h.post(listenRunnable);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App recent apps'dan o'chirilganda ham servis tirik qolishi kerak
        if (Store.isEnabled(this)) {
            try {
                Intent restart = new Intent(getApplicationContext(), VoiceService.class);
                PendingIntent pi = PendingIntent.getService(
                        getApplicationContext(), 99, restart,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null) {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pi);
                }
            } catch (Exception ignored) { }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        cancelWatchdog();
        restoreAudioVolume();
        unregisterScreenReceiver();
        releaseWakeLock();
        FloatService.refresh(this);
        h.removeCallbacksAndMessages(null);
        destroyRecognizer();
        super.onDestroy();
    }

    // ------------------------------------------------------------- audio beep muting

    private void muteBeep() {
        if (audioManager == null || volumeMuted) return;
        try {
            if (!audioManager.isMusicActive()) {
                originalSysVol = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM);
                originalNotifVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0);
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0);
                volumeMuted = true;
            }
        } catch (Exception ignored) { }
    }

    private void restoreAudioVolume() {
        if (!volumeMuted || audioManager == null) return;
        volumeMuted = false;
        try {
            if (originalSysVol >= 0) audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, originalSysVol, 0);
            if (originalNotifVol >= 0) audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotifVol, 0);
        } catch (Exception ignored) { }
        originalSysVol = -1;
        originalNotifVol = -1;
    }

    // ------------------------------------------------------------- watchdog

    private void resetWatchdog(long timeoutMs) {
        h.removeCallbacks(watchdogRunnable);
        h.postDelayed(watchdogRunnable, timeoutMs);
    }

    private void cancelWatchdog() {
        h.removeCallbacks(watchdogRunnable);
    }

    // ------------------------------------------------------------- recognizer

    private void createRecognizer() {
        destroyRecognizer();
        try {
            if (!forceStandardRecognizer && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                // Qurilmaning o'zidagi (100% internetsiz) on-device motor
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            }
        } catch (Exception e) {
            recognizer = null;
        }
        if (recognizer == null) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            } catch (Exception e) {
                recognizer = null;
            }
        }
        if (recognizer != null) {
            try {
                recognizer.setRecognitionListener(listener);
            } catch (Exception e) {
                recognizer = null;
            }
        }
    }

    private boolean modelChecked = false;

    private static boolean hasEnglish(List<String> langs) {
        if (langs == null) return false;
        for (String l : langs) {
            if (l != null && l.toLowerCase(java.util.Locale.ROOT).startsWith("en")) return true;
        }
        return false;
    }

    /** Android 13+: offline inglizcha ovoz paketi mavjudligini tekshiradi */
    private void ensureOfflineModel() {
        if (modelChecked || Build.VERSION.SDK_INT < 33 || recognizer == null) return;
        modelChecked = true;
        try {
            final Intent probe = buildIntent();
            recognizer.checkRecognitionSupport(probe, getMainExecutor(), new RecognitionSupportCallback() {
                @Override public void onSupportResult(RecognitionSupport support) {
                    if (hasEnglish(support.getInstalledOnDeviceLanguages())) return;
                    if (hasEnglish(support.getSupportedOnDeviceLanguages())) {
                        try {
                            if (recognizer != null) recognizer.triggerModelDownload(probe);
                            toast("Downloading offline English voice pack…");
                        } catch (Exception ignored) { }
                    }
                }
                @Override public void onError(int error) { }
            });
        } catch (Exception ignored) { }
    }

    private void destroyRecognizer() {
        cancelWatchdog();
        restoreAudioVolume();
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private Intent buildIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        // Free-form qisqa buyruqlar va xilma-xil iboralar uchun eng yaxshi offline model
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // 100% Offline rejim: internetga bormasin
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra("android.speech.extra.PREFER_OFFLINE", true);
        i.putExtra("android.speech.extra.DICTATION_MODE", true);
        i.putExtra("android.speech.extra.MASK_OFFENSIVE_WORDS", false);

        // Foydalanuvchi triggerlariga yo'naltirish (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 && !rawTriggers.isEmpty()) {
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, new ArrayList<String>(rawTriggers));
        }

        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L);
        return i;
    }

    private void loadMacros() {
        macros.clear();
        triggers.clear();
        rawTriggers.clear();
        try {
            JSONArray arr = new JSONArray(Store.getMacros(this));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                macros.add(o);
                triggers.add(TriggerMatcher.norm(o.optString("trigger", "")));
                rawTriggers.add(o.optString("trigger", ""));
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
            scheduleListen(600);
            return;
        }
        loadMacros();
        if (triggers.isEmpty()) {
            updateNotification("No shortcuts — add one with START");
            scheduleListen(3000);
            return;
        }
        if (recognizer == null) createRecognizer();
        try {
            acquireWakeLock();
            if (recognizer == null) createRecognizer();
            if (recognizer == null) {
                scheduleListen(1500);
                return;
            }
            ensureOfflineModel();
            muteBeep();
            resetWatchdog(WATCHDOG_MS);
            recognizer.startListening(buildIntent());
            updateNotification("Listening · English (offline)");
        } catch (Exception e) {
            restoreAudioVolume();
            cancelWatchdog();
            createRecognizer();
            errorStreak++;
            scheduleListen(600);
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            restoreAudioVolume();
        }

        @Override
        public void onBeginningOfSpeech() {
            resetWatchdog(8000);
        }

        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override
        public void onPartialResults(Bundle partialResults) {
            resetWatchdog(6000);
            // Qisman natijada juda aniq mos kelsa darhol bajariladi
            handleResults(partialResults, TriggerMatcher.EXACT, true);
        }

        @Override
        public void onResults(Bundle results) {
            cancelWatchdog();
            restoreAudioVolume();
            errorStreak = 0;
            if (!handleResults(results, TriggerMatcher.FUZZY, false)) {
                scheduleListen(100);
            } else if (!paused) {
                // Agar qulf ochilishini kutayotgan bo'lsa, tinglashni to'xtatmaymiz
                scheduleListen(600);
            }
        }

        @Override
        public void onError(int error) {
            cancelWatchdog();
            restoreAudioVolume();
            if (destroyed || paused) return;
            long delay = 150;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    errorStreak = 0;
                    delay = 100;
                    break;
                case SpeechRecognizer.ERROR_CLIENT:
                    errorStreak++;
                    if (errorStreak >= 2) createRecognizer();
                    delay = 250;
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    createRecognizer();
                    delay = 400;
                    break;
                case SpeechRecognizer.ERROR_AUDIO:
                    createRecognizer();
                    delay = 500;
                    break;
                case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
                case 13: // ERROR_LANGUAGE_UNAVAILABLE
                    // On-device API ushbu qurilmada mavjud bo'lmasa, standart motorga o'tamiz
                    forceStandardRecognizer = true;
                    createRecognizer();
                    delay = 800;
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    toast("Microphone permission missing");
                    stopForeground(true);
                    stopSelf();
                    return;
                default:
                    errorStreak++;
                    if (errorStreak >= 2) createRecognizer();
                    delay = Math.min(250L * errorStreak, 2500L);
                    break;
            }
            scheduleListen(delay);
        }
    };

    private boolean handleResults(Bundle b, double threshold, boolean partial) {
        if (b == null || destroyed || paused || FloatService.recording) return false;
        ArrayList<String> heard = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (heard == null || heard.isEmpty()) return false;
        if (System.currentTimeMillis() < cooldownUntil) return false;
        int idx = TriggerMatcher.best(heard, triggers, threshold);
        if (idx < 0 || idx >= macros.size()) return false;
        // Qisman natijada uzunroq trigger bo'lishi mumkin bo'lsa, yakuniy natijani kutamiz
        if (partial && TriggerMatcher.hasLongerSibling(triggers, idx)) return false;
        fire(idx);
        return true;
    }

    // ------------------------------------------------------------- makro ishga tushirish

    private void fire(int idx) {
        if (paused || destroyed || idx < 0 || idx >= macros.size()) return;
        JSONObject m = macros.get(idx);
        if (m == null) return;
        // Qulf ekranida yozilgan makro qulf ekranidan ijro etiladi; boshqalari qulf ochilishini kutadi
        if (Setup.isLocked(this) && !m.optBoolean("locked", false)) {
            queueUntilUnlock(m);
            return;
        }
        runMacro(m);
    }

    /** Qulf ekranida aytilgan: qulf ochilgach Home'dan ishga tushadi. */
    private void queueUntilUnlock(JSONObject m) {
        queued = m;
        queuedUntil = System.currentTimeMillis() + 45000;
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
        updateNotification("Unlock phone to run: " + m.optString("trigger", ""));
    }

    private void runMacro(final JSONObject m) {
        if (paused || destroyed || m == null) return;
        final String name = m.optString("trigger", "");
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
        paused = true;
        cancelWatchdog();
        restoreAudioVolume();
        h.removeCallbacks(listenRunnable);
        try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) { }

        updateNotification("Running: " + name);
        int st = Runner.run(this, m, new GestureService.Done() {
            @Override
            public void onDone(boolean completed) {
                updateNotification(completed ? "Done: " + name : "Stopped: " + name);
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        paused = false;
                        errorStreak = 0;
                        listen();
                    }
                }, 800);
            }
        });
        if (st == Runner.NO_ACCESSIBILITY) {
            toast("Enable Accessibility first");
            updateNotification("Accessibility is off");
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    paused = false;
                    listen();
                }
            }, 1500);
        } else if (st == Runner.INVALID || st == Runner.LOCKED) {
            paused = false;
            scheduleListen(st == Runner.INVALID ? 400 : 1000);
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
                Icon.createWithResource(this, R.drawable.ic_stat_close), "Stop", pStop).build();

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("SayDo")
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
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(text));
            }
        } catch (Exception ignored) { }
    }

    private void registerScreenReceiver() {
        if (screenRx != null) return;
        screenRx = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String act = intent.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(act)) {
                    final JSONObject q = queued;
                    boolean fresh = q != null && System.currentTimeMillis() < queuedUntil;
                    queued = null;
                    if (fresh && !destroyed) {
                        h.postDelayed(new Runnable() {
                            @Override public void run() { runMacro(q); }
                        }, 500);
                    }
                } else if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                    // Ekran o'chganda wake lock va tinglash davom etishini ta'minlash
                    acquireWakeLock();
                    if (running && !paused && !destroyed) {
                        scheduleListen(200);
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(act)) {
                    acquireWakeLock();
                    if (running && !paused && !destroyed) {
                        scheduleListen(100);
                    }
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenRx, f);
        } catch (Exception e) {
            screenRx = null;
        }
    }

    private void unregisterScreenReceiver() {
        if (screenRx != null) {
            try { unregisterReceiver(screenRx); } catch (Exception ignored) { }
            screenRx = null;
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SayDo:listen");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
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
