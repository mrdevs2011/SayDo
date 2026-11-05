package uz.saydo.app;

import uz.saydo.app.metrics.Metrics;
import uz.saydo.app.metrics.VoiceBench;
import uz.saydo.app.util.DebugLog;

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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
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
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.data.SettingsRepository;

/**
 * Production-hardened Continuous Offline Speech Recognition Engine.
 * Operates under a formalized Finite State Machine (FSM):
 * STOPPED -> PREPARING -> LISTENING -> PROCESSING -> EXECUTING_MACRO -> COOLDOWN -> BACKOFF_RECOVERY.
 * Resilient against all 13+ Android SpeechRecognizer errors, audio focus changes,
 * screen power states, and lock-screen macro queuing.
 */
public class VoiceService extends Service implements
        MacroRepository.OnMacrosChangedListener,
        SettingsRepository.Listener {

    private static final String TAG = "VoiceService";
    public static final String ACTION_STOP = "uz.saydo.app.STOP";
    private static final String CHANNEL_ID = "vf_listen";
    private static final int NOTIF_ID = 17;

    private static final long COOLDOWN_MS = 1800;
    private static final long WATCHDOG_MS = 10000;
    private static final long GUARD_INTERVAL_MS = 250;

    public static volatile boolean running = false;

    public enum State {
        STOPPED,
        PREPARING,
        LISTENING,
        PROCESSING,
        EXECUTING_MACRO,
        COOLDOWN,
        BACKOFF_RECOVERY
    }

    private volatile State state = State.STOPPED;

    public interface StateListener {
        void onStateChanged(State state, String statusText);
    }

    private static final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();

    public static void addStateListener(StateListener l) {
        if (l != null && !stateListeners.contains(l)) {
            stateListeners.add(l);
        }
    }

    public static void removeStateListener(StateListener l) {
        if (l != null) {
            stateListeners.remove(l);
        }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private SpeechRecognizer recognizer;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private boolean destroyed = false;
    private int consecutiveErrors = 0;
    private long cooldownUntil = 0;
    private boolean callPaused = false;
    private boolean modelDownloadChecked = false;

    // Metrics: speech timing for the current recognizer session
    private long speechBeginAt = 0;
    private long speechEndAt = 0;
    private long pendingMatchLatency = -1;

    // Macro Cache directly synced from MacroRepository
    private final List<Macro> cachedMacros = new CopyOnWriteArrayList<>();
    private final List<String> cachedTriggers = new CopyOnWriteArrayList<>();
    private final List<String> cachedRawTriggers = new CopyOnWriteArrayList<>();

    // Lock-screen queued macro
    private Macro queuedMacro;
    private long queuedMacroExpiry;
    private BroadcastReceiver screenReceiver;

    private final Runnable listenRunnable = this::startListeningInternal;

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || state == State.STOPPED || state == State.EXECUTING_MACRO) return;
            Log.w(TAG, "SpeechRecognizer watchdog triggered in state: " + state);
            transitionTo(State.BACKOFF_RECOVERY);
            recreateRecognizerSafely();
            scheduleListen(GUARD_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setupAudioFocus();
        registerScreenReceiver();

        // Reactive repository subscriptions
        MacroRepository.getInstance(this).addListener(this);
        SettingsRepository.getInstance(this).addListener(this);
        updateCachedMacros(MacroRepository.getInstance(this).getAll());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification n = buildNotification("Listening · Speak your trigger");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Could not start microphone foreground service", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        DebugLog.i("Voice", "service start masterOn triggers will load");
        destroyed = false;
        callPaused = false;
        acquireWakeLock();
        FloatService.refresh(this);

        boolean available = SpeechRecognizer.isRecognitionAvailable(this)
                || (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
        if (!available) {
            toast("Speech recognition is not available on this device");
            stopSelf();
            return START_NOT_STICKY;
        }

        transitionTo(State.PREPARING);
        recreateRecognizerSafely();
        scheduleListen(100);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (SettingsRepository.getInstance(this).isMasterOn()) {
            try {
                Intent restart = new Intent(getApplicationContext(), VoiceService.class);
                PendingIntent pi = PendingIntent.getService(
                        getApplicationContext(), 99, restart,
                        PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null) {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pi);
                }
            } catch (Throwable ignored) {
            }
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
        transitionTo(State.STOPPED);

        cancelWatchdog();
        abandonAudioFocus();
        unregisterScreenReceiver();
        releaseWakeLock();

        MacroRepository.getInstance(this).removeListener(this);
        SettingsRepository.getInstance(this).removeListener(this);

        FloatService.refresh(this);
        h.removeCallbacksAndMessages(null);
        destroyRecognizer();

        super.onDestroy();
    }

    // ------------------------------------------------------------- Finite State Machine (FSM)

    public State getState() {
        return state;
    }

    private synchronized boolean transitionTo(State newState) {
        if (this.state == newState) return true;
        Log.d(TAG, "FSM Transition: " + this.state + " -> " + newState);
        this.state = newState;

        // WakeLock Management tied strictly to FSM
        if (newState == State.LISTENING || newState == State.PREPARING || newState == State.PROCESSING) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }

        // Notify State Listeners
        String desc = getStateDescription(newState);
        for (StateListener l : stateListeners) {
            try {
                l.onStateChanged(newState, desc);
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private String getStateDescription(State s) {
        switch (s) {
            case LISTENING: return "Listening · Speak trigger";
            case PROCESSING: return "Processing speech…";
            case EXECUTING_MACRO: return "Running macro…";
            case PREPARING: return "Preparing listener…";
            case COOLDOWN: return "Cooling down…";
            case BACKOFF_RECOVERY: return "Recovering audio…";
            case STOPPED: default: return "Stopped";
        }
    }

    // ------------------------------------------------------------- Reactive Data Updates

    @Override
    public void onMacrosChanged(List<Macro> macros) {
        updateCachedMacros(macros);
        if (state == State.LISTENING && cachedTriggers.isEmpty()) {
            updateNotification("No shortcuts — add one with START");
        }
    }

    @Override
    public void onSettingChanged(String key) {
        if (SettingsRepository.KEY_MASTER_ON.equals(key)) {
            if (!SettingsRepository.getInstance(this).isMasterOn()) {
                stopForeground(true);
                stopSelf();
            }
        }
    }

    private void updateCachedMacros(List<Macro> macros) {
        cachedMacros.clear();
        cachedTriggers.clear();
        cachedRawTriggers.clear();
        if (macros != null) {
            for (Macro m : macros) {
                if (m != null && !m.trigger.isEmpty()) {
                    cachedMacros.add(m);
                    cachedTriggers.add(TriggerMatcher.norm(m.trigger));
                    cachedRawTriggers.add(m.trigger);
                }
            }
        }
    }

    // ------------------------------------------------------------- Watchdog Timer

    private void resetWatchdog(long timeoutMs) {
        h.removeCallbacks(watchdogRunnable);
        h.postDelayed(watchdogRunnable, timeoutMs);
    }

    private void cancelWatchdog() {
        h.removeCallbacks(watchdogRunnable);
    }

    // ------------------------------------------------------------- Recognizer Lifecycle

    private void recreateRecognizerSafely() {
        destroyRecognizer();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            if (recognizer != null) {
                recognizer.setRecognitionListener(recognitionListener);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error initializing SpeechRecognizer", t);
            recognizer = null;
        }
    }

    private void destroyRecognizer() {
        cancelWatchdog();
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
    }

    private void scheduleListen(long delayMs) {
        if (destroyed || callPaused) return;
        h.removeCallbacks(listenRunnable);
        h.postDelayed(listenRunnable, Math.max(20, delayMs));
    }

    private void startListeningInternal() {
        if (destroyed || callPaused || state == State.EXECUTING_MACRO) return;
        if (!SettingsRepository.getInstance(this).isMasterOn()) {
            stopForeground(true);
            stopSelf();
            return;
        }
        if (FloatService.recording) {
            scheduleListen(600);
            return;
        }
        if (cachedTriggers.isEmpty()) {
            updateNotification("No shortcuts — add one with START");
            scheduleListen(3000);
            return;
        }

        transitionTo(State.PREPARING);

        if (recognizer == null) {
            recreateRecognizerSafely();
            if (recognizer == null) {
                consecutiveErrors++;
                transitionTo(State.BACKOFF_RECOVERY);
                scheduleListen(1000);
                return;
            }
        }

        try {
            ensureOfflineModel();
            resetWatchdog(WATCHDOG_MS);
            speechBeginAt = 0;
            speechEndAt = 0;
            DebugLog.stt("startListening triggers=" + cachedTriggers.size() + " offlineOk check");
            recognizer.startListening(buildIntent());
            transitionTo(State.LISTENING);
            updateNotification("Listening · Speak your trigger");
        } catch (Throwable e) {
            Log.w(TAG, "startListening error", e);
            cancelWatchdog();
            consecutiveErrors++;
            transitionTo(State.BACKOFF_RECOVERY);
            recreateRecognizerSafely();
            long backoff = Math.min(200L * consecutiveErrors, 2000L);
            scheduleListen(backoff);
        }
    }

    private Intent buildIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Online STT is more reliable when offline English pack is missing
        // Prefer offline only if the device reports an offline recognizer.
        boolean offlineOk = false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                offlineOk = SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
            }
        } catch (Throwable ignored) {}
        if (offlineOk) {
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        }

        if (Build.VERSION.SDK_INT >= 33 && !cachedRawTriggers.isEmpty()) {
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, new ArrayList<>(cachedRawTriggers));
        }

        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L);
        return i;
    }

    private void ensureOfflineModel() {
        if (modelDownloadChecked || Build.VERSION.SDK_INT < 33 || recognizer == null) return;
        modelDownloadChecked = true;
        try {
            final Intent probe = buildIntent();
            recognizer.checkRecognitionSupport(probe, getMainExecutor(), new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport support) {
                    if (hasEnglish(support.getInstalledOnDeviceLanguages())) return;
                    if (hasEnglish(support.getSupportedOnDeviceLanguages())) {
                        try {
                            if (recognizer != null) recognizer.triggerModelDownload(probe);
                            toast("Downloading offline English voice pack…");
                        } catch (Throwable ignored) {
                        }
                    }
                }
                @Override
                public void onError(int error) {
            DebugLog.stt("onError code=" + error + " " + errorName(error));}
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasEnglish(List<String> langs) {
        if (langs == null) return false;
        for (String l : langs) {
            if (l != null && l.toLowerCase(Locale.ROOT).startsWith("en")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- RecognitionListener

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            if (state != State.EXECUTING_MACRO) {
                transitionTo(State.LISTENING);
                updateNotification("Listening · Speak trigger");
            }
        }

        @Override
        public void onBeginningOfSpeech() {
            speechBeginAt = SystemClock.uptimeMillis();
            speechEndAt = 0;
            if (state != State.EXECUTING_MACRO) {
                transitionTo(State.PROCESSING);
                resetWatchdog(10000);
                updateNotification("Hearing speech…");
            }
        }

        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            speechEndAt = SystemClock.uptimeMillis();
            if (state != State.EXECUTING_MACRO) {
                transitionTo(State.PROCESSING);
                updateNotification("Processing speech…");
            }
        }

        @Override public void onEvent(int eventType, Bundle params) {}

        @Override
        public void onPartialResults(Bundle partialResults) {
            DebugLog.stt("onPartialResults");
            resetWatchdog(8000);
            if (partialResults != null && state != State.EXECUTING_MACRO) {
                ArrayList<String> heard = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (heard != null && !heard.isEmpty()) {
                    updateNotification("Hearing: \"" + heard.get(0) + "\"");
                }
                handleResults(partialResults, TriggerMatcher.EXACT, true);
            }
        }

        @Override
        public void onResults(Bundle results) {
            DebugLog.stt("onResults bundle=" + (results != null));
            cancelWatchdog();
            consecutiveErrors = 0;
            if (results != null && state != State.EXECUTING_MACRO) {
                ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (heard != null && !heard.isEmpty()) {
                    String top = heard.get(0);
                    DebugLog.stt("heard=" + top + " alts=" + heard);
                    long lat = speechEndAt > 0 ? SystemClock.uptimeMillis() - speechEndAt : -1;
                    VoiceBench vb = VoiceBench.active();
                    if (vb != null) vb.onHeard(top);
                    for (int ti = 0; ti < cachedTriggers.size(); ti++) {
                        double sc = TriggerMatcher.score(TriggerMatcher.norm(top), cachedTriggers.get(ti));
                        DebugLog.match("score[" + cachedTriggers.get(ti) + "]=" + String.format(java.util.Locale.US, "%.3f", sc));
                    }
                    int idx = TriggerMatcher.best(heard, cachedTriggers, TriggerMatcher.FUZZY);

                    if (idx < 0) {
                        for (int mIdx = 0; mIdx < cachedMacros.size(); mIdx++) {
                            Macro candidate = cachedMacros.get(mIdx);
                            if (uz.saydo.app.kernel.voice.ParametricMatcher.hasSlots(candidate.trigger)) {
                                uz.saydo.app.kernel.voice.ParametricMatcher.MatchResult pRes =
                                        uz.saydo.app.kernel.voice.ParametricMatcher.match(top, candidate.trigger);
                                if (pRes.matched) {
                                    for (java.util.Map.Entry<String, String> entry : pRes.variables.entrySet()) {
                                        uz.saydo.app.kernel.workflow.ContextMemory.getInstance().set("{" + entry.getKey() + "}", entry.getValue());
                                        uz.saydo.app.kernel.workflow.ContextMemory.getInstance().set(entry.getKey(), entry.getValue());
                                    }
                                    idx = mIdx;
                                    break;
                                }
                            }
                            if (uz.saydo.app.kernel.voice.MultilingualMatcher.matchesMultilingual(top, candidate.trigger)) {
                                idx = mIdx;
                                break;
                            }
                            for (String alias : candidate.aliases) {
                                if (uz.saydo.app.kernel.voice.MultilingualMatcher.matchesMultilingual(top, alias)) {
                                    idx = mIdx;
                                    break;
                                }
                            }
                            if (idx >= 0) break;
                        }
                    }

                    if (idx >= 0 && idx < cachedMacros.size()) {
                        Macro matched = cachedMacros.get(idx);
                        DebugLog.match("MATCHED trigger=" + matched.trigger);
                        Metrics.voiceHeard(top, matched.trigger, false, lat);
                        pendingMatchLatency = lat;
                        updateNotification("Running: " + matched.trigger);
                        fireMacro(matched);
                        return;
                    } else {
                        Metrics.voiceHeard(top, "", false, lat);
                        updateNotification("Heard: \"" + top + "\" (no match)");
                        transitionTo(State.COOLDOWN);
                        scheduleListen(400);
                        return;
                    }
                }
            }
            transitionTo(State.COOLDOWN);
            scheduleListen(100);
        }

        @Override
        public void onError(int error) {
            DebugLog.stt("onError code=" + error + " " + errorName(error));
            // SPEECH_TIMEOUT fires every few seconds of silence — not worth logging.
            // NO_MATCH only matters if the user actually spoke in this session.
            if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    && (error != SpeechRecognizer.ERROR_NO_MATCH || speechBeginAt > 0)) {
                Metrics.voiceError(error, errorName(error));
            }
            speechBeginAt = 0;
            speechEndAt = 0;
            cancelWatchdog();
            if (destroyed || callPaused || state == State.EXECUTING_MACRO) return;

            long delay = 120;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    consecutiveErrors = 0;
                    transitionTo(State.COOLDOWN);
                    delay = 100;
                    break;

                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    transitionTo(State.BACKOFF_RECOVERY);
                    recreateRecognizerSafely();
                    delay = GUARD_INTERVAL_MS;
                    break;

                case SpeechRecognizer.ERROR_CLIENT:
                    consecutiveErrors++;
                    transitionTo(State.BACKOFF_RECOVERY);
                    if (consecutiveErrors >= 2) recreateRecognizerSafely();
                    delay = Math.min(150L * consecutiveErrors, 1200L);
                    break;

                case SpeechRecognizer.ERROR_AUDIO:
                    transitionTo(State.BACKOFF_RECOVERY);
                    recreateRecognizerSafely();
                    delay = 400;
                    break;

                case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
                case 13: // ERROR_LANGUAGE_UNAVAILABLE
                    toast("Download offline English pack in Voice Settings");
                    transitionTo(State.BACKOFF_RECOVERY);
                    recreateRecognizerSafely();
                    delay = 1000;
                    break;

                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    toast("Microphone permission missing");
                    transitionTo(State.STOPPED);
                    stopForeground(true);
                    stopSelf();
                    return;

                default:
                    consecutiveErrors++;
                    transitionTo(State.BACKOFF_RECOVERY);
                    if (consecutiveErrors >= 3) recreateRecognizerSafely();
                    delay = Math.min(200L * consecutiveErrors, 2000L);
                    break;
            }
            scheduleListen(delay);
        }
    };

    private boolean handleResults(Bundle b, double threshold, boolean partial) {
        if (b == null || destroyed || callPaused || state == State.EXECUTING_MACRO || FloatService.recording) {
            return false;
        }
        ArrayList<String> heard = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (heard == null || heard.isEmpty()) return false;
        if (System.currentTimeMillis() < cooldownUntil) return false;

        int idx = TriggerMatcher.best(heard, cachedTriggers, threshold);
        if (idx < 0 || idx >= cachedMacros.size()) return false;

        // Disambiguate longer sibling prefix before firing on partial match
        if (partial && TriggerMatcher.hasLongerSibling(cachedTriggers, idx)) {
            return false;
        }

        // Partial results fire before end of speech, so STT latency is unknown here.
        Metrics.voiceHeard(heard.get(0), cachedMacros.get(idx).trigger, partial, -1);
        pendingMatchLatency = -1;

        fireMacro(cachedMacros.get(idx));
        return true;
    }

    // ------------------------------------------------------------- Macro Execution

    private void fireMacro(Macro m) {
        if (state == State.EXECUTING_MACRO || destroyed || m == null) return;

        VoiceBench bench = VoiceBench.active();
        if (bench != null) {
            reportBenchMatch(bench, m);
            return;
        }

        if (Setup.isLocked(this) && !m.locked) {
            queueUntilUnlock(m);
            return;
        }
        executeMacro(m);
    }

    /** Benchmark mode: score the match, do NOT run the macro, keep listening. */
    private void reportBenchMatch(VoiceBench bench, Macro m) {
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
        cancelWatchdog();
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
        }
        bench.onMatch(m.trigger, pendingMatchLatency);
        pendingMatchLatency = -1;
        updateNotification("Benchmark: " + m.trigger);
        transitionTo(State.COOLDOWN);
        scheduleListen(300);
    }

    private void queueUntilUnlock(Macro m) {
        queuedMacro = m;
        queuedMacroExpiry = System.currentTimeMillis() + 45000;
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
        updateNotification("Unlock phone to run: " + m.trigger);
        transitionTo(State.COOLDOWN);
        scheduleListen(1000);
    }

    private void executeMacro(final Macro m) {
        if (state == State.EXECUTING_MACRO || destroyed || m == null) return;
        final String name = m.trigger;
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;

        transitionTo(State.EXECUTING_MACRO);
        cancelWatchdog();
        h.removeCallbacks(listenRunnable);

        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
        }

        updateNotification("Running: " + name);
        Metrics.nextRunSource = "voice";
        int result = Runner.run(this, m, new GestureService.Done() {
            @Override
            public void onDone(boolean completed) {
                updateNotification(completed ? "Done: " + name : "Stopped: " + name);
                h.postDelayed(() -> {
                    transitionTo(State.COOLDOWN);
                    consecutiveErrors = 0;
                    startListeningInternal();
                }, 800);
            }
        });

        if (result == Runner.NO_ACCESSIBILITY) {
            toast("Enable Accessibility first");
            updateNotification("Accessibility is off");
            h.postDelayed(() -> {
                transitionTo(State.COOLDOWN);
                startListeningInternal();
            }, 1500);
        } else if (result == Runner.INVALID || result == Runner.LOCKED) {
            transitionTo(State.COOLDOWN);
            scheduleListen(result == Runner.INVALID ? 400 : 1000);
        }
    }

    // ------------------------------------------------------------- Audio Focus Handling

    private void setupAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this::onAudioFocusChanged)
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(
                    this::onAudioFocusChanged,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
    }

    private void onAudioFocusChanged(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Incoming phone call or media takeover -> pause gracefully
            callPaused = true;
            cancelWatchdog();
            if (recognizer != null) {
                try { recognizer.cancel(); } catch (Throwable ignored) {}
            }
            transitionTo(State.COOLDOWN);
            updateNotification("Microphone paused for call/audio");
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (callPaused) {
                callPaused = false;
                scheduleListen(500);
            }
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(this::onAudioFocusChanged);
        }
    }

    // ------------------------------------------------------------- Notifications & Receivers

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SayDo listening", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

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

        return new Notification.Builder(this, CHANNEL_ID)
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
        } catch (Throwable ignored) {
        }
    }

    private void registerScreenReceiver() {
        if (screenReceiver != null) return;
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    final Macro q = queuedMacro;
                    boolean valid = q != null && System.currentTimeMillis() < queuedMacroExpiry;
                    queuedMacro = null;
                    if (valid && !destroyed) {
                        h.postDelayed(() -> executeMacro(q), 500);
                    }
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    acquireWakeLock();
                    if (running && state != State.EXECUTING_MACRO && !destroyed) {
                        scheduleListen(200);
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    acquireWakeLock();
                    if (running && state != State.EXECUTING_MACRO && !destroyed) {
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
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, f);
            }
        } catch (Throwable t) {
            screenReceiver = null;
        }
    }

    private void unregisterScreenReceiver() {
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
            screenReceiver = null;
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SayDo:VoiceListenLock");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private void toast(final String msg) {
        h.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    private static String errorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NETWORK: return "NETWORK";
            case SpeechRecognizer.ERROR_AUDIO: return "AUDIO";
            case SpeechRecognizer.ERROR_SERVER: return "SERVER";
            case SpeechRecognizer.ERROR_CLIENT: return "CLIENT";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "SPEECH_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH: return "NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "BUSY";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "NO_PERMISSION";
            case 12: return "LANG_NOT_SUPPORTED";
            case 13: return "LANG_UNAVAILABLE";
            default: return "CODE_" + error;
        }
    }
}
