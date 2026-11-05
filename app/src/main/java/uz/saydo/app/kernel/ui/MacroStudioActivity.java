package uz.saydo.app.kernel.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import uz.saydo.app.GestureService;
import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;

/**
 * Macro Studio Visual Timeline & Step Editor Activity.
 * Allows users to inspect, fine-tune delays (+/- 50ms), delete mis-taps,
 * and test individual gesture steps without re-recording.
 */
public final class MacroStudioActivity extends Activity {

    public static final String EXTRA_MACRO_ID = "macro_id";

    private MacroRepository repository;
    private Macro currentMacro;
    private List<Gesture> editableGestures = new ArrayList<>();
    private LinearLayout timelineContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        repository = MacroRepository.getInstance(this);
        String macroId = getIntent().getStringExtra(EXTRA_MACRO_ID);

        if (macroId != null) {
            currentMacro = repository.getById(macroId);
        }

        if (currentMacro == null) {
            Toast.makeText(this, "Macro not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        editableGestures.addAll(currentMacro.gestures);
        buildLayout();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0C0E14"));
        root.setPadding(32, 48, 32, 32);

        // Header Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText(currentMacro.trigger);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText(String.format("%d steps â€¢ %d ms total", editableGestures.size(), currentMacro.duration));
        tvSubtitle.setTextColor(Color.parseColor("#8E9BB5"));
        tvSubtitle.setTextSize(13f);
        tvSubtitle.setPadding(0, 8, 0, 24);
        root.addView(tvSubtitle);

        // Scrollable timeline
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollView.setLayoutParams(scrollParams);

        timelineContainer = new LinearLayout(this);
        timelineContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(timelineContainer);
        root.addView(scrollView);

        renderTimelineSteps();

        // Save Button Footer
        Button btnSave = new Button(this);
        btnSave.setText("Save Changes");
        btnSave.setTextColor(Color.WHITE);
        btnSave.setTextSize(15f);
        btnSave.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setCornerRadius(24f);
        saveBg.setColor(Color.parseColor("#3B82F6"));
        btnSave.setBackground(saveBg);

        btnSave.setOnClickListener(v -> saveMacro());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 120);
        btnParams.topMargin = 24;
        root.addView(btnSave, btnParams);

        setContentView(root);
    }

    private void renderTimelineSteps() {
        timelineContainer.removeAllViews();

        for (int i = 0; i < editableGestures.size(); i++) {
            final int index = i;
            final Gesture g = editableGestures.get(i);

            LinearLayout stepCard = new LinearLayout(this);
            stepCard.setOrientation(LinearLayout.HORIZONTAL);
            stepCard.setGravity(Gravity.CENTER_VERTICAL);
            stepCard.setPadding(24, 20, 24, 20);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setCornerRadius(16f);
            cardBg.setColor(Color.parseColor("#151822"));
            cardBg.setStroke(1, Color.parseColor("#262C3D"));
            stepCard.setBackground(cardBg);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = 16;
            stepCard.setLayoutParams(cardParams);

            // Step Number & Type Info
            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            infoCol.setLayoutParams(infoParams);

            TextView tvStepName = new TextView(this);
            tvStepName.setText(String.format("Step %d: %s", (index + 1), g.type.toUpperCase()));
            tvStepName.setTextColor(Color.WHITE);
            tvStepName.setTextSize(14f);
            tvStepName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            infoCol.addView(tvStepName);

            TextView tvCoords = new TextView(this);
            tvCoords.setText(String.format("X: %.2f, Y: %.2f | Dur: %d ms", g.x, g.y, g.duration));
            tvCoords.setTextColor(Color.parseColor("#78849E"));
            tvCoords.setTextSize(12f);
            infoCol.addView(tvCoords);

            stepCard.addView(infoCol);

            // -50ms Delay Button
            Button btnMinus = new Button(this);
            btnMinus.setText("-50");
            btnMinus.setTextColor(Color.parseColor("#CBD5E1"));
            btnMinus.setTextSize(10f);
            btnMinus.setOnClickListener(v -> adjustDuration(index, -50));
            stepCard.addView(btnMinus);

            // +50ms Delay Button
            Button btnPlus = new Button(this);
            btnPlus.setText("+50");
            btnPlus.setTextColor(Color.parseColor("#CBD5E1"));
            btnPlus.setTextSize(10f);
            btnPlus.setOnClickListener(v -> adjustDuration(index, 50));
            stepCard.addView(btnPlus);

            // Delete Step Button
            Button btnDel = new Button(this);
            btnDel.setText("X");
            btnDel.setTextColor(Color.parseColor("#EF4444"));
            btnDel.setTextSize(12f);
            btnDel.setOnClickListener(v -> deleteStep(index));
            stepCard.addView(btnDel);

            timelineContainer.addView(stepCard);
        }
    }

    private void adjustDuration(int index, long delta) {
        if (index < 0 || index >= editableGestures.size()) return;
        Gesture old = editableGestures.get(index);
        long newDur = Math.max(20, old.duration + delta);
        Gesture updated = new Gesture(old.type, old.action, old.x, old.y,
                old.startTime, old.startTime + newDur, newDur, old.points,
                old.anchor, old.variableSlot, old.textToInject, old.screenPkg);
        editableGestures.set(index, updated);
        renderTimelineSteps();
    }

    private void deleteStep(int index) {
        if (index < 0 || index >= editableGestures.size()) return;
        editableGestures.remove(index);
        renderTimelineSteps();
    }

    private void saveMacro() {
        if (currentMacro == null) return;
        Macro updated = currentMacro.withGestures(editableGestures);
        repository.save(updated);
        Toast.makeText(this, "Macro timeline updated", Toast.LENGTH_SHORT).show();
        finish();
    }
}
