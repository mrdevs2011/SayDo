package uz.saydo.app.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OEM Power Management & Autostart Diagnostics Helper.
 * Handles aggressive vendor battery savers across Xiaomi, Samsung, Huawei, Oppo, Vivo, and OnePlus.
 */
public final class OemPermissionHelper {
    private static final String TAG = "OemPermissionHelper";

    public static final String OEM_XIAOMI = "xiaomi";
    public static final String OEM_SAMSUNG = "samsung";
    public static final String OEM_HUAWEI = "huawei";
    public static final String OEM_HONOR = "honor";
    public static final String OEM_OPPO = "oppo";
    public static final String OEM_REALME = "realme";
    public static final String OEM_ONEPLUS = "oneplus";
    public static final String OEM_VIVO = "vivo";

    private OemPermissionHelper() {}

    /**
     * Returns true if device manufacturer matches given name (case-insensitive).
     */
    public static boolean isManufacturer(String manufacturer) {
        if (manufacturer == null || Build.MANUFACTURER == null) return false;
        return Build.MANUFACTURER.toLowerCase(Locale.US).contains(manufacturer.toLowerCase(Locale.US));
    }

    /**
     * Checks if given intent can be handled by an activity installed on device.
     */
    public static boolean canResolveIntent(Context context, Intent intent) {
        if (context == null || intent == null) return false;
        try {
            PackageManager pm = context.getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return list != null && !list.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Generates a ranked list of candidate intents for vendor autostart / battery management.
     */
    public static List<Intent> getVendorAutostartIntents(Context context) {
        List<Intent> intents = new ArrayList<>();
        String pkg = context.getPackageName();

        // 1. Xiaomi / Redmi / Poco (MIUI / HyperOS)
        if (isManufacturer(OEM_XIAOMI) || isManufacturer("redmi") || isManufacturer("poco")) {
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")));
            intents.add(new Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT));
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings")));
        }

        // 2. Samsung (OneUI / Smart Manager)
        if (isManufacturer(OEM_SAMSUNG)) {
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity")));
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity")));
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity")));
        }

        // 3. Huawei / Honor (EMUI / MagicOS)
        if (isManufacturer(OEM_HUAWEI) || isManufacturer(OEM_HONOR)) {
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")));
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")));
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")));
        }

        // 4. Oppo / Realme (ColorOS / RealmeUI)
        if (isManufacturer(OEM_OPPO) || isManufacturer(OEM_REALME)) {
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")));
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity")));
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity")));
        }

        // 5. OnePlus (OxygenOS)
        if (isManufacturer(OEM_ONEPLUS)) {
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")));
        }

        // 6. Vivo / iQOO (FuntouchOS / OriginOS)
        if (isManufacturer(OEM_VIVO) || isManufacturer("iqoo")) {
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")));
            intents.add(new Intent().setComponent(new ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")));
        }

        // 7. Standard Android Doze exemption & App Details Fallback
        if (Build.VERSION.SDK_INT >= 23) {
            intents.add(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + pkg)));
            intents.add(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }

        intents.add(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + pkg)));

        return intents;
    }

    /**
     * Attempts to launch the highest-priority resolved OEM autostart or battery setting.
     * Returns true if an activity was successfully launched.
     */
    public static boolean launchBestAutostartSetting(Context context) {
        if (context == null) return false;
        List<Intent> candidates = getVendorAutostartIntents(context);
        for (Intent intent : candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (canResolveIntent(context, intent)) {
                try {
                    context.startActivity(intent);
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "Failed launching intent: " + intent, t);
                }
            }
        }
        return false;
    }
}
