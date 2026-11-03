package uz.saydo.app;

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

/** Opens when floating Start is pressed: asks for the voice trigger phrase. */
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
        root.setBackgroundColor(Color.argb(200, 15, 31, 82));
        int pad = Math.round(28 * d);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Math.round(20 * d), Math.round(22 * d), Math.round(20 * d), Math.round(18 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1B3A8C"));
        bg.setCornerRadius(18 * d);
        bg.setStroke(Math.round(1 * d), Color.parseColor("#2A4A9E"));
        card.setBackground(bg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;

        TextView title = new TextView(this);
        title.setText("Enter your voice trigger");
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, Math.round(14 * d));
        card.addView(title);

        final EditText input = new EditText(this);
        input.setHint("e.g. open youtube");
        input.setHintTextColor(Color.parseColor("#A8C0E8"));
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setTextSize(16);
        input.setPadding(Math.round(14 * d), Math.round(14 * d), Math.round(14 * d), Math.round(14 * d));
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(Color.parseColor("#152868"));
        ibg.setCornerRadius(12 * d);
        ibg.setStroke(Math.round(1 * d), Color.parseColor("#2A4A9E"));
        input.setBackground(ibg);
        card.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button go = new Button(this);
        go.setText("Start recording");
        go.setAllCaps(false);
        go.setTextColor(Color.WHITE);
        go.setTextSize(16);
        GradientDrawable gbg = new GradientDrawable();
        gbg.setColor(Color.parseColor("#22C55E"));
        gbg.setCornerRadius(12 * d);
        go.setBackground(gbg);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Math.round(14 * d);
        card.addView(go, glp);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setTextColor(Color.parseColor("#A8C0E8"));
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
                    Toast.makeText(TriggerActivity.this, "Enter a phrase", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!Net.isOnline(TriggerActivity.this)) {
                    Toast.makeText(TriggerActivity.this, "You're offline", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                // Qulf ekranida ham, ochiq telefonda ham darhol yozishga o'tadi.
                // Qulf ekranida yozilsa, slide va parol kiritish ham yozib olinadi.
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
            @Override public void onClick(View v) { /* keep open */ }
        });

        input.requestFocus();
    }
}
