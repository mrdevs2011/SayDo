package uz.saydo.app.kernel.workflow;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Adaptive Conditional Logic & Branching Engine (IF/THEN/ELSE).
 * Evaluates real-time screen nodes, text presence, and context memory registers to make branching decisions.
 */
public final class BranchEvaluator {

    public enum Operator {
        TEXT_CONTAINS,
        TEXT_EQUALS,
        REGISTER_EQUALS,
        NODE_EXISTS,
        ALWAYS_TRUE
    }

    public static class BranchCondition {
        public final Operator operator;
        public final String operandA;
        public final String operandB;
        public final int targetStepIfTrue;
        public final int targetStepIfFalse;

        public BranchCondition(Operator operator, String operandA, String operandB, int targetStepIfTrue, int targetStepIfFalse) {
            this.operator = operator != null ? operator : Operator.ALWAYS_TRUE;
            this.operandA = operandA != null ? operandA : "";
            this.operandB = operandB != null ? operandB : "";
            this.targetStepIfTrue = targetStepIfTrue;
            this.targetStepIfFalse = targetStepIfFalse;
        }
    }

    private BranchEvaluator() {
    }

    /**
     * Evaluates condition against active window and memory registers, returning next step index.
     */
    public static int evaluateBranch(BranchCondition condition, AccessibilityNodeInfo root, ContextMemory memory, int currentStep) {
        if (condition == null) return currentStep + 1;

        boolean conditionResult = false;
        switch (condition.operator) {
            case ALWAYS_TRUE:
                conditionResult = true;
                break;

            case REGISTER_EQUALS:
                if (memory != null) {
                    String val = memory.get(condition.operandA);
                    conditionResult = val.equalsIgnoreCase(condition.operandB);
                }
                break;

            case TEXT_CONTAINS:
                if (root != null) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(condition.operandA);
                    conditionResult = nodes != null && !nodes.isEmpty();
                }
                break;

            case NODE_EXISTS:
                if (root != null) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(condition.operandA);
                    conditionResult = nodes != null && !nodes.isEmpty();
                }
                break;

            default:
                conditionResult = false;
                break;
        }

        return conditionResult ? condition.targetStepIfTrue : condition.targetStepIfFalse;
    }
}
