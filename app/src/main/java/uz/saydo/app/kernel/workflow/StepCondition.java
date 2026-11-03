package uz.saydo.app.kernel.workflow;

import android.view.accessibility.AccessibilityNodeInfo;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Smart Synchronization Barrier Condition for resilient workflow orchestration.
 * Replaces static thread sleeps with dynamic screen-state barriers.
 */
public final class StepCondition implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String TYPE_WAIT_FOR_PACKAGE = "wait_package";
    public static final String TYPE_WAIT_FOR_TEXT = "wait_text";
    public static final String TYPE_WAIT_FOR_DISAPPEAR = "wait_disappear";

    public final String type;
    public final String targetValue;
    public final long timeoutMs;

    public StepCondition(String type, String targetValue, long timeoutMs) {
        this.type = (type != null) ? type.trim().toLowerCase() : TYPE_WAIT_FOR_TEXT;
        this.targetValue = (targetValue != null) ? targetValue.trim() : "";
        this.timeoutMs = (timeoutMs > 0) ? timeoutMs : 4000;
    }

    public static StepCondition waitForPackage(String packageName, long timeoutMs) {
        return new StepCondition(TYPE_WAIT_FOR_PACKAGE, packageName, timeoutMs);
    }

    public static StepCondition waitForText(String text, long timeoutMs) {
        return new StepCondition(TYPE_WAIT_FOR_TEXT, text, timeoutMs);
    }

    public static StepCondition waitForDisappear(String text, long timeoutMs) {
        return new StepCondition(TYPE_WAIT_FOR_DISAPPEAR, text, timeoutMs);
    }

    /**
     * Checks if condition is satisfied against current active screen hierarchy.
     */
    public boolean isSatisfied(AccessibilityNodeInfo root, String currentPackage) {
        if (TYPE_WAIT_FOR_PACKAGE.equals(type)) {
            return currentPackage != null && currentPackage.equalsIgnoreCase(targetValue);
        }

        if (root == null) return false;

        boolean textFound = containsTextInTree(root, targetValue);
        if (TYPE_WAIT_FOR_TEXT.equals(type)) {
            return textFound;
        } else if (TYPE_WAIT_FOR_DISAPPEAR.equals(type)) {
            return !textFound;
        }

        return false;
    }

    private static boolean containsTextInTree(AccessibilityNodeInfo root, String target) {
        if (root == null || target.isEmpty()) return false;
        Queue<AccessibilityNodeInfo> queue = new LinkedList<>();
        queue.offer(root);

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;

            CharSequence txt = node.getText();
            if (txt != null && txt.toString().toLowerCase().contains(target.toLowerCase())) {
                return true;
            }

            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().toLowerCase().contains(target.toLowerCase())) {
                return true;
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) queue.offer(c);
            }
        }
        return false;
    }
}
