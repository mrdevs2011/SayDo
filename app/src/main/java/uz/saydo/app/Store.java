package uz.saydo.app;

import android.content.Context;

import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.data.SettingsRepository;

/**
 * Backward compatibility facade delegating to reactive MacroRepository and SettingsRepository.
 */
final class Store {
    private Store() {}

    static String getMacros(Context c) {
        if (c == null) return "[]";
        return MacroRepository.getInstance(c).toJsonString();
    }

    static void saveMacros(Context c, String json) {
        if (c == null) return;
        MacroRepository.getInstance(c).importFromJsonString(json);
    }

    static String getLang(Context c) {
        if (c == null) return SettingsRepository.DEFAULT_LANG;
        return SettingsRepository.getInstance(c).getLanguage();
    }

    static boolean isEnabled(Context c) {
        if (c == null) return false;
        return SettingsRepository.getInstance(c).isMasterOn();
    }

    static void setEnabled(Context c, boolean on) {
        if (c == null) return;
        SettingsRepository.getInstance(c).setMasterOn(on);
    }

    static boolean batteryAsked(Context c) {
        if (c == null) return true;
        return SettingsRepository.getInstance(c).isBatteryAsked();
    }

    static void setBatteryAsked(Context c) {
        if (c == null) return;
        SettingsRepository.getInstance(c).setBatteryAsked(true);
    }
}
