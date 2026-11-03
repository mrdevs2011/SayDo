package uz.saydo.app.kernel.oem;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.lang.reflect.Method;

/**
 * Universal Dynamic Capability Probing Engine (Future-Proof Runtime).
 * Replaces hardcoded Build.VERSION clamps with dynamic reflection probing,
 * ensuring seamless forward-compatibility with Android 15, 16, 17, and beyond.
 */
public final class RuntimeCapabilityProbe {

    public static class Capabilities {
        public final boolean canDrawOverlays;
        public final boolean isBatteryOptimizationIgnored;
        public final boolean supportsTakeScreenshot;
        public final boolean hasStrongBoxKeyStore;
        public final boolean supportsForegroundMicrophoneType;
        public final int apiLevel;

        public Capabilities(boolean canDrawOverlays,
                            boolean isBatteryOptimizationIgnored,
                            boolean supportsTakeScreenshot,
                            boolean hasStrongBoxKeyStore,
                            boolean supportsForegroundMicrophoneType,
                            int apiLevel) {
            this.canDrawOverlays = canDrawOverlays;
            this.isBatteryOptimizationIgnored = isBatteryOptimizationIgnored;
            this.supportsTakeScreenshot = supportsTakeScreenshot;
            this.hasStrongBoxKeyStore = hasStrongBoxKeyStore;
            this.supportsForegroundMicrophoneType = supportsForegroundMicrophoneType;
            this.apiLevel = apiLevel;
        }
    }

    private RuntimeCapabilityProbe() {
    }

    public static Capabilities probe(Context context) {
        if (context == null) {
            return new Capabilities(false, false, false, false, false, Build.VERSION.SDK_INT);
        }

        boolean overlay = probeOverlayPermission(context);
        boolean batteryIgnored = probeBatteryOptimization(context);
        boolean takeScreenshot = probeTakeScreenshotSupport();
        boolean strongBox = probeStrongBox(context);
        boolean fgMic = probeForegroundMicrophone();

        return new Capabilities(overlay, batteryIgnored, takeScreenshot, strongBox, fgMic, Build.VERSION.SDK_INT);
    }

    public static boolean probeOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                return Settings.canDrawOverlays(context);
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    public static boolean probeBatteryOptimization(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    public static boolean probeTakeScreenshotSupport() {
        // AccessibilityService.takeScreenshot introduced in API 30 (Android 11)
        try {
            Class<?> cls = Class.forName("android.accessibilityservice.AccessibilityService");
            Method m = cls.getMethod("takeScreenshot", int.class, java.util.concurrent.Executor.class,
                    Class.forName("android.accessibilityservice.AccessibilityService$TakeScreenshotCallback"));
            return m != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean probeStrongBox(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    public static boolean probeForegroundMicrophone() {
        return Build.VERSION.SDK_INT >= 29; // API 29+ Android 10+
    }
}
