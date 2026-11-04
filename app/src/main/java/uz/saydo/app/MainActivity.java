package uz.saydo.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.data.SettingsRepository;

/**
 * Production-hardened, zero-polling reactive Main Activity.
 * Subscribes directly to MacroRepository and SettingsRepository events.
 * Eliminates polling runnables, static context leaks, and index race conditions.
 */
public class MainActivity extends AppCompatActivity implements
        MacroAdapter.Listener,
        MacroRepository.OnMacrosChangedListener,
        SettingsRepository.Listener {

    static final String EXTRA_ASK_MIC = "ask_mic";
    static final String EXTRA_ASK_NOTIF = "ask_notif";

    private static final int REQ_AUDIO = 101;
    private static final int REQ_NOTIF = 102;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

    /** Active step in Auto Start permission chain */
    private boolean pendingStart = false;
    private int lastStep = -1;

    private RecyclerView list;
    private View empty;
    private TextView hint, btnStart;
    private MacroAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        setContentView(R.layout.activity_main);

        // Window Insets Handling for modern edge-to-edge displays
        View root = findViewById(R.id.root);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        hint = findViewById(R.id.hint);
        btnStart = findViewById(R.id.btnStart);

        adapter = new MacroAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setHasFixedSize(true);

        btnStart.setOnClickListener(v -> onStartClicked());

        // Reactive subscriptions (Push updates instead of pull/polling)
        MacroRepository.getInstance(this).addListener(this);
        SettingsRepository.getInstance(this).addListener(this);

        updateMacroList(MacroRepository.getInstance(this).getAll());
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
        if (i == null) return;
        if (i.getBooleanExtra(EXTRA_ASK_MIC, false)) {
            i.removeExtra(EXTRA_ASK_MIC);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestMic();
            }
        }
        if (i.getBooleanExtra(EXTRA_ASK_NOTIF, false)) {
            i.removeExtra(EXTRA_ASK_NOTIF);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotif();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (destroyed) return;
        if (pendingStart) {
            continueStart();
            return;
        }
        if (!SettingsRepository.getInstance(this).isMasterOn()) return;
        if (Setup.missing(this) != Setup.OK) {
            SettingsRepository.getInstance(this).setMasterOn(false);
            FloatService.hide(this);
            stopListening();
            return;
        }
        FloatService.show(this);
        if (!VoiceService.running) startListeningFlow();
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        MacroRepository.getInstance(this).removeListener(this);
        SettingsRepository.getInstance(this).removeListener(this);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------------ Reactive Callbacks

    @Override
    public void onMacrosChanged(List<Macro> macros) {
        if (destroyed) return;
        updateMacroList(macros);
    }

    @Override
    public void onSettingChanged(String key) {
        if (destroyed) return;
        ui.post(this::refreshUi);
    }

    private void updateMacroList(List<Macro> macros) {
        if (destroyed || macros == null) return;
        adapter.setData(macros);
        boolean hasItems = !macros.isEmpty();
        list.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        empty.setVisibility(hasItems ? View.GONE : View.VISIBLE);
        uz.saydo.app.kernel.ui.AppShortcutManager.updateShortcuts(this);
    }

    public void refreshUi() {
        if (destroyed) return;
        boolean enabled = SettingsRepository.getInstance(this).isMasterOn();
        boolean listening = VoiceService.running;
        btnStart.setText(enabled ? "STOP" : "START");
        btnStart.setBackgroundResource(enabled ? R.drawable.bg_btn_stop : R.drawable.bg_btn_start);

        if (enabled && listening) {
            hint.setText("Listening · English (offline)");
            hint.setTextColor(ContextCompat.getColor(this, R.color.success));
        } else if (enabled) {
            hint.setText("Starting…");
            hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        } else if (pendingStart) {
            hint.setText("Setting up permissions…");
            hint.setTextColor(ContextCompat.getColor(this, R.color.accent));
        } else {
            hint.setText("Tap START to begin");
            hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    // ------------------------------------------------------------------ Auto Start Chain

    private void onStartClicked() {
        if (SettingsRepository.getInstance(this).isMasterOn()) {
            applyMaster(false);
            return;
        }
        pendingStart = true;
        lastStep = -1;
        continueStart();
    }

    private void continueStart() {
        if (!pendingStart || destroyed) return;
        int step = Setup.missing(this);
        if (step == Setup.OK) {
            pendingStart = false;
            lastStep = -1;
            applyMaster(true);
            toast("SayDo is active! Tap floating START anytime");
            return;
        }
        if (step == lastStep) {
            pendingStart = false;
            lastStep = -1;
            toast(stepName(step) + " is required to start SayDo");
            refreshUi();
            return;
        }
        lastStep = step;
        switch (step) {
            case Setup.NEED_MIC:
                requestMic();
                break;
            case Setup.NEED_NOTIF:
                requestNotif();
                break;
            case Setup.NEED_BATTERY:
                SettingsRepository.getInstance(this).setBatteryAsked(true);
                Setup.route(this, step);
                break;
            case Setup.NEED_VOICE_PACK:
                SettingsRepository.getInstance(this).setVoiceGuided(true);
                Setup.route(this, step);
                break;
            default:
                Setup.route(this, step);
                break;
        }
    }

    private String stepName(int step) {
        switch (step) {
            case Setup.NEED_OVERLAY: return "Display over other apps";
            case Setup.NEED_ACCESSIBILITY: return "Accessibility service";
            case Setup.NEED_MIC: return "Microphone permission";
            case Setup.NEED_NOTIF: return "Notification permission";
            case Setup.NEED_BATTERY: return "Battery optimization exemption";
            case Setup.NEED_VOICE_PACK: return "Offline voice setup";
            default: return "Setup";
        }
    }

    private void applyMaster(boolean on) {
        if (destroyed) return;
        SettingsRepository.getInstance(this).setMasterOn(on);
        if (on) {
            FloatService.show(this);
            startListeningFlow();
        } else {
            FloatService.hide(this);
            stopListening();
        }
        refreshUi();
    }

    // ------------------------------------------------------------------ Permissions & Audio

    private void requestMic() {
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void startListeningFlow() {
        if (destroyed || !SettingsRepository.getInstance(this).isMasterOn()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotif();
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
        try {
            stopService(new Intent(this, VoiceService.class));
        } catch (Exception ignored) {
        }
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
                toast("Microphone permission is required");
            }
        } else if (code == REQ_NOTIF) {
            if (pendingStart) {
                continueStart();
            } else {
                startListeningFlow();
            }
        }
    }

    // ------------------------------------------------------------------ Macro Actions

    @Override
    public void onPlay(Macro macro) {
        if (macro == null) return;
        try {
            int r = Runner.run(this, macro, null);
            if (r == Runner.NO_ACCESSIBILITY) Setup.route(this, Setup.NEED_ACCESSIBILITY);
            else if (r == Runner.INVALID) toast("Invalid shortcut");
            else if (r == Runner.LOCKED) toast("Unlock your phone first");
        } catch (Exception e) {
            toast("Could not run");
        }
    }

    @Override
    public void onEdit(Macro macro) {
        if (macro == null) return;
        Intent intent = new Intent(this, uz.saydo.app.kernel.ui.MacroStudioActivity.class);
        intent.putExtra(uz.saydo.app.kernel.ui.MacroStudioActivity.EXTRA_MACRO_ID, macro.id);
        startActivity(intent);
    }

    @Override
    public void onDelete(String macroId) {
        if (macroId == null) return;
        String armed = adapter.getArmedDeleteId();
        if (!macroId.equals(armed)) {
            adapter.armDelete(macroId);
            toast("Tap again to delete");
            ui.postDelayed(() -> {
                if (!destroyed && macroId.equals(adapter.getArmedDeleteId())) {
                    adapter.armDelete(null);
                }
            }, 2500);
            return;
        }

        // Armed confirmation matched -> Delete by UUID
        adapter.armDelete(null);
        boolean deleted = MacroRepository.getInstance(this).delete(macroId);
        if (deleted) {
            toast("Deleted");
        } else {
            toast("Could not delete");
        }
    }

    private void toast(String message) {
        if (destroyed || message == null) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void openDebug() {
        try {
            startActivity(new Intent(this, DebugActivity.class));
        } catch (Exception e) {
            toast("Could not open debug");
        }
    }
}
