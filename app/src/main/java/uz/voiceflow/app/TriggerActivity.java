package uz.voiceflow.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Float Start bosilganda ochiladi: trigger so'zini so'raydi.
 * Klaviatura ishonchli ishlashi uchun alohida Activity.
 */
public class TriggerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.argb(180, 0, 0, 0));
        int pad = Math.round(28 * d);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Math.round(20 * d), Math.round(22 * d), Math.round(20 * d), Math.round(18 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(18 * d);
        card.setBackground(bg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;

        TextView title = new TextView(this);
        title.setText("Trigger so'zni yozing");
        title.setTextSize(18);
        title.setTextColor(Color.parseColor("#1c1c1e"));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, Math.round(14 * d));
        card.addView(title);

        final EditText input = new EditText(this);
        input.setHint("Masalan: YouTube och");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextSize(16);
        input.setPadding(Math.round(14 * d), Math.round(14 * d), Math.round(14 * d), Math.round(14 * d));
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(Color.parseColor("#F2F2F7"));
        ibg.setCornerRadius(12 * d);
        input.setBackground(ibg);
        card.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button go = new Button(this);
        go.setText("Yozishni boshlash");
        go.setAllCaps(false);
        go.setTextColor(Color.WHITE);
        go.setTextSize(16);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(Color.parseColor("#007AFF"));
        gbg.setCornerRadius(12 * d);
        go.setBackground(gbg);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Math.round(14 * d);
        card.addView(go, glp);

        Button cancel = new Button(this);
        cancel.setText("Bekor qilish");
        cancel.setAllCaps(false);
        cancel.setTextColor(Color.parseColor("#8e8e93"));
        cancel.setTextSize(14);
        cancel.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Math.round(4 * d);
        card.addView(cancel, clp);

        root.addView(card, cardLp);
        setContentView(root);

        Runnable start = new Runnable() {
            @Override public void run() {
                String t = input.getText() != null ? input.getText().toString().trim() : "";
                if (t.isEmpty()) {
                    Toast.makeText(TriggerActivity.this, "So'z yozing", Toast.LENGTH_SHORT).show();
                    return;
                }
                FloatService.startRecording(TriggerActivity.this, t);
                finish();
            }
        };
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { start.run(); }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    start.run();
                    return true;
                }
                return false;
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* card bosilsa yopilmasin */ }
        });

        input.requestFocus();
    }
}
