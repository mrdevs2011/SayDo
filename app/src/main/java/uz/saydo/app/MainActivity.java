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

    /** VoiceService state changes arrive on arbitrary threads; refresh the status text on the UI thread. */
    private final VoiceService.StateListener voiceListener =
            (state, text) -> ui.post(this::refreshUi);

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

        View mcp = findViewById(R.id.btnMcp);
        if (mcp != null) {
            mcp.setOnClickListener(v -> startActivity(new Intent(this, McpActivity.class)));
        }
        McpService.startIfEnabled(this);

        View voiceGate = findViewById(R.id.btnVoiceGate);
        if (voiceGate != null) {
            voiceGate.setOnClickListener(v -> startActivity(new Intent(this, VoiceGateActivity.class)));
        }

        View title = findViewById(R.id.titleSayDo);
        if (title != null) {
            title.setOnLongClickListener(v -> {
                openDebug();
                return true;
            });
        }
        if (hint != null) {
            hint.setOnLongClickListener(v -> {
                openDebug();
                return true;
            });
        }

        // Reactive subscriptions (Push updates instead of pull/polling)
        MacroRepository.getInstance(this).addListener(this);
        SettingsRepository.getInstance(this).addListener(this);

        VoiceService.addStateListener(voiceListener);
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
        VoiceService.removeStateListener(voiceListener);
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

        String packMsg = VoiceService.voicePackMessage;
        if (enabled && listening && packMsg != null) {
            hint.setText("Listening · " + packMsg);
            hint.setTextColor(ContextCompat.getColor(this, R.color.accent));
        } else if (enabled && listening) {
            hint.setText("Listening · say a trigger");
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
            offerBatteryOnce();
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
            default:
                Setup.route(this, step);
                break;
        }
    }

    /** Optional and asked only once. Declining or ignoring it changes nothing about START. */
    private void offerBatteryOnce() {
        if (destroyed || !Setup.shouldOfferBattery(this)) return;
        SettingsRepository.getInstance(this).setBatteryAsked(true);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Keep voice listening alive?")
                .setMessage("Android may stop SayDo in the background to save battery. "
                        + "You can allow unrestricted background use. This is optional.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings", (d, w) -> Setup.route(this, Setup.NEED_BATTERY))
                .show();
    }

    private String stepName(int step) {
        switch (step) {
            case Setup.NEED_OVERLAY: return "Display over other apps";
            case Setup.NEED_ACCESSIBILITY: return "Accessibility service";
            case Setup.NEED_MIC: return "Microphone permission";
            case Setup.NEED_NOTIF: return "Notification permission";
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
        if (macroId == null || destroyed) return;
        String name = macroId;
        for (Macro m : MacroRepository.getInstance(this).getAll()) {
            if (m != null && macroId.equals(m.id)) {
                name = m.trigger;
                break;
            }
        }
        final String idToDelete = macroId;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete '" + name + "'?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    boolean deleted = MacroRepository.getInstance(this).delete(idToDelete);
                    toast(deleted ? "Deleted" : "Could not delete");
                })
                .show();
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
