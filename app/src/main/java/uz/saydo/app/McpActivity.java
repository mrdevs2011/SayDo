package uz.saydo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MCP settings for this device: on/off, full control, connection info for the AI,
 * token rotation and live status.
 */
public class McpActivity extends Activity implements McpService.Listener {

    private McpConfig cfg;
    private float d;

    private Switch enable, full;
    private TextView status, endpoint, tokenView, hint;
    private EditText nameInput, relayInput;
    private Button battery;
    private boolean showToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new McpConfig(this);
        d = getResources().getDisplayMetrics().density;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B1220"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root);

        root.addView(text("MCP — AI boshqaruvi", 24, true, "#FFFFFF"));
        root.addView(text("Yoqilsa, Claude kabi AI yordamchilar shu telefonni internet orqali boshqara oladi: "
                + "makrolarni ishga tushiradi, ekranni o'qiydi, bosadi, matn yozadi va skrinshot oladi.",
                13, false, "#94A3B8"));

        TextView warn = text("⚠ Tokenni hech kimga bermang. Kimda token bo'lsa, u telefoningizni boshqara oladi. "
                + "Shubha bo'lsa — \"Tokenni yangilash\" yoki MCP'ni o'chiring.", 13, true, "#FBBF24");
        warn.setBackgroundColor(Color.parseColor("#2A2210"));
        warn.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wp.setMargins(0, dp(12), 0, dp(4));
        root.addView(warn, wp);

        enable = switchRow(root, "Bu telefonda MCP'ni yoqish");
        full = switchRow(root, "To'liq boshqaruv (ekranni o'qish, bosish, yozish, skrinshot)");
        root.addView(text("O'chiq bo'lsa AI faqat holatni ko'radi va makrolarni ishga tushiradi.",
                12, false, "#94A3B8"));

        status = text("", 14, true, "#E2E8F0");
        status.setPadding(0, dp(12), 0, dp(4));
        root.addView(status);
        hint = text("", 12, false, "#FBBF24");
        root.addView(hint);
        battery = button("Batareya cheklovini o'chirish", "#64748B");
        root.addView(battery);

        // --- connection info
        root.addView(header("AI'ga ulash"));
        root.addView(text("Claude Code: buyruqni nusxalab terminalda ishga tushiring.", 12, false, "#94A3B8"));
        Button copyCmd = button("Claude Code buyrug'ini nusxalash", "#22C55E");
        root.addView(copyCmd);
        root.addView(text("Faqat URL qabul qiladigan ilovalar uchun (token URL ichida):", 12, false, "#94A3B8"));
        Button copyUrl = button("MCP URL'ni nusxalash", "#3B82F6");
        root.addView(copyUrl);
        endpoint = mono();
        root.addView(endpoint);
        tokenView = mono();
        root.addView(tokenView);
        Button toggleToken = button("Tokenni ko'rsatish / yashirish", "#334155");
        root.addView(toggleToken);
        Button rotate = button("Tokenni yangilash (eskisi ishlamay qoladi)", "#EF4444");
        root.addView(rotate);

        // --- device
        root.addView(header("Qurilma"));
        root.addView(text("AI ko'radigan nom:", 12, false, "#94A3B8"));
        nameInput = input(cfg.name());
        root.addView(nameInput);
        root.addView(text("Relay server (o'zgartirmang, agar o'zingiz joylamagan bo'lsangiz):", 12, false, "#94A3B8"));
        relayInput = input(cfg.relay());
        relayInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(relayInput);
        Button save = button("Saqlash", "#3B82F6");
        root.addView(save);
        root.addView(text("Qurilma ID: " + cfg.deviceId(), 11, false, "#64748B"));

        setContentView(scroll);

        enable.setChecked(cfg.enabled());
        full.setChecked(cfg.fullControl());
        enable.setOnCheckedChangeListener((b, on) -> {
            cfg.setEnabled(on);
            if (on) McpService.startIfEnabled(this);
            else McpService.stop(this);
            render();
        });
        full.setOnCheckedChangeListener((b, on) -> cfg.setFullControl(on));

        copyCmd.setOnClickListener(v -> copy("SayDo MCP", cfg.claudeCodeCommand(), "Buyruq nusxalandi"));
        copyUrl.setOnClickListener(v -> copy("SayDo MCP", cfg.mcpUrlWithToken(), "URL nusxalandi"));
        toggleToken.setOnClickListener(v -> {
            showToken = !showToken;
            render();
        });
        rotate.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Yangi token yaratilsinmi? Eski token bilan ulangan barcha AI'lar uziladi.")
                .setPositiveButton("Yangilash", (dlg, w) -> {
                    cfg.rotateToken();
                    restartIfRunning();
                    render();
                    toast("Yangi token yaratildi. AI'ga qayta ulang.");
                })
                .setNegativeButton("Bekor", null)
                .show());
        save.setOnClickListener(v -> {
            String url = relayInput.getText().toString().trim();
            if (!url.isEmpty() && !url.startsWith("https://")) {
                toast("Relay manzili https:// bilan boshlanishi kerak");
                return;
            }
            cfg.setName(nameInput.getText().toString());
            cfg.setRelay(url);
            restartIfRunning();
            render();
            toast("Saqlandi");
        });
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        McpService.addListener(this);
        McpService.startIfEnabled(this);
        render();
    }

    @Override
    protected void onStop() {
        McpService.removeListener(this);
        super.onStop();
    }

    @Override
    public void onMcpStatus() {
        runOnUiThread(this::render);
    }

    private void restartIfRunning() {
        if (!cfg.enabled()) return;
        McpService.stop(this);
        status.postDelayed(() -> McpService.startIfEnabled(this), 600);
    }

    private void render() {
        if (status == null) return;
        if (!cfg.enabled()) {
            status.setText("○ O'chiq");
            status.setTextColor(Color.parseColor("#64748B"));
        } else if (McpService.connected) {
            String t = McpService.lastPollOkAt > 0
                    ? new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(McpService.lastPollOkAt)) : "";
            status.setText("● Ulangan (" + t + ")"
                    + (McpService.lastCommand.isEmpty() ? "" : "\nOxirgi buyruq: " + McpService.lastCommand));
            status.setTextColor(Color.parseColor("#22C55E"));
        } else {
            status.setText(McpService.lastError.isEmpty() ? "◌ Ulanmoqda…" : "✕ " + McpService.lastError);
            status.setTextColor(Color.parseColor("#FBBF24"));
        }

        StringBuilder h = new StringBuilder();
        if (cfg.enabled() && GestureService.instance == null) {
            h.append("Accessibility o'chiq: AI ekranni boshqara olmaydi. Asosiy ekranda START bosing.\n");
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        if (cfg.enabled() && !batteryOk) {
            h.append("Batareya cheklovi yoqiq: ekran o'chganda Android ulanishni uzishi mumkin.");
        }
        hint.setText(h.toString().trim());
        hint.setVisibility(h.length() == 0 ? View.GONE : View.VISIBLE);
        battery.setVisibility(cfg.enabled() && !batteryOk ? View.VISIBLE : View.GONE);

        endpoint.setText("Endpoint: " + cfg.mcpEndpoint());
        String tok = cfg.clientToken();
        tokenView.setText("Token: " + (showToken ? tok : tok.substring(0, 4) + "••••••••••••" + tok.substring(tok.length() - 4)));
    }

    private void copy(String label, String value, String done) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText(label, value);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Hide the secret from the clipboard preview overlay.
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        toast(done);
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

    private TextView mono() {
        TextView t = text("", 12, false, "#E2E8F0");
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(false);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 17, true, "#FFFFFF");
        t.setPadding(0, dp(22), 0, dp(4));
        return t;
    }

    private Switch switchRow(LinearLayout root, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(2));
        row.addView(text(label, 15, true, "#E2E8F0"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        row.addView(sw);
        root.addView(row);
        return sw;
    }

    private Button button(String s, String color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(color));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private EditText input(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setSingleLine(true);
        return e;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
