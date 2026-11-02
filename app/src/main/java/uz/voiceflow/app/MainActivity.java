package uz.voiceflow.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * To'liq native UI — WebView/HTML yo'q.
 */
public class MainActivity extends AppCompatActivity implements MacroAdapter.Listener {

    static volatile MainActivity current;

    private static final int REQ_AUDIO = 101;
    private static final int REQ_NOTIF = 102;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;
    private boolean askedNotif = false;
    private boolean suppressSwitch = false;

    private RecyclerView list;
    private View empty;
    private TextView hint, chipAcc, chipMic, chipLang, masterLabel;
    private SwitchMaterial masterSwitch;
    private MacroAdapter adapter;
    private JSONArray macros = new JSONArray();

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            refreshUi();
            ui.postDelayed(this, 1200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        hint = findViewById(R.id.hint);
        chipAcc = findViewById(R.id.chipAcc);
        chipMic = findViewById(R.id.chipMic);
        chipLang = findViewById(R.id.chipLang);
        masterLabel = findViewById(R.id.masterLabel);
        masterSwitch = findViewById(R.id.masterSwitch);
        ImageButton btnSettings = findViewById(R.id.btnSettings);

        adapter = new MacroAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setHasFixedSize(true);

        masterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressSwitch || destroyed) return;
            applyMaster(isChecked);
        });

        chipAcc.setOnClickListener(v -> {
            if (!isAccessibilityEnabled()) {
                toast("VoiceFlow ni Accessibility da yoqing");
                openAccessibility();
            } else {
                toast("Accessibility yoqilgan");
            }
        });
        chipAcc.setOnLongClickListener(v -> {
            openAppInfo();
            return true;
        });

        chipMic.setOnClickListener(v -> {
            if (!Store.isEnabled(this)) {
                toast("Avval ON qiling");
                return;
            }
            if (VoiceService.running) stopListening();
            else startListeningFlow();
        });

        chipLang.setOnClickListener(v -> {
            String l = Store.nextLang(this);
            toast("Til: " + l);
            refreshUi();
        });

        btnSettings.setOnClickListener(v -> openAppInfo());

        reloadMacros();
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        current = this;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Store.isEnabled(this) && FloatService.canOverlay(this)) {
            FloatService.show(this);
            if (!VoiceService.running
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startListeningFlow();
            }
        }
        reloadMacros();
        refreshUi();
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 1200);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (current == this) current = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------ data

    private void reloadMacros() {
        try {
            macros = new JSONArray(Store.getMacros(this));
        } catch (Exception e) {
            macros = new JSONArray();
        }
        adapter.setData(macros);
        boolean has = macros.length() > 0;
        list.setVisibility(has ? View.VISIBLE : View.GONE);
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
    }

    private void refreshUi() {
        if (destroyed) return;
        boolean acc = isAccessibilityEnabled();
        boolean listening = VoiceService.running;
        boolean enabled = Store.isEnabled(this);
        String lang = Store.getLang(this);
        String langShort = lang.length() >= 2 ? lang.substring(0, 2).toUpperCase() : lang;

        styleChip(chipAcc, acc, acc ? "Accessibility" : "Accessibility", !acc);
        styleChip(chipMic, listening, listening ? "Tinglanmoqda" : "Mikrofon", false);
        chipLang.setText(langShort);

        if (!acc) {
            hint.setText("Harakatlar uchun Accessibility yoqing");
            hint.setTextColor(ContextCompat.getColor(this, R.color.danger));
        } else if (listening) {
            hint.setText("Tinglanmoqda · " + langShort);
            hint.setTextColor(ContextCompat.getColor(this, R.color.success));
        } else if (enabled) {
            hint.setText("ON · float START bilan yozing");
            hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        } else {
            hint.setText("OFF · ishlatish uchun ON qiling");
            hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }

        suppressSwitch = true;
        masterSwitch.setChecked(enabled);
        masterLabel.setText(enabled ? "ON" : "OFF");
        suppressSwitch = false;
    }

    private void styleChip(TextView chip, boolean on, String text, boolean bad) {
        chip.setText(text);
        if (bad) {
            chip.setBackgroundResource(R.drawable.bg_chip_bad);
            chip.setTextColor(0xFFFFFFFF);
        } else if (on) {
            chip.setBackgroundResource(R.drawable.bg_chip_on);
            chip.setTextColor(0xFFFFFFFF);
        } else {
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    // ------------------------------------------------------------------ master

    private void applyMaster(boolean on) {
        if (destroyed) return;
        Store.setEnabled(this, on);
        if (on) {
            if (!FloatService.canOverlay(this)) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    toast("Boshqa ilovalar ustida ko'rsatish ruxsatini yoqing");
                } catch (Exception e) {
                    toast("Sozlamalarni ochib bo'lmadi");
                }
            } else {
                FloatService.show(this);
            }
            startListeningFlow();
            maybeRequestBatteryOpt();
        } else {
            FloatService.hide(this);
            stopListening();
        }
        refreshUi();
    }

    private void maybeRequestBatteryOpt() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            String pkg = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg)));
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ voice

    private void startListeningFlow() {
        if (destroyed || !Store.isEnabled(this)) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        try {
            Intent i = new Intent(this, VoiceService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            toast("Tinglashni boshlab bo'lmadi");
        }
        refreshUi();
    }

    private void stopListening() {
        try { stopService(new Intent(this, VoiceService.class)); } catch (Exception ignored) { }
        VoiceService.running = false;
        refreshUi();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (destroyed) return;
        if (code == REQ_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startListeningFlow();
            } else {
                toast("Mikrofon ruxsati kerak");
            }
        } else if (code == REQ_NOTIF) {
            startListeningFlow();
        }
    }

    // ------------------------------------------------------------------ list actions

    @Override
    public void onPlay(int index) {
        try {
            JSONObject m = macros.optJSONObject(index);
            int r = Runner.run(this, m, null);
            if (r == Runner.NO_ACCESSIBILITY) toast("Avval Accessibility ni yoqing");
            else if (r == Runner.INVALID) toast("Makro yaroqsiz");
        } catch (Exception e) {
            toast("Ishga tushmadi");
        }
    }

    @Override
    public void onDelete(int index) {
        if (adapter.getArmedDelete() != index) {
            adapter.armDelete(index);
            toast("O'chirish uchun yana bosing");
            ui.postDelayed(() -> {
                if (!destroyed && adapter.getArmedDelete() == index) adapter.armDelete(-1);
            }, 2200);
            return;
        }
        try {
            JSONArray next = new JSONArray();
            for (int i = 0; i < macros.length(); i++) {
                if (i != index) next.put(macros.get(i));
            }
            Store.saveMacros(this, next.toString());
            reloadMacros();
            toast("O'chirildi");
        } catch (Exception e) {
            toast("O'chirib bo'lmadi");
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean isAccessibilityEnabled() {
        try {
            if (GestureService.instance != null) return true;
            String enabled = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName me = new ComponentName(this, GestureService.class);
            for (String part : enabled.split(":")) {
                ComponentName c = ComponentName.unflattenFromString(part);
                if (me.equals(c)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            toast("Sozlamalarni ochib bo'lmadi");
        }
    }

    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("Sozlamalarni ochib bo'lmadi");
        }
    }

    private void toast(String m) {
        if (destroyed || m == null) return;
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
