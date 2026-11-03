package uz.saydo.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Toza bosh ekran: sarlavha, shortcutlar ro'yxati va bitta katta START/STOP tugmasi.
 * START bosilganda to'liq sozlanmagan bo'lsa kerakli sozlamaga o'tkazadi.
 * Hammasi internetsiz (offline) ishlaydi.
 */
public class MainActivity extends AppCompatActivity implements MacroAdapter.Listener {

    static volatile MainActivity current;
    static final String EXTRA_ASK_MIC = "ask_mic";

    private static final int REQ_AUDIO = 101;
    private static final int REQ_NOTIF = 102;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;
    private boolean askedNotif = false;

    /** START bosilgan va sozlash zanjiri davom etmoqda. */
    private boolean pendingStart = false;
    private int lastStep = -1;

    private RecyclerView list;
    private View empty;
    private TextView hint, btnStart, btnVoiceSettings;
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
        btnStart = findViewById(R.id.btnStart);
        btnVoiceSettings = findViewById(R.id.btnVoiceSettings);

        adapter = new MacroAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setHasFixedSize(true);

        btnStart.setOnClickListener(v -> onStartClicked());
        btnVoiceSettings.setOnClickListener(v -> Setup.openVoiceSettings(this));

        reloadMacros();
        refreshUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_ASK_MIC, false)) {
            i.removeExtra(EXTRA_ASK_MIC);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestMic();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        current = this;
    }

    @Override
    protected void onResume() {
        super.onResume();
        onResume2();
        reloadMacros();
        refreshUi();
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 1200);
    }

    private void onResume2() {
        if (destroyed) return;
        if (pendingStart) {
            continueStart();
            return;
        }
        if (!Store.isEnabled(this)) return;
        if (Setup.missing(this) != Setup.OK) {
            // Ruxsat qaytarib olingan: qaytadan START bilan sozlanadi
            Store.setEnabled(this, false);
            FloatService.hide(this);
            stopListening();
            return;
        }
        FloatService.show(this);
        if (!VoiceService.running) startListeningFlow();
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
        boolean enabled = Store.isEnabled(this);
        boolean listening = VoiceService.running;
        btnStart.setText(enabled ? "STOP" : "START");
        btnStart.setBackgroundResource(enabled ? R.drawable.bg_btn_stop : R.drawable.bg_btn_start);

        if (enabled && listening) {
            hint.setText("Listening · English");
            hint.setTextColor(ContextCompat.getColor(this, R.color.success));
        } else if (enabled) {
            hint.setText("Starting…");
            hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        } else {
            hint.setText("Tap START to begin");
            hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    // ------------------------------------------------------------------ start / stop

    private void onStartClicked() {
        if (Store.isEnabled(this)) {
            applyMaster(false);
            return;
        }
        pendingStart = true;
        lastStep = -1;
        continueStart();
    }

    /** Sozlash zanjiri: yetishmayotgan birinchi qadamni ochadi; hammasi tayyor bo'lsa ishga tushiradi. */
    private void continueStart() {
        if (!pendingStart || destroyed) return;
        int step = Setup.missing(this);
        if (step == Setup.OK) {
            pendingStart = false;
            lastStep = -1;
            applyMaster(true);
            return;
        }
        if (step == lastStep) {
            // Foydalanuvchi shu qadamni bajarmasdan qaytdi
            pendingStart = false;
            lastStep = -1;
            toast(stepName(step) + " is required");
            refreshUi();
            return;
        }
        lastStep = step;
        if (step == Setup.NEED_MIC) requestMic();
        else Setup.route(this, step);
    }

    private String stepName(int step) {
        switch (step) {
            case Setup.NEED_OVERLAY: return "Display over other apps";
            case Setup.NEED_ACCESSIBILITY: return "Accessibility";
            case Setup.NEED_MIC: return "Microphone";
            default: return "Setup";
        }
    }

    private void applyMaster(boolean on) {
        if (destroyed) return;
        Store.setEnabled(this, on);
        if (on) {
            FloatService.show(this);
            startListeningFlow();
            maybeRequestBatteryOpt();
        } else {
            FloatService.hide(this);
            stopListening();
        }
        refreshUi();
    }

    private void maybeRequestBatteryOpt() {
        if (Build.VERSION.SDK_INT < 23 || Store.batteryAsked(this)) return;
        try {
            String pkg = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                Store.setBatteryAsked(this);
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg)));
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ voice

    private void requestMic() {
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    private void startListeningFlow() {
        if (destroyed || !Store.isEnabled(this)) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic();
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
            toast("Could not start listening");
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
            if (pendingStart) {
                continueStart();
            } else if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startListeningFlow();
            } else {
                toast("Microphone permission required");
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
            if (r == Runner.NO_ACCESSIBILITY) Setup.route(this, Setup.NEED_ACCESSIBILITY);
            else if (r == Runner.INVALID) toast("Invalid shortcut");
            else if (r == Runner.LOCKED) toast("Unlock your phone first");
        } catch (Exception e) {
            toast("Could not run");
        }
    }

    @Override
    public void onDelete(int index) {
        if (adapter.getArmedDelete() != index) {
            adapter.armDelete(index);
            toast("Tap again to delete");
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
            toast("Deleted");
        } catch (Exception e) {
            toast("Could not delete");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void toast(String m) {
        if (destroyed || m == null) return;
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
