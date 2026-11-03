package uz.saydo.app.kernel.workflow;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.kernel.gesture.SmartGestureDispatcher;

/**
 * Cross-App DAG Workflow Pipeline Orchestrator.
 * Sequences multi-app automations, executes conditional step transitions,
 * evaluates screen-state barriers, and exchanges data across apps via ContextMemory.
 */
public final class PipelineRunner {

    public interface PipelineCallback {
        void onStepStarted(int stepIndex, int totalSteps, String stepDescription);
        void onPipelineComplete(boolean success);
    }

    private final Context context;
    private final ContextMemory memory;

    public PipelineRunner(Context context) {
        this.context = (context != null) ? context.getApplicationContext() : null;
        this.memory = ContextMemory.getInstance();
    }

    /**
     * Launches external application package by Intent.
     */
    public boolean launchApp(String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(intent);
                return true;
            }
        } catch (Throwable t) {
            android.util.Log.e("PipelineRunner", "Failed to launch package: " + packageName, t);
        }
        return false;
    }

    /**
     * Executes a single workflow step against the active Accessibility window.
     */
    public SmartGestureDispatcher.ResolveResult executeStep(AccessibilityNodeInfo root, Gesture gesture,
                                                           int screenWidth, int screenHeight) {
        if (gesture == null) {
            return SmartGestureDispatcher.ResolveResult.coordinateFallback(0, 0);
        }

        // Interpolate text parameters if step has variables
        String textToInject = gesture.textToInject;
        if (!gesture.variableSlot.isEmpty()) {
            String slotVal = memory.get(gesture.variableSlot);
            if (!slotVal.isEmpty()) {
                textToInject = slotVal;
            }
        }

        if (textToInject != null && !textToInject.isEmpty()) {
            textToInject = memory.interpolate(textToInject);
        }

        return SmartGestureDispatcher.resolveAndExecute(root, gesture, screenWidth, screenHeight, textToInject);
    }

    public ContextMemory getMemory() {
        return memory;
    }
}
