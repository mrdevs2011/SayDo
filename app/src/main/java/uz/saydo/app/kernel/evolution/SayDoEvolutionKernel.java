package uz.saydo.app.kernel.evolution;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * SayDo Autonomous Evolution Kernel (The Closure Protocol).
 * Self-tuning diagnostic engine that dynamically calibrates voice recognition thresholds,
 * prunes stale diagnostics, and tracks repetitive user behavior to suggest automated shortcuts.
 */
public final class SayDoEvolutionKernel {

    private static final long LOG_ROTATION_THRESHOLD_BYTES = 128 * 1024; // 128 KB

    private final Context context;
    private final Map<String, Integer> actionFrequencyCounter = new HashMap<>();
    private double currentConfidenceThreshold = 0.80; // Baseline 80%

    public SayDoEvolutionKernel(Context context) {
        this.context = (context != null) ? context.getApplicationContext() : null;
    }

    /**
     * Dynamically calibrates speech recognition confidence threshold based on ambient noise.
     * Silent room -> relax threshold to 0.72 for effortless triggers.
     * Busy street / cafeteria -> tighten threshold to 0.88 to eliminate false positives.
     */
    public synchronized double calibrateVoiceThreshold(double ambientRmsEnergy) {
        if (ambientRmsEnergy < 150.0) {
            // Quiet environment
            currentConfidenceThreshold = 0.72;
        } else if (ambientRmsEnergy > 600.0) {
            // Noisy environment
            currentConfidenceThreshold = 0.88;
        } else {
            // Normal environment: linear interpolation
            double ratio = (ambientRmsEnergy - 150.0) / (450.0);
            currentConfidenceThreshold = 0.72 + (0.16 * ratio);
        }
        return currentConfidenceThreshold;
    }

    public synchronized double getCurrentConfidenceThreshold() {
        return currentConfidenceThreshold;
    }

    /**
     * Records an app action and suggests creating a shortcut if repeated 3+ times.
     */
    public synchronized String recordActionAndCheckSuggestion(String appPackage, String actionName) {
        if (appPackage == null || actionName == null) return null;
        String key = appPackage + ":" + actionName;
        int count = actionFrequencyCounter.getOrDefault(key, 0) + 1;
        actionFrequencyCounter.put(key, count);

        if (count == 3) {
            return "SayDo noticed you frequently " + actionName + " in " + appPackage + ". Would you like to create a voice shortcut?";
        }
        return null;
    }

    /**
     * Automatically inspects and rotates local diagnostic logs if exceeding size limits.
     */
    public void rotateLogsIfNeeded(File logFile) {
        if (logFile == null || !logFile.exists()) return;
        try {
            if (logFile.length() > LOG_ROTATION_THRESHOLD_BYTES) {
                File backup = new File(logFile.getParentFile(), logFile.getName() + ".old");
                if (backup.exists()) {
                    backup.delete();
                }
                logFile.renameTo(backup);
                // Create fresh log file
                try (FileOutputStream fos = new FileOutputStream(logFile)) {
                    String header = "[LOG ROTATED AT " + System.currentTimeMillis() + "]\n";
                    fos.write(header.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("SayDoEvolutionKernel", "Log rotation error", t);
        }
    }
}
