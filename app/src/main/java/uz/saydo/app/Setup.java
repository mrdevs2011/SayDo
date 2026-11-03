package uz.saydo.app;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Sozlash zanjiri: Overlay -> Accessibility -> Mikrofon.
 * To'liq sozlanmagan bo'lsa START bosilganda kerakli joyga o'tkazadi
 * (trigger so'ralishidan oldin).
 */
final class Setup {
    static final int OK = 0;
    static final int NEED_OVERLAY = 1;
    static final int NEED_ACCESSIBILITY = 2;
    static final int NEED_MIC = 3;

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

    /** Birinchi yetishmayotgan qadam yoki OK. */
    static int missing(Context c) {
        if (!Settings.canDrawOverlays(c)) return NEED_OVERLAY;
        if (!accessibilityOn(c)) return NEED_ACCESSIBILITY;
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return NEED_MIC;
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
                msg = "Turn on SayDo. If greyed out: App info > menu > Allow restricted settings";
                break;
            case NEED_MIC:
                i = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_ASK_MIC, true);
                msg = "Allow the microphone";
                break;
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
