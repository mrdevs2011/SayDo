package uz.saydo.app.kernel.workflow;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/**
 * Autonomous Smart Dialog Dismissal Engine.
 * Detects and automatically dismisses unexpected system permission popups,
 * GDPR cookie consents, and rate-app banners during automated workflows.
 */
public final class SmartDialogDismissor {

    private static final Set<String> DISMISS_BUTTON_TEXTS = new HashSet<>(Arrays.asList(
            "while using the app",
            "only this time",
            "allow",
            "accept all",
            "accept",
            "agree",
            "not now",
            "later",
            "maybe later",
            "no thanks",
            "got it",
            "dismiss",
            "close",
            "ok"
    ));

    private SmartDialogDismissor() {
    }

    /**
     * Inspects active window hierarchy and dismisses any recognized dialog/popup.
     * Returns true if a dialog was detected and successfully dismissed.
     */
    public static boolean autoDismissIfPresent(AccessibilityNodeInfo root) {
        if (root == null) return false;

        Queue<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.offer(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;

            if (node.isClickable()) {
                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();

                String tStr = (text != null) ? text.toString().toLowerCase().trim() : "";
                String dStr = (desc != null) ? desc.toString().toLowerCase().trim() : "";

                if (DISMISS_BUTTON_TEXTS.contains(tStr) || DISMISS_BUTTON_TEXTS.contains(dStr)) {
                    boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (clicked) {
                        return true;
                    }
                }
            }

            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) queue.offer(c);
            }
        }

        return false;
    }
}
