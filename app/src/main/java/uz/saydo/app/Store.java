package uz.saydo.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Macro and settings storage. Always English (en-US). */
final class Store {
    private static final String PREFS = "saydo";
    private static final String K_MACROS = "macros";
    private static final String K_LANG = "lang";
    private static final String K_ENABLED = "master_on";
    private static final String K_BATT = "batt_asked";
    private static final int MAX_MACROS_JSON = 512 * 1024;

    private static final String LANG = "en-US";

    private Store() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getMacros(Context c) {
        try {
            String s = sp(c).getString(K_MACROS, "[]");
            return (s == null || s.isEmpty()) ? "[]" : s;
        } catch (Exception e) {
            return "[]";
        }
    }

    static void saveMacros(Context c, String json) {
        if (json == null || json.isEmpty()) json = "[]";
        if (json.length() > MAX_MACROS_JSON) return;
        String t = json.trim();
        if (!t.startsWith("[") || !t.endsWith("]")) return;
        try {
            sp(c).edit().putString(K_MACROS, json).commit();
        } catch (Exception ignored) { }
    }

    /** Always en-US — no language switching. */
    static String getLang(Context c) {
        return LANG;
    }

    static boolean isEnabled(Context c) {
        try { return sp(c).getBoolean(K_ENABLED, false); }
        catch (Exception e) { return false; }
    }

    static void setEnabled(Context c, boolean on) {
        try { sp(c).edit().putBoolean(K_ENABLED, on).commit(); }
        catch (Exception ignored) { }
    }

    static boolean batteryAsked(Context c) {
        try { return sp(c).getBoolean(K_BATT, false); }
        catch (Exception e) { return true; }
    }

    static void setBatteryAsked(Context c) {
        try { sp(c).edit().putBoolean(K_BATT, true).commit(); }
        catch (Exception ignored) { }
    }
}
