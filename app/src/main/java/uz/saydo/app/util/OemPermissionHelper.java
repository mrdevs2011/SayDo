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
        return manufacturerMatches(Build.MANUFACTURER, manufacturer);
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
     * One settings screen to try: either an explicit component or an action
     * (optionally with a "package:" data URI). Pure data, so the fallback order is unit-testable.
     */
    public static final class Target {
        public final String action;    // null for explicit components
        public final String pkg;       // component package, or null
        public final String cls;       // component class, or null
        public final String category;  // optional
        public final boolean packageUri;

        private Target(String action, String pkg, String cls, String category, boolean packageUri) {
            this.action = action;
            this.pkg = pkg;
            this.cls = cls;
            this.category = category;
            this.packageUri = packageUri;
        }

        static Target component(String pkg, String cls) {
            return new Target(null, pkg, cls, null, false);
        }

        static Target action(String action, String category, boolean packageUri) {
            return new Target(action, null, null, category, packageUri);
        }

        @Override public String toString() {
            return action != null ? action : pkg + "/" + cls;
        }
    }

    static boolean manufacturerMatches(String manufacturer, String name) {
        return manufacturer != null && name != null
                && manufacturer.toLowerCase(Locale.US).contains(name.toLowerCase(Locale.US));
    }

    /**
     * Ranked autostart / battery screens for a manufacturer: vendor screens first, then the
     * standard Android battery-optimization screens, and the App Info page as the last resort
     * (it exists on every device).
     */
    public static List<Target> autostartTargets(String manufacturer, int sdkInt) {
        List<Target> t = new ArrayList<>();

        // 1. Xiaomi / Redmi / Poco (MIUI / HyperOS)
        if (manufacturerMatches(manufacturer, OEM_XIAOMI) || manufacturerMatches(manufacturer, "redmi")
                || manufacturerMatches(manufacturer, "poco")) {
            t.add(Target.component("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            t.add(Target.action("miui.intent.action.OP_AUTO_START", Intent.CATEGORY_DEFAULT, false));
            t.add(Target.component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"));
        }

        // 2. Samsung (OneUI / Smart Manager)
        if (manufacturerMatches(manufacturer, OEM_SAMSUNG)) {
            t.add(Target.component("com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"));
            t.add(Target.component("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"));
            t.add(Target.component("com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"));
        }

        // 3. Huawei / Honor (EMUI / MagicOS)
        if (manufacturerMatches(manufacturer, OEM_HUAWEI) || manufacturerMatches(manufacturer, OEM_HONOR)) {
            t.add(Target.component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            t.add(Target.component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.bootstart.BootStartActivity"));
            t.add(Target.component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"));
        }

        // 4. Oppo / Realme (ColorOS / RealmeUI)
        if (manufacturerMatches(manufacturer, OEM_OPPO) || manufacturerMatches(manufacturer, OEM_REALME)) {
            t.add(Target.component("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
            t.add(Target.component("com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"));
            t.add(Target.component("com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"));
        }

        // 5. OnePlus (OxygenOS)
        if (manufacturerMatches(manufacturer, OEM_ONEPLUS)) {
            t.add(Target.component("com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"));
        }

        // 6. Vivo / iQOO (FuntouchOS / OriginOS)
        if (manufacturerMatches(manufacturer, OEM_VIVO) || manufacturerMatches(manufacturer, "iqoo")) {
            t.add(Target.component("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
            t.add(Target.component("com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"));
        }

        // 7. Standard Android Doze exemption & App Details fallback
        if (sdkInt >= 23) {
            t.add(Target.action(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, null, true));
            t.add(Target.action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, null, false));
        }
        t.add(Target.action(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, null, true));
        return t;
    }

    /**
     * Generates a ranked list of candidate intents for vendor autostart / battery management.
     */
    public static List<Intent> getVendorAutostartIntents(Context context) {
        String pkg = context.getPackageName();
        List<Intent> intents = new ArrayList<>();
        for (Target t : autostartTargets(Build.MANUFACTURER, Build.VERSION.SDK_INT)) {
            Intent i = t.action != null ? new Intent(t.action) : new Intent();
            if (t.pkg != null) i.setComponent(new ComponentName(t.pkg, t.cls));
            if (t.category != null) i.addCategory(t.category);
            if (t.packageUri) i.setData(Uri.parse("package:" + pkg));
            intents.add(i);
        }
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
