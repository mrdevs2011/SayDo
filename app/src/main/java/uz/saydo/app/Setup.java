package uz.saydo.app;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import uz.saydo.app.data.SettingsRepository;
import uz.saydo.app.util.OemPermissionHelper;

/**
 * Robust Onboarding & Permission Resolution Engine.
 * Evaluates real-time device capability and directs user through a zero-friction sequential flow.
 */
public final class Setup {
    public static final int OK = 0;
    public static final int NEED_OVERLAY = 1;
    public static final int NEED_ACCESSIBILITY = 2;
    public static final int NEED_MIC = 3;
    public static final int NEED_NOTIF = 4;
    /** Optional: never returned by missing(); used only to open the battery page on request. */
    public static final int NEED_BATTERY = 5;

    private Setup() {}

    public static boolean accessibilityOn(Context c) {
        if (c == null) return false;
        try {
            if (GestureService.instance != null) return true;
            String enabled = Settings.Secure.getString(
                    c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            ComponentName me = new ComponentName(c, GestureService.class);
            for (String part : enabled.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (me.equals(cn)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isBatteryOptimizationIgnored(Context c) {
        if (c == null) return true;
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable e) {
            return true;
        }
    }

    /** Only what is needed to run. Battery and the speech pack are optional and never block START. */
    public static int missing(Context c) {
        if (c == null) return OK;
        return uz.saydo.app.util.SetupPolicy.firstMissing(
                Settings.canDrawOverlays(c),
                accessibilityOn(c),
                c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                Build.VERSION.SDK_INT >= 33,
                Build.VERSION.SDK_INT < 33
                        || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    /** True once per install if background battery limits are on: offer the page, do not force it. */
    public static boolean shouldOfferBattery(Context c) {
        if (c == null) return false;
        return uz.saydo.app.util.SetupPolicy.shouldOfferBattery(Build.VERSION.SDK_INT,
                isBatteryOptimizationIgnored(c), SettingsRepository.getInstance(c).isBatteryAsked());
    }

    public static boolean isLocked(Context c) {
        if (c == null) return false;
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable e) {
            return false;
        }
    }

    public static Intent accessibilityIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        String flat = new ComponentName(c, GestureService.class).flattenToString();
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", flat);
        i.putExtra(":settings:fragment_args_key", flat);
        i.putExtra(":settings:show_fragment_args", args);
        return i;
    }

    public static void route(Context c, int step) {
        if (c == null) return;
        Intent i;
        String msg;
        switch (step) {
            case NEED_OVERLAY:
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + c.getPackageName()));
                msg = "Allow SayDo to display over other apps";
                break;
            case NEED_ACCESSIBILITY:
                i = accessibilityIntent(c);
                msg = "Turn on SayDo. If greyed out: App info > 3 dots > Allow restricted settings";
                break;
            case NEED_MIC:
                i = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_ASK_MIC, true);
                msg = "Allow microphone for voice shortcuts";
                break;
            case NEED_NOTIF:
                i = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_ASK_NOTIF, true);
                msg = "Allow notifications for background service";
                break;
            case NEED_BATTERY:
                SettingsRepository.getInstance(c).setBatteryAsked(true);
                // Attempt OEM deep power management first
                if (OemPermissionHelper.launchBestAutostartSetting(c)) {
                    Toast.makeText(c.getApplicationContext(),
                            "Allow Autostart / Unrestricted background battery for SayDo",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (Build.VERSION.SDK_INT >= 23) {
                    i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + c.getPackageName()));
                    msg = "Allow background activity so voice shortcuts work with screen off";
                } else {
                    return;
                }
                break;
            default:
                return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            Toast.makeText(c.getApplicationContext(), "Could not open settings", Toast.LENGTH_SHORT).show();
        }
    }
}
