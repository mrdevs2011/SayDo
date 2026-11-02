package uz.voiceflow.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Makrolar va sozlamalarni saqlash. Activity ham, servislar ham shu yerdan o'qiydi. */
final class Store {
    private static final String PREFS = "voiceflow";
    private static final String K_MACROS = "macros";
    private static final String K_LANG = "lang";

    static final String[] LANGS = {"uz-UZ", "ru-RU", "en-US"};

    private Store() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getMacros(Context c) {
        return sp(c).getString(K_MACROS, "[]");
    }

    static void saveMacros(Context c, String json) {
        if (json == null || json.isEmpty()) json = "[]";
        sp(c).edit().putString(K_MACROS, json).commit();
    }

    static String getLang(Context c) {
        String l = sp(c).getString(K_LANG, LANGS[0]);
        for (String s : LANGS) if (s.equals(l)) return l;
        return LANGS[0];
    }

    static String nextLang(Context c) {
        String cur = getLang(c);
        int idx = 0;
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i].equals(cur)) idx = i;
        String next = LANGS[(idx + 1) % LANGS.length];
        sp(c).edit().putString(K_LANG, next).commit();
        return next;
    }
}
