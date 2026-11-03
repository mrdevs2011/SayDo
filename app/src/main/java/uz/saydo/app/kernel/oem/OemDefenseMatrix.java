package uz.saydo.app.kernel.oem;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Universal OEM Defense Matrix.
 * Counters aggressive OEM background app killers across Xiaomi HyperOS, Samsung OneUI,
 * Huawei HarmonyOS, Oppo ColorOS, Vivo FuntouchOS, OnePlus OxygenOS, and Nothing OS.
 */
public final class OemDefenseMatrix {

    public enum OemBrand {
        XIAOMI,
        SAMSUNG,
        HUAWEI,
        OPPO,
        VIVO,
        ONEPLUS,
        HONOR,
        GENERIC
    }

    private OemDefenseMatrix() {
    }

    public static OemBrand detectOemBrand() {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase() : "";
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";

        if (manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
            return OemBrand.XIAOMI;
        } else if (manufacturer.contains("samsung")) {
            return OemBrand.SAMSUNG;
        } else if (manufacturer.contains("huawei")) {
            return OemBrand.HUAWEI;
        } else if (manufacturer.contains("oppo") || brand.contains("realme")) {
            return OemBrand.OPPO;
        } else if (manufacturer.contains("vivo") || brand.contains("iqoo")) {
            return OemBrand.VIVO;
        } else if (manufacturer.contains("oneplus")) {
            return OemBrand.ONEPLUS;
        } else if (manufacturer.contains("honor")) {
            return OemBrand.HONOR;
        }

        return OemBrand.GENERIC;
    }

    /**
     * Resolves tailored system intents to configure autostart and disable battery restrictions.
     */
    public static List<Intent> getOemAutostartIntents(Context context) {
        if (context == null) return Collections.emptyList();
        List<Intent> intents = new ArrayList<>();
        OemBrand brand = detectOemBrand();

        switch (brand) {
            case XIAOMI:
                // Xiaomi HyperOS / MIUI Autostart
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")));
                // Background popup permissions
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
                        .putExtra("extra_pkgname", context.getPackageName()));
                break;

            case SAMSUNG:
                // Samsung Device Care / Never Sleeping Apps
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")));
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")));
                break;

            case HUAWEI:
            case HONOR:
                // Huawei / Honor Launch Manager
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")));
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")));
                break;

            case OPPO:
                // Oppo / Realme Startup Manager
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")));
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")));
                break;

            case VIVO:
                // Vivo / iQOO Background High Power Consumption
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")));
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")));
                break;

            case ONEPLUS:
                // OnePlus OxygenOS Battery Optimization
                intents.add(new Intent().setComponent(new ComponentName(
                        "com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")));
                break;

            default:
                break;
        }

        return intents;
    }
}
