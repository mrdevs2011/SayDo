package uz.voiceflow.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Makrolar va sozlamalarni saqlash. Thread-safe commit. */
final class Store {
    private static final String PREFS = "voiceflow";
    private static final String K_MACROS = "macros";
    private static final String K_LANG = "lang";
    private static final String K_ENABLED = "master_on";
    private static final int MAX_MACROS_JSON = 512 * 1024; // 512 KB

    static final String[] LANGS = {"uz-UZ", "ru-RU", "en-US"};

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
        if (json.length() > MAX_MACROS_JSON) {
            // Juda katta — saqlamaymiz (buzilishdan himoya)
            return;
        }
        // Minimal JSON tekshiruv
        String t = json.trim();
        if (!t.startsWith("[") || !t.endsWith("]")) return;
        try {
            sp(c).edit().putString(K_MACROS, json).commit();
        } catch (Exception ignored) { }
    }

    static String getLang(Context c) {
        try {
            String l = sp(c).getString(K_LANG, LANGS[0]);
            for (String s : LANGS) if (s.equals(l)) return l;
        } catch (Exception ignored) { }
        return LANGS[0];
    }

    static String nextLang(Context c) {
        String cur = getLang(c);
        int idx = 0;
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(cur)) idx = i;
        String next = LANGS[(idx + 1) % LANGS.length];
        try { sp(c).edit().putString(K_LANG, next).commit(); } catch (Exception ignored) { }
        return next;
    }

    static boolean isEnabled(Context c) {
        try { return sp(c).getBoolean(K_ENABLED, false); }
        catch (Exception e) { return false; }
    }

    static void setEnabled(Context c, boolean on) {
        try { sp(c).edit().putBoolean(K_ENABLED, on).commit(); }
        catch (Exception ignored) { }
    }
}
