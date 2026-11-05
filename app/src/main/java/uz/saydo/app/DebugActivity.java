package uz.saydo.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import uz.saydo.app.util.DebugLog;

public class DebugActivity extends Activity implements DebugLog.Listener {
    private TextView body;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B1220"));
        root.setPadding(0, Math.round(28 * d), 0, 0);

        TextView title = new TextView(this);
        title.setText("SayDo Debug · STT");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(Math.round(16 * d), Math.round(8 * d), Math.round(16 * d), Math.round(8 * d));
        root.addView(title);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(Math.round(12 * d), 0, Math.round(12 * d), Math.round(8 * d));

        Button copy = btn("Copy all", "#22C55E");
        Button clear = btn("Clear", "#EF4444");
        Button refresh = btn("Refresh", "#3B82F6");
        Button bench = btn("Benchmark", "#A855F7");
        bar.addView(copy, lp(d));
        bar.addView(clear, lp(d));
        bar.addView(refresh, lp(d));
        bar.addView(bench, lp(d));
        root.addView(bar);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new TextView(this);
        body.setTextColor(Color.parseColor("#E2E8F0"));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(Math.round(12 * d), Math.round(8 * d), Math.round(12 * d), Math.round(24 * d));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        copy.setOnClickListener(v -> {
            if (DebugLog.copyToClipboard(this)) {
                Toast.makeText(this, "Copied — paste to chat", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Copy failed", Toast.LENGTH_SHORT).show();
            }
        });
        clear.setOnClickListener(v -> DebugLog.clear());
        refresh.setOnClickListener(v -> render());
        bench.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, BenchmarkActivity.class)));

        render();
    }

    private Button btn(String t, String color) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(color));
        return b;
    }

    private LinearLayout.LayoutParams lp(float d) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(Math.round(4 * d), 0, Math.round(4 * d), 0);
        return p;
    }

    private void render() {
        body.setText(DebugLog.dump());
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override public void onLog() { render(); }

    @Override protected void onStart() {
        super.onStart();
        DebugLog.addListener(this);
        render();
    }

    @Override protected void onStop() {
        DebugLog.removeListener(this);
        super.onStop();
    }
}
