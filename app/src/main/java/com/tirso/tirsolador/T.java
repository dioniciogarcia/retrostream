package com.tirso.tirsolador;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Language support: texts are written in Spanish in the code; English is looked up here. */
final class T {
    private static final Map<String, String> EN = new HashMap<>();
    private static volatile String lang = "es";
    static { TEn.fill(EN); }

    static void init(Context context) {
        String saved = context.getSharedPreferences("language", Context.MODE_PRIVATE).getString("lang", "");
        if (saved.isEmpty()) saved = "es".equals(Locale.getDefault().getLanguage()) ? "es" : "en";
        lang = saved;
    }
    static void set(Context context, String code) {
        lang = "en".equals(code) ? "en" : "es";
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("lang", lang).apply();
    }
    static boolean english() { return "en".equals(lang); }
    static String t(String es) {
        if (!"en".equals(lang)) return es;
        String v = EN.get(es);
        return v == null ? es : v;
    }
}
