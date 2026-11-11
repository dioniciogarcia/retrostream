package com.tirso.tirsolador;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Library {
    private final SharedPreferences prefs;
    public final List<Game> games = new ArrayList<>();
    public boolean demo;
    public Library(Context context) {
        prefs = context.getSharedPreferences("tirsolador", Context.MODE_PRIVATE);
        String catalog = prefs.getString("catalog", null);
        demo = catalog == null;
        try {
            if (demo) {
                try (InputStream in = context.getAssets().open("demo-catalog.json")) {
                    catalog = new String(readLimited(in, 2_000_000), StandardCharsets.UTF_8);
                }
            }
            games.addAll(parse(catalog));
        } catch (Exception e) { games.clear(); }
    }
    static byte[] readLimited(InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > max) throw new java.io.IOException(T.t("Archivo demasiado grande"));
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
    static List<Game> parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.getInt("schemaVersion") != 1) throw new JSONException(T.t("Versión de catálogo no compatible"));
        JSONArray a = root.getJSONArray("games");
        if (a.length() > 50000) throw new JSONException(T.t("Máximo 50000 juegos"));
        List<Game> parsed = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            Game g = Game.fromJson(a.getJSONObject(i));
            if (!ids.add(g.id)) throw new JSONException(T.t("ID duplicado: ") + g.id);
            parsed.add(g);
        }
        java.util.Collections.sort(parsed, (aGame, bGame) -> aGame.title.compareToIgnoreCase(bGame.title));
        return parsed;
    }
    public void importCatalog(String json) throws JSONException {
        List<Game> valid = parse(json);
        prefs.edit().putString("catalog", json).apply();
        games.clear(); games.addAll(valid); demo = false;
    }
    public boolean dark() { return prefs.getBoolean("dark", true); }
    public void dark(boolean value) { prefs.edit().putBoolean("dark", value).apply(); }
    public String theme() { return prefs.getString("theme", "Moderno"); }
    public void theme(String value) { prefs.edit().putString("theme", value).apply(); }
    public boolean descending() { return prefs.getBoolean("descending", false); }
    public void descending(boolean value) { prefs.edit().putBoolean("descending", value).apply(); }
    public String homeConsole() { return prefs.getString("homeConsole", "NES"); }
    public void homeConsole(Console c) { prefs.edit().putString("homeConsole", c.name()).apply(); }
    public boolean visible(Console c) { return prefs.getBoolean("visible_" + c.name(), true); }
    public void visible(Console c, boolean visible) { prefs.edit().putBoolean("visible_" + c.name(), visible).apply(); }
    public Set<String> favorites() { return new HashSet<>(prefs.getStringSet("favorites", new HashSet<>())); }
    public boolean favorite(Game g) { return favorites().contains(g.id); }
    public void toggleFavorite(Game g) {
        Set<String> f = favorites(); if (!f.add(g.id)) f.remove(g.id);
        prefs.edit().putStringSet("favorites", f).apply();
    }
    public List<Game> filtered(Console c, boolean favorite, String query) {
        List<Game> result = new ArrayList<>();
        for (Game g : games) if (visible(g.console) && (c == null || c == g.console)
                && (!favorite || favorite(g)) && g.title.toLowerCase(java.util.Locale.ROOT)
                .contains(query.toLowerCase(java.util.Locale.ROOT))) result.add(g);
        if (descending()) java.util.Collections.reverse(result);
        return result;
    }
    public java.util.List<String> recent() {
        java.util.List<String> out = new ArrayList<>(); String raw = prefs.getString("recent", "");
        for (String id : raw.split("\n")) if (!id.isEmpty()) out.add(id); return out;
    }
    public void addRecent(Game g) {
        java.util.List<String> ids = recent(); ids.remove(g.id); ids.add(0, g.id);
        while (ids.size() > 30) ids.remove(ids.size() - 1);
        prefs.edit().putString("recent", android.text.TextUtils.join("\n", ids)).apply();
    }
    public int count(Console c) { int n = 0; for (Game g : games) if (g.console == c) n++; return n; }
    public void replaceTelegram(java.util.List<Game> incoming) throws JSONException {
        JSONArray array=new JSONArray();for(Game game:incoming)array.put(game.toJson());
        importCatalog(new JSONObject().put("schemaVersion",1).put("games",array).toString());
    }
    public void clearTelegram(){
        try{replaceTelegram(new java.util.ArrayList<>());}catch(JSONException ignored){ }
    }
}
