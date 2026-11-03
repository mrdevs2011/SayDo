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

/**
 * Avtomatik sozlash zanjiri (Auto Start onboarding):
 * Foydalanuvchi START bosganda barcha zarur ruxsatlar va konfiguratsiyalarni
 * ketma-ket aniqlaydi va tegishli Android sozlamasini ochib beradi:
 * 1. Overlay (Display over other apps)
 * 2. Accessibility (GestureService)
 * 3. Microphone (RECORD_AUDIO)
 * 4. Notifications (POST_NOTIFICATIONS, Android 13+)
 * 5. Battery optimization (Doze mode exemption)
 * 6. Offline voice package / Voice input setup
 */
final class Setup {
    static final int OK = 0;
    static final int NEED_OVERLAY = 1;
    static final int NEED_ACCESSIBILITY = 2;
    static final int NEED_MIC = 3;
    static final int NEED_NOTIF = 4;
    static final int NEED_BATTERY = 5;
    static final int NEED_VOICE_PACK = 6;

    private Setup() {}

    static boolean accessibilityOn(Context c) {
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
        } catch (Exception ignored) { }
        return false;
    }

    static boolean isBatteryOptimizationIgnored(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    /** Birinchi yetishmayotgan qadam yoki OK. */
    static int missing(Context c) {
        if (!Settings.canDrawOverlays(c)) return NEED_OVERLAY;
        if (!accessibilityOn(c)) return NEED_ACCESSIBILITY;
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return NEED_MIC;
        }
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return NEED_NOTIF;
        }
        if (Build.VERSION.SDK_INT >= 23 && !isBatteryOptimizationIgnored(c) && !Store.batteryAsked(c)) {
            return NEED_BATTERY;
        }
        if (!Store.voiceSetupGuided(c)) {
            return NEED_VOICE_PACK;
        }
        return OK;
    }

    static boolean isLocked(Context c) {
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }

    static Intent accessibilityIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        String flat = new ComponentName(c, GestureService.class).flattenToString();
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", flat);
        i.putExtra(":settings:fragment_args_key", flat);
        i.putExtra(":settings:show_fragment_args", args);
        return i;
    }

    /** Oflayn ovoz kiritish sozlamalari ekranini ochadi */
    static void openVoiceSettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            Toast.makeText(c.getApplicationContext(), "Offline speech recognition > English (US) pack", Toast.LENGTH_LONG).show();
            return;
        } catch (Exception ignored) { }

        try {
            Intent i = new Intent(Settings.ACTION_LOCALE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            Toast.makeText(c.getApplicationContext(), "Voice input > Offline speech recognition", Toast.LENGTH_LONG).show();
            return;
        } catch (Exception ignored) { }

        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) { }
    }

    /** Berilgan qadam uchun kerakli sozlama ekranini ochadi. */
    static void route(Context c, int step) {
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
                if (Build.VERSION.SDK_INT >= 23) {
                    i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + c.getPackageName()));
                    msg = "Allow background activity so voice shortcuts work with screen off";
                } else {
                    return;
                }
                break;
            case NEED_VOICE_PACK:
                openVoiceSettings(c);
                return;
            default:
                return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(c.getApplicationContext(), "Could not open settings", Toast.LENGTH_SHORT).show();
        }
    }
}
