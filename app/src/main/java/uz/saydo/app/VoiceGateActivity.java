package uz.saydo.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

import uz.saydo.app.audio.AudioLevel;
import uz.saydo.app.audio.VoiceGate;
import uz.saydo.app.data.SettingsRepository;

/**
 * Voice gate settings: on/off, live level meter, one-tap calibration and threshold slider.
 *
 * While this screen is open, background listening is paused so the meter can use the mic,
 * and the meter runs the exact same VoiceGate code the service uses ("open" lamp = what the
 * service would do).
 */
public class VoiceGateActivity extends Activity {

    private static final float METER_MIN_DB = -70f;
    private static final float METER_MAX_DB = 0f;
    private static final long NOISE_MS = 3000;
    private static final long SPEECH_MS = 4000;
    private static final int MAX_SAMPLES = 400;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SettingsRepository settings;
    private float d;

    private Switch enable;
    private MeterView meter;
    private TextView levelText, lamp, calibText, calibStatus, modeText;
    private SeekBar seek;
    private Button calibrate, retryStream;

    private VoiceGate meterGate;
    private boolean micOk;

    // Calibration
    private enum Phase { NONE, NOISE, SPEECH }
    private Phase phase = Phase.NONE;
    private final double[] samples = new double[MAX_SAMPLES];
    private int sampleCount;
    private double noiseResult = Double.NaN;

    private final Runnable lampOff = () -> {
        setLamp(false);
        if (meterGate != null && phase == Phase.NONE) meterGate.arm(settings.getGateDb());
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = SettingsRepository.getInstance(this);
        d = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B1220"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root);

        root.addView(text("Ovoz darvozasi", 24, true, "#FFFFFF"));
        root.addView(text("Yoqilsa, nutqni tanish doimo ishlamaydi. Mikrofon faqat tovush balandligini "
                + "o'lchaydi va ovoz chegaradan baland bo'lgandagina trigger tinglanadi. "
                + "Bu faqat asosiy ekranda tinglash ON bo'lganda ishlaydi.", 13, false, "#94A3B8"));

        // --- Enable
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(8));
        TextView lbl = text("Ovoz darvozasini yoqish", 16, true, "#E2E8F0");
        row.addView(lbl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        enable = new Switch(this);
        row.addView(enable);
        root.addView(row);

        // --- Meter
        root.addView(header("Hozirgi tovush"));
        meter = new MeterView(this);
        root.addView(meter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        levelText = text("", 14, false, "#E2E8F0");
        levelText.setTypeface(Typeface.MONOSPACE);
        root.addView(levelText);
        lamp = text("", 16, true, "#64748B");
        root.addView(lamp);

        // --- Threshold
        root.addView(header("Chegara"));
        root.addView(text("Sariq chiziqdan baland ovoz darvozani ochadi. Oddiy gapirganda ochilmasligi, "
                + "biroz baland aytganda ochilishi kerak.", 12, false, "#94A3B8"));
        seek = new SeekBar(this);
        seek.setMax(Math.round(AudioLevel.MAX_THRESHOLD_DB - AudioLevel.MIN_THRESHOLD_DB));
        root.addView(seek);

        // --- Calibration
        root.addView(header("Kalibrlash"));
        root.addView(text("1) 3 soniya jim turing.  2) 4 soniya triggeringizni odatdagi ovozda 2–3 marta "
                + "ayting. Chegara oddiy ovozingizdan biroz balandroq qilib o'rnatiladi.",
                12, false, "#94A3B8"));
        calibrate = button("Kalibrlashni boshlash", "#3B82F6");
        root.addView(calibrate);
        calibStatus = text("", 18, true, "#FACC15");
        calibStatus.setGravity(Gravity.CENTER);
        root.addView(calibStatus);
        calibText = text("", 13, false, "#E2E8F0");
        root.addView(calibText);

        // --- Mode
        root.addView(header("Rejim"));
        modeText = text("", 13, false, "#E2E8F0");
        root.addView(modeText);
        retryStream = button("Oqim rejimini qayta sinash", "#64748B");
        root.addView(retryStream);

        setContentView(scroll);

        enable.setChecked(settings.isGateOn());
        enable.setOnCheckedChangeListener((b, on) -> settings.setGateOn(on));
        seek.setProgress(toProgress(settings.getGateDb()));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                float db = fromProgress(p);
                settings.setGateDb(db);
                if (meterGate != null) meterGate.setThreshold(db);
                renderStatic();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        calibrate.setOnClickListener(v -> {
            if (phase == Phase.NONE) startCalibration();
            else stopCalibration(null);
        });
        retryStream.setOnClickListener(v -> {
            settings.setGateStreamBad(false);
            renderStatic();
        });
        renderStatic();
    }

    @Override
    protected void onStart() {
        super.onStart();
        micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!micOk) {
            levelText.setText("Mikrofon ruxsati yo'q. Asosiy ekranda START orqali ruxsat bering.");
            calibrate.setEnabled(false);
            return;
        }
        VoiceService.setPausedForSettings(true);
        // Give the service a moment to release the mic before we open it.
        h.postDelayed(this::openMeter, 350);
    }

    @Override
    protected void onStop() {
        h.removeCallbacksAndMessages(null);
        if (phase != Phase.NONE) stopCalibration(null);
        closeMeter();
        if (micOk) VoiceService.setPausedForSettings(false);
        super.onStop();
    }

    // ------------------------------------------------------------------ meter

    private void openMeter() {
        if (meterGate != null || isFinishing()) return;
        VoiceGate g = new VoiceGate();
        g.setLevelListener(db -> h.post(() -> onLevel(db)));
        g.setCallback(new VoiceGate.Callback() {
            @Override public void onGateOpen(double db) {
                h.post(() -> {
                    setLamp(true);
                    h.removeCallbacks(lampOff);
                    h.postDelayed(lampOff, 900);
                });
            }
            @Override public void onGateLost() {
                h.post(() -> levelText.setText("Mikrofonni boshqa ilova oldi."));
            }
        });
        if (!g.start()) {
            levelText.setText("Mikrofon band (qo'ng'iroq yoki boshqa ilova). Keyinroq urinib ko'ring.");
            calibrate.setEnabled(false);
            return;
        }
        calibrate.setEnabled(true);
        g.arm(settings.getGateDb());
        meterGate = g;
        setLamp(false);
    }

    private void closeMeter() {
        VoiceGate g = meterGate;
        meterGate = null;
        if (g != null) {
            g.setCallback(null);
            g.setLevelListener(null);
            g.stop();
        }
    }

    private void onLevel(double db) {
        if (meterGate == null) return;
        meter.setLevel((float) db);
        levelText.setText(String.format(Locale.US, "Hozir: %5.1f dB   Chegara: %5.1f dB",
                db, settings.getGateDb()));
        if (phase != Phase.NONE && sampleCount < MAX_SAMPLES) samples[sampleCount++] = db;
    }

    private void setLamp(boolean open) {
        if (open) {
            lamp.setText("● Darvoza OCHILDI — shu ovozda trigger tinglanadi");
            lamp.setTextColor(Color.parseColor("#22C55E"));
        } else {
            lamp.setText("○ Darvoza yopiq — baland ovozda gapirib sinab ko'ring");
            lamp.setTextColor(Color.parseColor("#64748B"));
        }
    }

    // ------------------------------------------------------------------ calibration

    private void startCalibration() {
        if (meterGate == null) return;
        meterGate.disarm();
        h.removeCallbacks(lampOff);
        setLamp(false);
        phase = Phase.NOISE;
        sampleCount = 0;
        noiseResult = Double.NaN;
        calibrate.setText("To'xtatish");
        calibStatus.setText("1/2  Jim turing…");
        h.postDelayed(this::speechPhase, NOISE_MS);
    }

    private void speechPhase() {
        if (phase != Phase.NOISE) return;
        noiseResult = AudioLevel.percentile(samples, sampleCount, 50);
        phase = Phase.SPEECH;
        sampleCount = 0;
        calibStatus.setText("2/2  Triggeringizni odatdagidek ayting…");
        h.postDelayed(this::finishCalibration, SPEECH_MS);
    }

    private void finishCalibration() {
        if (phase != Phase.SPEECH) return;
        double noise = noiseResult;
        // Only frames clearly above the room noise are speech.
        double floor = Double.isNaN(noise) ? -60 : noise + 6;
        double[] voiced = new double[sampleCount];
        int n = 0;
        for (int i = 0; i < sampleCount; i++) if (samples[i] > floor) voiced[n++] = samples[i];
        if (n < 5) {
            stopCalibration("Ovoz eshitilmadi. Yaqinroq turib, qayta urinib ko'ring.");
            return;
        }
        double speech = AudioLevel.percentile(voiced, n, 90);
        float threshold = AudioLevel.suggestThreshold(noise, speech, AudioLevel.DEFAULT_OFFSET_DB);
        settings.setGateCalibration((float) noise, (float) speech);
        settings.setGateDb(threshold);
        if (meterGate != null) meterGate.setThreshold(threshold);
        seek.setProgress(toProgress(threshold));
        String warn = (speech - noise) < 12
                ? "\nDiqqat: xona shovqinli, ovoz shovqindan unchalik baland emas. Darvoza ba'zan "
                  + "o'z-o'zidan ochilishi yoki ochilmasligi mumkin."
                : "";
        stopCalibration(String.format(Locale.US, "Tayyor. Chegara %.0f dB.%s", threshold, warn));
    }

    private void stopCalibration(String message) {
        h.removeCallbacksAndMessages(null);
        phase = Phase.NONE;
        calibrate.setText("Kalibrlashni boshlash");
        calibStatus.setText(message == null ? "" : message);
        if (meterGate != null) meterGate.arm(settings.getGateDb());
        renderStatic();
    }

    // ------------------------------------------------------------------ render

    private void renderStatic() {
        float thr = settings.getGateDb();
        float noise = settings.getGateNoiseDb();
        float speech = settings.getGateSpeechDb();
        meter.setMarks(thr, noise, speech);
        if (Float.isNaN(speech)) {
            calibText.setText("Hali kalibrlanmagan. Hozirgi chegara: " + Math.round(thr) + " dB.");
        } else {
            calibText.setText(String.format(Locale.US,
                    "Xona shovqini: %.0f dB   Oddiy nutqingiz: %.0f dB   Chegara: %.0f dB",
                    noise, speech, thr));
        }

        boolean sdkStream = Build.VERSION.SDK_INT >= 33;
        boolean bad = settings.isGateStreamBad();
        if (sdkStream && !bad) {
            modeText.setText("Oqim (Android 13+): oxirgi 1.2 soniya ovoz xotirada saqlanadi, shuning uchun "
                    + "triggerning boshi yo'qolmaydi. Agar telefoningiz buni qo'llamasa, SayDo o'zi oddiy "
                    + "rejimga o'tadi.");
        } else if (sdkStream) {
            modeText.setText("Oddiy rejim: bu telefonning nutq tanuvchisi oqim rejimini qo'llamadi. "
                    + "Baland ovozdan keyin tinglash boshlanadi, triggerning boshi yo'qolishi mumkin. "
                    + "Tanilmasa, darhol yana bir marta ayting.");
        } else {
            modeText.setText("Oddiy rejim (Android 12 va past): baland ovozdan keyin tinglash boshlanadi, "
                    + "triggerning boshi yo'qolishi mumkin. Tanilmasa, darhol yana bir marta ayting.");
        }
        retryStream.setVisibility(sdkStream && bad ? View.VISIBLE : View.GONE);
    }

    private static int toProgress(float db) {
        return Math.round(AudioLevel.clampThreshold(db) - AudioLevel.MIN_THRESHOLD_DB);
    }

    private static float fromProgress(int p) {
        return AudioLevel.MIN_THRESHOLD_DB + p;
    }

    // ------------------------------------------------------------------ ui helpers

    private int dp(int v) {
        return Math.round(v * d);
    }

    private TextView text(String s, int sp, boolean bold, String color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 17, true, "#FFFFFF");
        t.setPadding(0, dp(22), 0, dp(4));
        return t;
    }

    private Button button(String s, String color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(color));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(8), 0, dp(8));
        b.setLayoutParams(p);
        return b;
    }

    /** Horizontal level bar with threshold (yellow), noise and speech marks. */
    private static final class MeterView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thr = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float level = METER_MIN_DB;
        private float threshold = AudioLevel.DEFAULT_THRESHOLD_DB;
        private float noise = Float.NaN, speech = Float.NaN;

        MeterView(Context c) {
            super(c);
            float dd = c.getResources().getDisplayMetrics().density;
            bg.setColor(Color.parseColor("#1E293B"));
            thr.setColor(Color.parseColor("#FACC15"));
            thr.setStrokeWidth(3 * dd);
            mark.setColor(Color.parseColor("#94A3B8"));
            mark.setStrokeWidth(2 * dd);
        }

        void setLevel(float db) {
            level = db;
            invalidate();
        }

        void setMarks(float threshold, float noise, float speech) {
            this.threshold = threshold;
            this.noise = noise;
            this.speech = speech;
            invalidate();
        }

        private float x(float db) {
            float t = (db - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB);
            t = Math.max(0f, Math.min(1f, t));
            return getPaddingLeft() + t * (getWidth() - getPaddingLeft() - getPaddingRight());
        }

        @Override
        protected void onDraw(Canvas c) {
            float hgt = getHeight();
            float rad = hgt / 4f;
            r.set(0, 0, getWidth(), hgt);
            c.drawRoundRect(r, rad, rad, bg);
            fill.setColor(level >= threshold ? Color.parseColor("#22C55E") : Color.parseColor("#3B82F6"));
            r.set(0, 0, x(level), hgt);
            c.drawRoundRect(r, rad, rad, fill);
            if (!Float.isNaN(noise)) c.drawLine(x(noise), hgt * 0.6f, x(noise), hgt, mark);
            if (!Float.isNaN(speech)) c.drawLine(x(speech), hgt * 0.6f, x(speech), hgt, mark);
            c.drawLine(x(threshold), 0, x(threshold), hgt, thr);
        }
    }
}
