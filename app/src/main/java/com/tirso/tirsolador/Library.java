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
    private final Context context;
    public final List<Game> games = new ArrayList<>();
    public boolean demo;
    public Library(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("tirsolador", Context.MODE_PRIVATE);
        // Big Console in dark mode is the default look (applied once to existing installs too).
        if (!prefs.getBoolean("default_console_dark", false)) prefs.edit().putString("theme", "Consola completa").putBoolean("dark_console", true).putBoolean("default_console_dark", true).apply();
        String catalog = prefs.getString("catalog", null);
        demo = catalog == null;
        try {
            if (demo) {
                try (InputStream in = context.getAssets().open("demo-catalog.json")) {
                    catalog = new String(readLimited(in, 2_000_000), StandardCharsets.UTF_8);
                }
            }
            games.addAll(parse(catalog));
            // Phone app: games found in folders on the phone join the Telegram ones.
            String local = prefs.getString("local_catalog", null);
            if (BuildConfig.PHONE && local != null) {
                List<Game> found = parse(local);
                if (!found.isEmpty()) { if (demo) { games.clear(); demo = false; } games.addAll(found); }
            }
            if (!demo) {
                for (int i = 0; i < games.size(); i++) games.set(i, GameMeta.apply(context, games.get(i)));
                java.util.Collections.sort(games, (a, b) -> a.title.compareToIgnoreCase(b.title));
            }
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
    public boolean dark(String themeKey) { return prefs.getBoolean("dark_" + themeKey, !themeKey.equals("gba")); }
    public void dark(String themeKey, boolean value) { prefs.edit().putBoolean("dark_" + themeKey, value).apply(); }
    public void dark(boolean value) { prefs.edit().putBoolean("dark", value).apply(); }
    public String theme() { return prefs.getString("theme", "Consola completa"); }
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
        List<Game> result = new ArrayList<>(); Set<String> hidden = hidden();
        for (Game g : games) if (visible(g.console) && (c == null || c == g.console) && !hidden.contains(g.id)
                && (!favorite || favorite(g)) && g.title.toLowerCase(java.util.Locale.ROOT)
                .contains(query.toLowerCase(java.util.Locale.ROOT))) result.add(g);
        String sort = sort();
        if (sort.equals("added")) java.util.Collections.sort(result, (a, b) -> Long.compare(b.messageId, a.messageId));
        else if (sort.equals("played")) { java.util.Map<String, Long> t = new java.util.HashMap<>(); for (Game g : result) t.put(g.id, PlayTime.get(context, g.id));
            java.util.Collections.sort(result, (a, b) -> Long.compare(t.get(b.id), t.get(a.id))); }
        else if (sort.equals("recent")) { java.util.List<String> r = recent();
            java.util.Collections.sort(result, (a, b) -> { int x = r.indexOf(a.id), y = r.indexOf(b.id); return Integer.compare(x < 0 ? Integer.MAX_VALUE : x, y < 0 ? Integer.MAX_VALUE : y); }); }
        else if (descending()) java.util.Collections.reverse(result);
        return result;
    }
    /** How games are ordered: name (A-Z / Z-A), added (newest in Telegram first), played (most time), recent (last played). */
    public String sort() { return prefs.getString("sort", "name"); }
    public void sort(String value) { prefs.edit().putString("sort", value).apply(); }
    /** Games you hid: they stay in Telegram but don't show in the library. */
    public Set<String> hidden() { return new HashSet<>(prefs.getStringSet("hidden", new HashSet<>())); }
    public void hide(Game g, boolean hide) { Set<String> h = hidden(); if (hide) h.add(g.id); else h.remove(g.id); prefs.edit().putStringSet("hidden", h).apply(); }
    public List<Game> hiddenGames() { Set<String> h = hidden(); List<Game> out = new ArrayList<>(); for (Game g : games) if (h.contains(g.id)) out.add(g); return out; }
    /** Takes a game off "Recently played" (its play history stays); playing it again brings it back. */
    public void removeRecent(Game g) {
        java.util.List<String> ids = recent(); ids.remove(g.id);
        prefs.edit().putString("recent", android.text.TextUtils.join("\n", ids)).putLong("unrecent_" + g.id, System.currentTimeMillis()).apply();
    }
    /** "Recently played": the play history minus what you took off it. */
    public java.util.List<Object[]> recentPlays() {
        java.util.List<Object[]> out = new ArrayList<>(); long all = prefs.getLong("unrecent_all", 0);
        for (Object[] r : plays()) { long t = (Long) r[1]; if (t > all && t > prefs.getLong("unrecent_" + ((Game) r[0]).id, 0)) out.add(r); }
        return out;
    }
    public void clearRecents() { prefs.edit().remove("recent").putLong("unrecent_all", System.currentTimeMillis()).apply(); }
    public java.util.List<String> recent() {
        java.util.List<String> out = new ArrayList<>(); String raw = prefs.getString("recent", "");
        for (String id : raw.split("\n")) if (!id.isEmpty()) out.add(id); return out;
    }
    public void addRecent(Game g) {
        java.util.List<String> ids = recent(); ids.remove(g.id); ids.add(0, g.id);
        while (ids.size() > 30) ids.remove(ids.size() - 1);
        // Every time a game is played: "<time>|<game id>" lines, newest first (kept for the played history).
        String log = prefs.getString("plays", ""); java.util.List<String> lines = new ArrayList<>();
        lines.add(System.currentTimeMillis() + "|" + g.id);
        for (String l : log.split("\n")) if (!l.isEmpty() && lines.size() < 300) lines.add(l);
        prefs.edit().putString("recent", android.text.TextUtils.join("\n", ids)).putString("plays", android.text.TextUtils.join("\n", lines)).apply();
    }
    /** Played history, newest first: {game, time in ms}. Games no longer in the library are skipped. */
    public java.util.List<Object[]> plays() {
        java.util.List<Object[]> out = new ArrayList<>();
        for (String l : prefs.getString("plays", "").split("\n")) {
            int bar = l.indexOf('|'); if (bar < 0) continue;
            try { long t = Long.parseLong(l.substring(0, bar)); String id = l.substring(bar + 1);
                for (Game g : games) if (g.id.equals(id)) { out.add(new Object[]{g, t}); break; } } catch (NumberFormatException ignored) { }
        }
        return out;
    }
    public int count(Console c) { int n = 0; for (Game g : games) if (g.console == c) n++; return n; }
    // ------------------------------------------------------------------ collections, completed, new games
    private JSONObject collectionsJson() { try { return new JSONObject(prefs.getString("collections", "{}")); } catch (JSONException e) { return new JSONObject(); } }
    /** Your own lists ("Couch co-op", "Finished"…), in the order they were made. */
    public List<String> collections() { List<String> out = new ArrayList<>(); java.util.Iterator<String> it = collectionsJson().keys(); while (it.hasNext()) out.add(it.next()); return out; }
    public Set<String> collection(String name) { Set<String> out = new HashSet<>(); JSONArray a = collectionsJson().optJSONArray(name); if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i)); return out; }
    public void collect(String name, Game g, boolean in) {
        try { JSONObject all = collectionsJson(); Set<String> ids = collection(name); if (in) ids.add(g.id); else ids.remove(g.id);
            all.put(name, new JSONArray(ids)); prefs.edit().putString("collections", all.toString()).apply(); } catch (JSONException ignored) { }
    }
    public void createCollection(String name) { try { JSONObject all = collectionsJson(); if (!all.has(name)) { all.put(name, new JSONArray()); prefs.edit().putString("collections", all.toString()).apply(); } } catch (JSONException ignored) { } }
    public void deleteCollection(String name) { JSONObject all = collectionsJson(); all.remove(name); prefs.edit().putString("collections", all.toString()).apply(); }
    public void renameCollection(String from, String to) {
        try { JSONObject all = collectionsJson(); if (!all.has(from) || all.has(to)) return; JSONObject out = new JSONObject();
            java.util.Iterator<String> it = all.keys(); while (it.hasNext()) { String k = it.next(); out.put(k.equals(from) ? to : k, all.get(k)); }
            prefs.edit().putString("collections", out.toString()).apply(); } catch (JSONException ignored) { }
    }
    public boolean completed(Game g) { return prefs.getStringSet("completed", new HashSet<>()).contains(g.id); }
    public void completed(Game g, boolean done) { Set<String> s = new HashSet<>(prefs.getStringSet("completed", new HashSet<>())); if (done) s.add(g.id); else s.remove(g.id); prefs.edit().putStringSet("completed", s).apply(); }
    private JSONObject addedJson() { try { return new JSONObject(prefs.getString("added_at", "{}")); } catch (JSONException e) { return new JSONObject(); } }
    /** Games that showed up in your channels in the last [days] days, newest first. */
    public List<Game> newGames(int days) {
        JSONObject added = addedJson(); long since = System.currentTimeMillis() - days * 86_400_000L; Set<String> hidden = hidden();
        List<Game> out = new ArrayList<>();
        for (Game g : games) if (added.optLong(g.id, 0) > since && !hidden.contains(g.id) && visible(g.console)) out.add(g);
        java.util.Collections.sort(out, (a, b) -> { int c = Long.compare(added.optLong(b.id), added.optLong(a.id)); return c != 0 ? c : Long.compare(b.messageId, a.messageId); });
        return out;
    }
    /** Remembers when each game first appeared (not on the first catalog, nor when a whole channel is assigned at once). */
    private void noteNew(java.util.List<Game> incoming) {
        Set<String> before = new HashSet<>();
        try { String old = prefs.getString("catalog", null); if (old != null) for (Game g : parse(old)) before.add(g.id); } catch (Exception ignored) { }
        if (before.isEmpty()) return;
        List<Game> fresh = new ArrayList<>(); for (Game g : incoming) if (!before.contains(g.id)) fresh.add(g);
        if (fresh.isEmpty() || fresh.size() > 150) return;
        try { JSONObject added = addedJson(); long now = System.currentTimeMillis();
            for (Game g : fresh) if (!added.has(g.id)) added.put(g.id, now);
            JSONObject kept = new JSONObject(); java.util.Iterator<String> it = added.keys();
            while (it.hasNext()) { String k = it.next(); long t = added.optLong(k); if (now - t < 60L * 86_400_000L) kept.put(k, t); }
            prefs.edit().putString("added_at", kept.toString()).apply(); } catch (JSONException ignored) { }
    }
    public void replaceTelegram(java.util.List<Game> incoming) throws JSONException {
        noteNew(incoming);
        JSONArray array=new JSONArray();for(Game game:incoming)array.put(game.toJson());
        importCatalog(new JSONObject().put("schemaVersion",1).put("games",array).toString());
    }
    public void replaceLocal(java.util.List<Game> found) {
        try { JSONArray array = new JSONArray(); for (Game g : found) array.put(g.toJson());
            prefs.edit().putString("local_catalog", new JSONObject().put("schemaVersion", 1).put("games", array).toString()).apply(); } catch (JSONException ignored) { }
    }
    public void clearTelegram(){
        try{replaceTelegram(new java.util.ArrayList<>());}catch(JSONException ignored){ }
    }
}
