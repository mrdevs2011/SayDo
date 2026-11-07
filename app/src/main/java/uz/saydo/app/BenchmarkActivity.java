package uz.saydo.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.metrics.Metrics;

/**
 * Roadmap Stage 1: measure real-device reliability.
 *
 * - Macro test: runs one macro N times; after each run the human confirms whether it really
 *   reached the right end state (the app's own "OK" is not trusted).
 * - Report: summary of everything recorded in metrics.jsonl.
 */
public class BenchmarkActivity extends Activity {


    // Macro test state is static so it survives the activity going to the background
    // while the macro runs in other apps.
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Macro mbMacro;
    private static int mbTotal, mbDone, mbOk;
    private static int mbViewSec = 3;
    private static boolean mbActive, mbAwaitVerdict;
    private static boolean mbReportedOk;
    private static long mbRunId;

    private final Handler h = new Handler(Looper.getMainLooper());
    private float d;

    // Macro UI
    private Spinner macroSpinner;
    private EditText runsInput, viewSecInput, brokeStepInput;
    private TextView macroStatus;
    private LinearLayout verdictPanel;
    private Button macroStart;
    private List<Macro> macros = new ArrayList<>();

    // Report UI
    private TextView reportView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        d = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B1220"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(28), dp(16), dp(32));
        scroll.addView(root);

        root.addView(text("SayDo Benchmark", 20, true, "#FFFFFF"));
        root.addView(text("Real telefonda o'lchov. Natijalar metrics.jsonl ga yoziladi.",
                12, false, "#94A3B8"));

        buildMacroSection(root);
        buildReportSection(root);

        setContentView(scroll);
        refreshReport();
    }

    // ================================================================== Macro test

    private void buildMacroSection(LinearLayout root) {
        root.addView(header("1. Makro testi"));
        root.addView(text("Makro N marta ishga tushadi. Har safar oxirida natijani ko'rib chiqing, so'ng "
                + "SayDo qaytib ochiladi va siz \"to'g'ri bo'ldimi?\" deb javob berasiz.", 12, false, "#94A3B8"));

        macros = new ArrayList<>(MacroRepository.getInstance(this).getAll());
        List<String> names = new ArrayList<>();
        for (Macro m : macros) {
            names.add((m.trigger.isEmpty() ? "(nomsiz)" : m.trigger) + " · " + m.gestures.size() + " qadam");
        }
        macroSpinner = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        macroSpinner.setAdapter(ad);
        macroSpinner.setBackgroundColor(Color.parseColor("#1E293B"));
        root.addView(macroSpinner);

        LinearLayout row = row();
        row.addView(text("Necha marta:", 14, false, "#E2E8F0"));
        runsInput = number("10");
        row.addView(runsInput, weight());
        row.addView(text("Ko'rish (s):", 14, false, "#E2E8F0"));
        viewSecInput = number(String.valueOf(mbViewSec));
        row.addView(viewSecInput, weight());
        root.addView(row);

        macroStart = button("Makro testini boshlash", "#3B82F6");
        macroStart.setOnClickListener(v -> {
            if (mbActive) stopMacroBench("To'xtatildi");
            else startMacroBench();
        });
        root.addView(macroStart);

        macroStatus = text("", 13, false, "#E2E8F0");
        macroStatus.setTypeface(Typeface.MONOSPACE);
        root.addView(macroStatus);

        verdictPanel = new LinearLayout(this);
        verdictPanel.setOrientation(LinearLayout.VERTICAL);
        verdictPanel.setBackgroundColor(Color.parseColor("#1E293B"));
        verdictPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
        verdictPanel.addView(text("Makro oxirigacha TO'G'RI bajarildimi?", 16, true, "#FFFFFF"));
        LinearLayout vr = row();
        Button yes = button("Ha, to'g'ri", "#22C55E");
        Button no = button("Yo'q", "#EF4444");
        vr.addView(yes, weight());
        vr.addView(no, weight());
        verdictPanel.addView(vr);
        verdictPanel.addView(text("Yo'q bo'lsa — qaysi qadamda buzildi? (bilmasangiz bo'sh qoldiring)",
                12, false, "#94A3B8"));
        brokeStepInput = number("");
        verdictPanel.addView(brokeStepInput);
        verdictPanel.setVisibility(View.GONE);
        root.addView(verdictPanel);

        yes.setOnClickListener(v -> submitVerdict(true));
        no.setOnClickListener(v -> submitVerdict(false));
    }

    private void startMacroBench() {
        if (macros.isEmpty()) {
            toast("Makro yo'q");
            return;
        }
        if (GestureService.instance == null) {
            toast("Avval Accessibility'ni yoqing");
            return;
        }
        int pos = macroSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= macros.size()) return;
        mbMacro = macros.get(pos);
        mbTotal = clamp(parseInt(runsInput, 10), 1, 100);
        mbViewSec = clamp(parseInt(viewSecInput, 3), 0, 30);
        mbDone = 0;
        mbOk = 0;
        mbActive = true;
        mbAwaitVerdict = false;
        macroStatus.setText("");
        renderMacroState();
        runNextMacro(getApplicationContext());
    }

    private static void runNextMacro(final Context app) {
        if (!mbActive || mbMacro == null) return;
        if (mbDone >= mbTotal) {
            mbActive = false;
            bringToFront(app);
            return;
        }
        Metrics.nextRunSource = "bench";
        final long prevRunId = Metrics.lastRunId;
        int r = Runner.run(app, mbMacro, completed -> {
            if (!mbActive) return;
            mbReportedOk = completed;
            // 0 = playback never started (e.g. Home step failed), so there is no run to attach to.
            mbRunId = Metrics.lastRunId != prevRunId ? Metrics.lastRunId : 0;
            mbAwaitVerdict = true;
            // Leave time to look at the final screen before SayDo comes back.
            MAIN.postDelayed(() -> bringToFront(app), mbViewSec * 1000L);
        });
        if (r != Runner.OK) {
            mbActive = false;
            Metrics.nextRunSource = "manual";
            Toast.makeText(app, "Ishga tushmadi (kod " + r + ")", Toast.LENGTH_LONG).show();
            bringToFront(app);
        }
    }

    private static void bringToFront(Context app) {
        try {
            Intent i = new Intent(app, BenchmarkActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            app.startActivity(i);
        } catch (Throwable ignored) {
            // Background start may be blocked; the verdict panel shows when the user reopens SayDo.
        }
    }

    private void submitVerdict(boolean ok) {
        if (!mbAwaitVerdict) return;
        int broke = ok ? -1 : parseInt(brokeStepInput, -1);
        Metrics.verdict(mbRunId, ok, broke);
        mbAwaitVerdict = false;
        mbDone++;
        if (ok) mbOk++;
        String line = String.format(Locale.US, "%d/%d  %s  (ilova: %s)%s%n",
                mbDone, mbTotal, ok ? "✓ to'g'ri" : "✗ xato", mbReportedOk ? "OK" : "xato",
                (!ok && broke > 0) ? "  qadam " + broke : "");
        macroStatus.append(line);
        brokeStepInput.setText("");
        if (mbDone >= mbTotal) {
            stopMacroBench("Tugadi");
        } else {
            renderMacroState();
            final Context app = getApplicationContext();
            MAIN.postDelayed(() -> runNextMacro(app), 1000);
        }
    }

    private void stopMacroBench(String why) {
        boolean wasRunning = mbActive && !mbAwaitVerdict;
        mbActive = false;
        mbAwaitVerdict = false;
        MAIN.removeCallbacksAndMessages(null);
        if (wasRunning && GestureService.instance != null) GestureService.instance.cancel();
        macroStatus.append(String.format(Locale.US, "%n%s: %d/%d to'g'ri%n", why, mbOk, mbDone));
        renderMacroState();
        refreshReport();
    }

    private void renderMacroState() {
        if (verdictPanel == null) return;
        verdictPanel.setVisibility(mbAwaitVerdict ? View.VISIBLE : View.GONE);
        macroStart.setText(mbActive ? "To'xtatish" : "Makro testini boshlash");
    }

    // ================================================================== Report

    private void buildReportSection(LinearLayout root) {
        root.addView(header("2. Hisobot"));
        LinearLayout row = row();
        Button refresh = button("Yangilash", "#64748B");
        Button copy = button("Nusxa olish", "#22C55E");
        Button clear = button("Tozalash", "#EF4444");
        row.addView(refresh, weight());
        row.addView(copy, weight());
        row.addView(clear, weight());
        root.addView(row);

        reportView = text("…", 11, false, "#E2E8F0");
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        root.addView(reportView);

        refresh.setOnClickListener(v -> refreshReport());
        copy.setOnClickListener(v -> {
            File f = Metrics.file();
            String body = reportView.getText() + (f == null ? "" : "\nraw: " + f.getAbsolutePath() + "\n");
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("SayDo metrics", body));
                toast("Nusxa olindi");
            }
        });
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Barcha o'lchov ma'lumotlari o'chirilsinmi?")
                .setPositiveButton("O'chirish", (dlg, w) -> {
                    Metrics.clear();
                    h.postDelayed(this::refreshReport, 300);
                })
                .setNegativeButton("Bekor", null)
                .show());
    }

    private void refreshReport() {
        if (reportView == null) return;
        new Thread(() -> {
            final String r = Metrics.report();
            h.post(() -> { if (reportView != null) reportView.setText(r); });
        }, "SayDo-report").start();
    }

    // ================================================================== lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        renderMacroState();
        if (!mbActive && mbDone > 0 && mbDone >= mbTotal) refreshReport();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ================================================================== UI helpers

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
        t.setPadding(0, dp(24), 0, dp(4));
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
        p.setMargins(dp(4), dp(6), dp(4), dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private EditText number(String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.parseColor("#64748B"));
        e.setMinWidth(dp(56));
        return e;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        return p;
    }

    private static int parseInt(EditText e, int def) {
        try {
            String s = e.getText().toString().trim();
            return s.isEmpty() ? def : Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
