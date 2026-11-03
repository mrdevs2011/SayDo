package uz.saydo.app.kernel.voice;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * On-Device Conversational Audio Co-Pilot & TTS Voice Feedback Engine.
 * Provides spoken confirmations, disambiguation questions, and real-time step guidance.
 */
public final class VoiceFeedbackEngine implements TextToSpeech.OnInitListener {

    public interface TtsCompletionCallback {
        void onCompleted();
    }

    private final Context context;
    private TextToSpeech tts;
    private boolean isInitialized = false;

    public VoiceFeedbackEngine(Context context) {
        this.context = (context != null) ? context.getApplicationContext() : null;
        if (this.context != null) {
            try {
                this.tts = new TextToSpeech(this.context, this);
            } catch (Throwable t) {
                android.util.Log.w("VoiceFeedbackEngine", "TTS initialization error: " + t.getMessage());
            }
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            isInitialized = true;
            try {
                tts.setLanguage(Locale.US);
                tts.setPitch(1.05f);
                tts.setSpeechRate(1.0f);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Speaks the feedback message asynchronously to the user.
     */
    public synchronized void speak(String message) {
        speak(message, null);
    }

    public synchronized void speak(String message, TtsCompletionCallback callback) {
        if (message == null || message.trim().isEmpty() || !isInitialized || tts == null) {
            if (callback != null) callback.onCompleted();
            return;
        }

        String utteranceId = "saydo_tts_" + System.currentTimeMillis();
        if (callback != null) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String s) {
                }

                @Override
                public void onDone(String s) {
                    if (utteranceId.equals(s)) {
                        callback.onCompleted();
                    }
                }

                @Override
                public void onError(String s) {
                    if (utteranceId.equals(s)) {
                        callback.onCompleted();
                    }
                }
            });
        }

        try {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        } catch (Throwable t) {
            android.util.Log.e("VoiceFeedbackEngine", "TTS speak failed", t);
            if (callback != null) callback.onCompleted();
        }
    }

    public synchronized void stop() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void shutdown() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
            isInitialized = false;
        }
    }
}
