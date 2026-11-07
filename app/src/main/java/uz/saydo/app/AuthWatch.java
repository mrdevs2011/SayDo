package uz.saydo.app;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.kernel.data.NodeAnchor;
import uz.saydo.app.kernel.gesture.SmartGestureDispatcher;
import uz.saydo.app.safety.AuthDetector;
import uz.saydo.app.safety.AuthRecordLogic;
import uz.saydo.app.util.DebugLog;

/**
 * Recording hook for fingerprint / face / lock-screen prompts (decisions are in AuthRecordLogic).
 * EventRecorder calls {@link #onEvent} first; true means "handled, record nothing else".
 *  - Window of an auth prompt: nothing is recorded while it is open.
 *  - Prompt closed with no touch inside: a wait_user step ("biometric") is added.
 *  - Taps inside the prompt (PIN pad): recorded as sensitive steps (stored encrypted, see MacroRepository).
 * Never logs labels or anything typed. NOT verified on a phone.
 */
final class AuthWatch {
    private final AuthRecordLogic logic = new AuthRecordLogic();

    void reset() {
        logic.reset();
    }

    boolean onEvent(AccessibilityEvent e, String pkg, int type, long now, long t0, List<Gesture> steps,
                    int sw, int sh) {
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cc = e.getClassName();
            String cls = cc != null ? cc.toString() : "";
            StringBuilder title = new StringBuilder();
            List<CharSequence> parts = e.getText();
            if (parts != null) for (CharSequence c : parts) if (c != null) title.append(c);
            boolean wasIn = logic.inAuth();
            AuthRecordLogic.Action a = logic.onWindow(pkg, cls, title.toString(), now);
            if (!wasIn && logic.inAuth()) {
                DebugLog.i("Rec", "auth prompt opened pkg=" + pkg + " cls=" + cls);
            }
            return handle(a, now, t0, steps);
        }
        if (!logic.inAuth()) return false;

        boolean click = type == AccessibilityEvent.TYPE_VIEW_CLICKED;
        if (click) {
            // A click on the prompt itself: the owner is using a PIN.
            if (AuthDetector.isNeutralDuringAuth(pkg, "") || AuthDetector.isAuthWindow(pkg, "", "")) {
                logic.onTouchInAuth();
                addSensitiveTap(e, pkg, now, t0, steps, sw, sh);
                return true;
            }
        }
        if (AuthDetector.isNeutralDuringAuth(pkg, "") || AuthDetector.isAuthWindow(pkg, "", "")) {
            return true; // other events of the prompt or the keyboard: never recorded
        }
        // An app behind the prompt is active again: the prompt is over (not every phone reports a window change).
        AuthRecordLogic.Action a = logic.onWindow(pkg, "", "", now);
        handle(a, now, t0, steps);
        return false; // the event itself belongs to the app and is recorded normally
    }

    private boolean handle(AuthRecordLogic.Action a, long now, long t0, List<Gesture> steps) {
        switch (a) {
            case EMIT_WAIT_USER:
                if (steps.size() < 200) {
                    steps.add(Gesture.createWaitUser(Gesture.REASON_BIOMETRIC, Math.max(0, logic.openedAt() - t0)));
                    DebugLog.i("Rec", "step #" + steps.size() + " wait_user biometric");
                }
                return true;
            case CONSUME:
                return true;
            default:
                return false;
        }
    }

    private void addSensitiveTap(AccessibilityEvent e, String pkg, long now, long t0, List<Gesture> steps,
                                 int sw, int sh) {
        if (steps.size() >= 200) return;
        AccessibilityNodeInfo src = e.getSource();
        NodeAnchor anchor = null;
        double x = 0.5, y = 0.5;
        try {
            if (src != null) {
                anchor = SmartGestureDispatcher.extractAnchor(src, true);
                Rect b = new Rect();
                src.getBoundsInScreen(b);
                if (b.width() > 0 && b.height() > 0 && sw > 0 && sh > 0) {
                    x = Math.max(0, Math.min(1, b.centerX() / (double) sw));
                    y = Math.max(0, Math.min(1, b.centerY() / (double) sh));
                }
            }
        } finally {
            if (src != null) {
                try {
                    src.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        long st = Math.max(0, now - t0);
        steps.add(Gesture.createSemantic(Gesture.TYPE_TAP, x, y, st, st + 20, anchor, "", pkg, "", 0, 0)
                .withSensitive(true));
        // Deliberately no label, id or count of digits in the log.
        DebugLog.i("Rec", "step #" + steps.size() + " tap in auth prompt (sensitive, stored encrypted)");
    }
}
