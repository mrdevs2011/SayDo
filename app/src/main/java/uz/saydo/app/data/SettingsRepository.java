package uz.saydo.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, non-blocking settings repository using SharedPreferences.apply().
 * Notifies observers when setting values change.
 */
public final class SettingsRepository {
    private static final String PREFS_NAME = "saydo_settings";
    private static final String LEGACY_PREFS_NAME = "saydo";

    public static final String KEY_MASTER_ON = "master_on";
    public static final String KEY_BATTERY_ASKED = "batt_asked";
    public static final String KEY_VOICE_GUIDED = "voice_guided";
    public static final String KEY_LANG = "lang";
    public static final String DEFAULT_LANG = "en-US";

    /** Voice gate: start speech recognition only after a sound louder than the threshold. */
    public static final String KEY_GATE_ON = "gate_on";
    /** Gate threshold in dBFS (per-device, set by calibration or slider). */
    public static final String KEY_GATE_DB = "gate_db";
    public static final String KEY_GATE_NOISE_DB = "gate_noise_db";
    public static final String KEY_GATE_SPEECH_DB = "gate_speech_db";
    public static final float DEFAULT_GATE_DB = -28f;
    /** Set when this phone's recognizer did not accept our audio stream (Android 13+ mode). */
    public static final String KEY_GATE_STREAM_BAD = "gate_stream_bad";
    /** Set once a stream-mode session heard speech on this phone (stream mode proven to work). */
    public static final String KEY_GATE_STREAM_OK = "gate_stream_ok";

    private static volatile SettingsRepository sInstance;
    private final SharedPreferences prefs;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public interface Listener {
        void onSettingChanged(String key);
    }

    private SettingsRepository(Context context) {
        Context app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        migrateLegacy(app);
    }

    public static SettingsRepository getInstance(Context context) {
        if (sInstance == null) {
            synchronized (SettingsRepository.class) {
                if (sInstance == null) {
                    sInstance = new SettingsRepository(context);
                }
            }
        }
        return sInstance;
    }

    private void migrateLegacy(Context app) {
        try {
            SharedPreferences legacy = app.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE);
            if (legacy.contains(KEY_MASTER_ON) && !prefs.contains(KEY_MASTER_ON)) {
                boolean master = legacy.getBoolean(KEY_MASTER_ON, false);
                boolean batt = legacy.getBoolean(KEY_BATTERY_ASKED, false);
                boolean voice = legacy.getBoolean(KEY_VOICE_GUIDED, false);

                prefs.edit()
                        .putBoolean(KEY_MASTER_ON, master)
                        .putBoolean(KEY_BATTERY_ASKED, batt)
                        .putBoolean(KEY_VOICE_GUIDED, voice)
                        .apply();
            }
        } catch (Throwable ignored) {
        }
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyChanged(String key) {
        for (Listener l : listeners) {
            try {
                l.onSettingChanged(key);
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isMasterOn() {
        return prefs.getBoolean(KEY_MASTER_ON, false);
    }

    public void setMasterOn(boolean on) {
        if (isMasterOn() != on) {
            prefs.edit().putBoolean(KEY_MASTER_ON, on).apply();
            notifyChanged(KEY_MASTER_ON);
        }
    }

    public boolean isBatteryAsked() {
        return prefs.getBoolean(KEY_BATTERY_ASKED, false);
    }

    public void setBatteryAsked(boolean asked) {
        if (isBatteryAsked() != asked) {
            prefs.edit().putBoolean(KEY_BATTERY_ASKED, asked).apply();
            notifyChanged(KEY_BATTERY_ASKED);
        }
    }

    public boolean isVoiceGuided() {
        return prefs.getBoolean(KEY_VOICE_GUIDED, false);
    }

    public void setVoiceGuided(boolean guided) {
        if (isVoiceGuided() != guided) {
            prefs.edit().putBoolean(KEY_VOICE_GUIDED, guided).apply();
            notifyChanged(KEY_VOICE_GUIDED);
        }
    }

    public String getLanguage() {
        return DEFAULT_LANG;
    }

    // ------------------------------------------------------------------ voice gate

    public boolean isGateOn() {
        return prefs.getBoolean(KEY_GATE_ON, false);
    }

    public void setGateOn(boolean on) {
        if (isGateOn() != on) {
            prefs.edit().putBoolean(KEY_GATE_ON, on).apply();
            notifyChanged(KEY_GATE_ON);
        }
    }

    public float getGateDb() {
        return prefs.getFloat(KEY_GATE_DB, DEFAULT_GATE_DB);
    }

    public void setGateDb(float db) {
        if (Float.compare(getGateDb(), db) != 0) {
            prefs.edit().putFloat(KEY_GATE_DB, db).apply();
            notifyChanged(KEY_GATE_DB);
        }
    }

    /** NaN when not calibrated. */
    public float getGateNoiseDb() {
        return prefs.getFloat(KEY_GATE_NOISE_DB, Float.NaN);
    }

    /** NaN when not calibrated. */
    public float getGateSpeechDb() {
        return prefs.getFloat(KEY_GATE_SPEECH_DB, Float.NaN);
    }

    public boolean isGateStreamBad() {
        return prefs.getBoolean(KEY_GATE_STREAM_BAD, false);
    }

    public void setGateStreamBad(boolean bad) {
        if (isGateStreamBad() != bad) {
            prefs.edit().putBoolean(KEY_GATE_STREAM_BAD, bad).apply();
            notifyChanged(KEY_GATE_STREAM_BAD);
        }
    }

    public boolean isGateStreamOk() {
        return prefs.getBoolean(KEY_GATE_STREAM_OK, false);
    }

    public void setGateStreamOk() {
        if (!isGateStreamOk()) prefs.edit().putBoolean(KEY_GATE_STREAM_OK, true).apply();
    }

    public void setGateCalibration(float noiseDb, float speechDb) {
        prefs.edit()
                .putFloat(KEY_GATE_NOISE_DB, noiseDb)
                .putFloat(KEY_GATE_SPEECH_DB, speechDb)
                .apply();
        notifyChanged(KEY_GATE_NOISE_DB);
    }
}
