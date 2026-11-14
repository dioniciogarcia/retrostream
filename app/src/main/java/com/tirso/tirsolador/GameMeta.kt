package com.tirso.tirsolador

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Game names, descriptions, genres and release dates. The data comes from OpenVGDB (the same source Tirso
 * Compiler uses): the database is downloaded once, every game is matched once, and the result is kept in
 * storage so the app never has to look anything up again when it opens.
 */
internal object GameMeta {
    private const val VERSION = 3
    private const val DB_URL = "https://github.com/OpenVGDB/OpenVGDB/releases/latest/download/openvgdb.zip"
    const val CREDITS = "Description: OpenVGDB, Wikipedia · Pictures: libretro thumbnails"
    private val SYSTEM = mapOf(
        Console.N64 to 23, Console.GBA to 20, Console.GBC to 21, Console.GB to 19, Console.NDS to 24,
        Console.SNES to 26, Console.NES to 25, Console.MD to 33, Console.MAME to 2,
        Console.PSP to 39, Console.GC to 22,   // PS2 and Wii aren't in OpenVGDB: covers/names come from libretro's list, text from Wikipedia
    )
    /** Games the lookup handles: Telegram ones and games found in the phone's folders. */
    private fun ours(g: Game) = g.telegramAccount != 0L || g.id.startsWith("local:")
    private val main = Handler(Looper.getMainLooper())
    private var cache = JSONObject()
    private var loaded = false
    @Volatile private var running = false

    private fun store(ctx: Context) = File(ctx.filesDir, "gamemeta.json")

    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = store(ctx)
            if (f.isFile) {
                val j = JSONObject(f.readText())
                if (j.optInt("_v") == VERSION) cache = j
            }
        }
    }

    @Synchronized private fun save(ctx: Context) {
        runCatching {
            cache.put("_v", VERSION)
            val tmp = File(ctx.filesDir, "gamemeta.json.tmp")
            tmp.writeText(cache.toString())
            if (!tmp.renameTo(store(ctx))) { store(ctx).delete(); tmp.renameTo(store(ctx)) }
        }
    }

    // ------------------------------------------------------------------ names
    private val REGIONS = "USA|Europe|Japan|World|Korea|Australia|Asia|Germany|France|Spain|Italy|Brazil|China|Netherlands|Sweden|Canada|Russia|UK"
    private val TRAIL_LANG = Regex("(\\s+[A-Za-z]{2}(,[A-Za-z]{2})+)+$")
    private val TRAIL_REGION = Regex("(\\s+($REGIONS))+$", RegexOption.IGNORE_CASE)
    /** Some dumps end in "Europe En,Fr,De" instead of "(Europe) (En,Fr,De)": take those tails off. */
    private fun tails(s: String): String {
        var t = s.trim()
        t = TRAIL_LANG.replace(t, "").trim()
        t = TRAIL_REGION.replace(t, "").trim()
        return t.ifEmpty { s }
    }
    private fun fix(s: String) = s.replace(Regex("(?<=[A-Za-z])_s\\b"), "'s").replace('_', ' ')

    /** "Mario_Kart_64 (Europe)" -> "Mario Kart 64"; "Legend of Zelda, The (U) [!]" -> "The Legend of Zelda". */
    @JvmStatic fun clean(stem: String): String {
        var s = tails(fix(stem).replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), ""))
        s = Regex("^(.*), (The|A|An)$").replace(s.trim()) { "${it.groupValues[2]} ${it.groupValues[1]}" }
        s = s.replace(Regex("\\s+"), " ").trim(' ', '-', '.')
        return s.ifBlank { stem }
    }

    private fun ascii(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "").lowercase().replace("&", "and")
    private fun key(s: String): String {
        var t = tails(ascii(s).replace(Regex("[(\\[].*?[)\\]]"), "").trim())
        t = Regex("^(.*), (the|a|an)$").replace(t) { "${it.groupValues[2]} ${it.groupValues[1]}" }
        t = t.replace(Regex("^(the|a|an) "), "")
        return t.replace(Regex("[^a-z0-9]"), "")
    }
    private fun words(s: String): Set<String> =
        Regex("[a-z0-9]+").findAll(tails(ascii(s).replace(Regex("[(\\[].*?[)\\]]"), ""))).map { it.value }.filter { it !in setOf("the", "a", "an", "of") }.toSet()

    private fun regionOf(stem: String): String {
        val t = Regex("[(\\[]([^)\\]]*)[)\\]]").findAll(stem).joinToString(" ") { it.groupValues[1] }.lowercase()
        val tail = TRAIL_REGION.find(Regex("[(\\[][^)\\]]*[)\\]]").replace(stem, "").trim().let { TRAIL_LANG.replace(it, "").trim() })?.value?.lowercase().orEmpty()
        return when {
            "usa" in tail -> "USA"; "europe" in tail -> "Europe"; "japan" in tail -> "Japan"; "world" in tail -> "World"
            Regex("\\busa\\b|\\bu\\b|\\bus\\b").containsMatchIn(t) -> "USA"
            "europe" in t || Regex("\\be\\b").containsMatchIn(t) -> "Europe"
            "japan" in t || Regex("\\bj\\b").containsMatchIn(t) -> "Japan"
            "world" in t -> "World"
            else -> ""
        }
    }

    /** The name a game file has without its extension(s). */
    @JvmStatic fun stemOf(g: Game): String {
        var s = g.fileName
        if (s.isBlank()) return g.title
        s = s.substringBeforeLast('.')
        if (s.lowercase().let { it.endsWith(".nes") || it.endsWith(".sfc") || it.endsWith(".smc") || it.endsWith(".gba") || it.endsWith(".gb") || it.endsWith(".gbc") || it.endsWith(".nds") || it.endsWith(".n64") || it.endsWith(".z64") || it.endsWith(".v64") || it.endsWith(".md") || it.endsWith(".gen") || it.endsWith(".bin") }) s = s.substringBeforeLast('.')
        return s.ifBlank { g.title }
    }

    /** libretro thumbnail file names replace these characters with "_". */
    @JvmStatic fun thumbName(name: String) = name.replace(Regex("[&*/:`<>?\\\\|\"]"), "_")

    // ------------------------------------------------------------------ applying results
    /** The game with its clean name, description and the cover name that matches libretro's thumbnails. */
    @JvmStatic fun apply(ctx: Context, g: Game): Game {
        if (!ours(g)) return g
        load(ctx)
        val m = cache.optJSONObject(g.id)
        val title = m?.optString("t")?.takeIf { it.isNotBlank() } ?: clean(stemOf(g))
        val desc = m?.optString("d").orEmpty()
        val cover = m?.optString("c")?.takeIf { it.isNotBlank() } ?: g.coverName
        return Game(g.id, title, g.console, desc, cover, g.telegramAccount, g.chatId, g.messageId, g.fileId, g.fileName, g.fileSize)
    }

    /** Everything the "Game information" window shows. */
    @JvmStatic fun info(ctx: Context, g: Game): JSONObject {
        load(ctx)
        val m = cache.optJSONObject(g.id) ?: JSONObject()
        return JSONObject().put("title", g.title).put("console", g.console.name.lowercase()).put("description", g.description)
            .put("genre", m.optString("g")).put("year", m.optInt("y")).put("month", m.optInt("m")).put("region", m.optString("r"))
            .put("developer", m.optString("dev")).put("publisher", m.optString("pub")).put("credits", CREDITS)
    }

    /** Other names libretro may know a game's cover by (tried in this order). */
    @JvmStatic fun coverNames(ctx: Context, g: Game): List<String> {
        load(ctx)
        val m = cache.optJSONObject(g.id)
        val base = clean(stemOf(g))
        val out = linkedSetOf<String>()
        m?.optString("c")?.takeIf { it.isNotBlank() }?.let { out += it }
        out += thumbName(stemOf(g))
        val reg = regionOf(stemOf(g))
        for (r in listOf(reg, "USA", "Europe", "World", "Japan").filter { it.isNotEmpty() }.distinct()) out += thumbName("$base ($r)")
        return out.toList()
    }

    // ------------------------------------------------------------------ lookup
    private class Row(val rom: String, val title: String, val region: String, val genre: String, val date: String, val desc: String, val dev: String, val pub: String)
    private class Index(val rows: List<Row>) {
        val byRom = HashMap<String, MutableList<Row>>()
        val byKey = HashMap<String, MutableList<Row>>()
        val wl = rows.map { words(it.title.ifBlank { it.rom }) to it }
        init {
            for (r in rows) {
                byRom.getOrPut(r.rom.lowercase()) { mutableListOf() } += r
                for (k in setOf(key(r.rom), key(r.title))) if (k.isNotEmpty()) byKey.getOrPut(k) { mutableListOf() } += r
            }
        }
    }

    private fun pick(c: List<Row>, reg: String) = c.maxByOrNull { (if (it.desc.isNotBlank()) 4 else 0) + (if (it.region == reg) 2 else 0) + (if (it.region == "USA") 1 else 0) }!!

    private fun match(idx: Index, stem: String): Row? {
        val reg = regionOf(stem)
        idx.byRom[stem.lowercase()]?.let { return pick(it, reg) }
        idx.byRom[fix(stem).lowercase()]?.let { return pick(it, reg) }
        idx.byKey[key(stem)]?.let { return pick(it, reg) }
        val w = words(fix(stem))
        if (w.size >= 2) {
            var best: Row? = null; var bs = 0.0
            for ((ws, r) in idx.wl) {
                if (ws.isEmpty()) continue
                val j = w.intersect(ws).size.toDouble() / w.union(ws).size
                if (j > bs) { bs = j; best = r }
            }
            if (bs >= 0.8) return best
        }
        return null
    }

    private fun index(db: SQLiteDatabase, sid: Int): Index {
        val rows = ArrayList<Row>()
        db.rawQuery(
            "select r.romExtensionlessFileName, e.releaseTitleName, e.TEMPregionLocalizedName, e.releaseGenre, e.releaseDate, e.releaseDescription, e.releaseDeveloper, e.releasePublisher " +
                "from ROMs r left join RELEASES e on e.romID = r.romID where r.systemID = ?", arrayOf(sid.toString())
        ).use { c ->
            while (c.moveToNext()) rows += Row(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: "")
        }
        return Index(rows)
    }

    private fun entry(g: Game, stem: String, r: Row?): JSONObject {
        val o = JSONObject()
        if (r == null) return o.put("t", clean(stem))
        val mame = g.console == Console.MAME
        val title = (if (r.title.isNotBlank()) r.title else r.rom).let { if (mame) it.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "") else it }
        o.put("t", title.trim())
        o.put("c", thumbName(if (mame) r.title.ifBlank { r.rom } else r.rom.ifBlank { r.title }))
        if (r.desc.isNotBlank()) o.put("d", r.desc.trim())
        if (r.genre.isNotBlank()) o.put("g", r.genre.split(',').joinToString(", ") { it.trim() })
        if (r.region.isNotBlank()) o.put("r", r.region)
        Regex("([A-Za-z]{3})[a-z]*\\s*\\d*,?\\s*(\\d{4})").find(r.date)?.let { m ->
            o.put("y", m.groupValues[2].toInt())
            val mon = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(m.groupValues[1].lowercase()) + 1
            if (mon > 0) o.put("m", mon)
        } ?: Regex("(\\d{4})").find(r.date)?.let { o.put("y", it.groupValues[1].toInt()) }
        if (r.dev.isNotBlank()) o.put("dev", r.dev.trim())
        if (r.pub.isNotBlank()) o.put("pub", r.pub.trim())
        return o
    }

    private fun platform(c: Console) = when (c) {
        Console.N64 -> "Nintendo 64"; Console.NDS -> "Nintendo DS"; Console.GBA -> "Game Boy Advance"; Console.GBC -> "Game Boy Color"
        Console.GB -> "Game Boy"; Console.SNES -> "Super Nintendo"; Console.NES -> "Nintendo Entertainment System"; Console.MD -> "Mega Drive"
        Console.PSP -> "PlayStation Portable"; Console.PS2 -> "PlayStation 2"; Console.GC -> "GameCube"; Console.WII -> "Wii"; else -> "arcade"
    }

    /** Wikipedia's intro for a game the database doesn't know (null when nothing convincing is found). */
    private fun wiki(title: String, console: Console): String? = runCatching {
        val q = java.net.URLEncoder.encode("$title ${platform(console)} video game", "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=$q&gsrlimit=4&prop=extracts&exintro=1&explaintext=1&exsentences=4&exlimit=4"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "RetroStream/1.0 (Android TV retro game launcher)")
        if (c.responseCode != 200) return@runCatching null
        val json = JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@runCatching null
        val want = words(title)
        var best: Pair<Int, String>? = null
        for (k in pages.keys()) {
            val p = pages.getJSONObject(k)
            val name = p.optString("title").replace(Regex("\\s*\\([^)]*\\)"), "")
            val extract = p.optString("extract").trim()
            val have = words(name)
            if (want.isEmpty() || have.isEmpty() || extract.length < 60 || !extract.contains("game", true)) continue
            val j = want.intersect(have).size.toDouble() / want.union(have).size
            if (j >= 0.6 && (best == null || p.optInt("index") < best.first)) best = p.optInt("index") to extract
        }
        best?.second
    }.getOrNull()

    private fun database(ctx: Context): File? {
        val db = File(ctx.filesDir, "openvgdb.sqlite")
        if (db.isFile && db.length() > 1_000_000) return db
        return runCatching {
            val c = URL(DB_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            if (c.responseCode != 200) return null
            val tmp = File(ctx.filesDir, "openvgdb.tmp")
            ZipInputStream(c.inputStream).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: return null
                    if (e.name.endsWith("openvgdb.sqlite") && !e.name.startsWith("__MACOSX")) {
                        tmp.outputStream().use { out -> zip.copyTo(out) }
                        break
                    }
                }
            }
            if (!tmp.renameTo(db)) return null
            db
        }.getOrNull()
    }

    // ------------------------------------------------------------------ libretro's list of pictures
    /** Every box art name libretro has for a console (kept for two weeks; call off the main thread). */
    @JvmStatic fun names(ctx: Context, console: Console): List<String> {
        val f = File(ctx.filesDir, "thumbs/${console.name}.txt")
        if (f.isFile && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < 14L * 86_400_000) return f.readLines().filter { it.isNotBlank() }
        val list = runCatching {
            val url = "https://thumbnails.libretro.com/" + android.net.Uri.encode(console.thumbnailSystem) + "/Named_Boxarts/"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 30000; c.setRequestProperty("User-Agent", "RetroStream/1.0")
            if (c.responseCode != 200) return@runCatching null
            val html = c.inputStream.use { it.readBytes().decodeToString() }
            Regex("<a href=\"[^\"]+\">([^<]+)\\.png</a>").findAll(html).map { android.text.Html.fromHtml(it.groupValues[1], 0).toString().trim() }.filter { it.isNotEmpty() }.distinct().toList()
        }.getOrNull()
        if (list.isNullOrEmpty()) return if (f.isFile) f.readLines().filter { it.isNotBlank() } else emptyList()
        runCatching { f.parentFile?.mkdirs(); f.writeText(list.joinToString("\n")) }
        return list
    }
    /** The libretro picture name that best fits a file name (null when nothing is close enough). */
    private fun artMatch(names: List<String>, stem: String): String? {
        if (names.isEmpty()) return null
        val k = key(stem); val reg = regionOf(stem)
        fun score(n: String) = (if (reg.isNotEmpty() && n.contains("($reg)")) 2 else 0) + (if (n.contains("(USA)")) 1 else 0) - (if (n.contains("(Beta") || n.contains("(Demo") || n.contains("(Proto")) 3 else 0)
        names.filter { key(it) == k }.maxByOrNull { score(it) }?.let { return it }
        val w = words(fix(stem)); if (w.isEmpty()) return null
        var best: String? = null; var bs = 0.0
        for (n in names) {
            val ws = words(n); if (ws.isEmpty()) continue
            val j = w.intersect(ws).size.toDouble() / w.union(ws).size + score(n) * 0.01
            if (j > bs) { bs = j; best = n }
        }
        return if (bs >= 0.6) best else null
    }

    // ------------------------------------------------------------------ manual search and fixes
    /**
     * Games that fit what you typed, from the game database and from libretro's pictures (call off the main thread).
     * Each result: t (name), c (picture name), and when known y (year), r (region), d (description), g, dev, pub.
     */
    @JvmStatic fun search(ctx: Context, console: Console, query: String): List<JSONObject> {
        val want = words(fix(query)); if (want.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Double, JSONObject>>(); val seen = HashSet<String>()
        val app = ctx.applicationContext
        SYSTEM[console]?.let { sid ->
            database(app)?.let { file ->
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val dummy = Game("search", query, console, "", query)
                    for ((ws, r) in index(db, sid).wl) {
                        if (!ws.containsAll(want)) continue
                        val e = entry(dummy, r.rom, r)
                        val c = e.optString("c"); if (c.isEmpty() || !seen.add(c.lowercase())) continue
                        out += (want.size.toDouble() / ws.size + (if (r.desc.isNotBlank()) 0.2 else 0.0)) to e
                    }
                }
            }
        }
        for (n in runCatching { names(app, console) }.getOrDefault(emptyList())) {
            val ws = words(n); if (!ws.containsAll(want) || !seen.add(n.lowercase())) continue
            out += (want.size.toDouble() / ws.size) to JSONObject().put("t", clean(n)).put("c", n)
        }
        return out.sortedByDescending { it.first }.take(60).map { it.second }
    }
    /** Uses a search result for this game (fills in the description from Wikipedia when it has none; call off the main thread). */
    @JvmStatic fun choose(ctx: Context, g: Game, pick: JSONObject) {
        val app = ctx.applicationContext; load(app)
        val e = JSONObject(pick.toString()).put("manual", true)
        if (e.optString("d").isBlank() && g.console != Console.MAME) wiki(e.optString("t"), g.console)?.let { e.put("d", it) }
        synchronized(this) { cache.put(g.id, e) }; save(app)
    }
    @JvmStatic fun rename(ctx: Context, g: Game, title: String) = edit(ctx, g) { it.put("t", title.trim()) }
    @JvmStatic fun setCover(ctx: Context, g: Game, name: String) = edit(ctx, g) { it.put("c", name) }
    private fun edit(ctx: Context, g: Game, change: (JSONObject) -> Unit) {
        val app = ctx.applicationContext; load(app)
        synchronized(this) {
            val e = cache.optJSONObject(g.id) ?: JSONObject().put("t", g.title)
            change(e); e.put("manual", true); cache.put(g.id, e)
        }
        save(app)
    }
    /** Your own fixes (name, cover, description) for every game, for the library sync. */
    @JvmStatic fun manual(ctx: Context): JSONObject {
        val app = ctx.applicationContext; load(app); val out = JSONObject()
        synchronized(this) {
            val it = cache.keys()
            while (it.hasNext()) { val k = it.next(); val e = cache.optJSONObject(k) ?: continue
                if (e.optBoolean("manual")) out.put(k, JSONObject(e.toString()).apply { remove("manual") }) }
        }
        return out
    }
    /** Uses the fixes from your other devices: [all] holds every game's own fix (games missing from it go back to automatic). */
    @JvmStatic fun setManual(ctx: Context, all: JSONObject) {
        val app = ctx.applicationContext; load(app)
        synchronized(this) {
            val drop = ArrayList<String>(); val it = cache.keys()
            while (it.hasNext()) { val k = it.next(); if (cache.optJSONObject(k)?.optBoolean("manual") == true && !all.has(k)) drop += k }
            drop.forEach { cache.remove(it) }
            val ks = all.keys(); while (ks.hasNext()) { val k = ks.next(); all.optJSONObject(k)?.let { cache.put(k, JSONObject(it.toString()).put("manual", true)) } }
        }
        save(app)
    }
    /** Forgets what was found or chosen for a game, so the automatic lookup runs again. */
    @JvmStatic fun reset(ctx: Context, g: Game) { val app = ctx.applicationContext; load(app); synchronized(this) { cache.remove(g.id) }; save(app) }
    /** True when the game has a description. */
    @JvmStatic fun described(ctx: Context, g: Game): Boolean { load(ctx.applicationContext); return cache.optJSONObject(g.id)?.optString("d").orEmpty().isNotBlank() }
    /** True when the lookup has already been tried for this game. */
    @JvmStatic fun looked(ctx: Context, g: Game): Boolean { load(ctx.applicationContext); return cache.has(g.id) }

    /** Looks up every game that hasn't been looked up yet (in the background). [done] runs on the main thread when something new was found. */
    @JvmStatic fun refresh(ctx: Context, games: List<Game>, done: Runnable) {
        val app = ctx.applicationContext
        load(app)
        val todo = synchronized(this) { games.filter { ours(it) && !cache.has(it.id) } }
        if (todo.isEmpty() || running) return
        running = true
        Thread {
            try {
                val file = database(app) ?: return@Thread
                val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                db.use {
                    for ((console, list) in todo.groupBy { it.console }) {
                        val sid = SYSTEM[console]
                        val idx = if (sid != null) index(db, sid) else null
                        var art: List<String>? = null
                        for (g in list) {
                            val stem = stemOf(g)
                            val row = idx?.let { runCatching { match(it, stem) }.getOrNull() }
                            val e = entry(g, stem, row)
                            if (row == null && console != Console.MAME) {
                                // Not in the database: libretro's own list of cover names usually still has the game.
                                if (art == null) art = runCatching { names(app, console) }.getOrDefault(emptyList())
                                artMatch(art!!, stem)?.let { name -> e.put("c", name); e.put("t", clean(name)) }
                            }
                            if ((row == null || row.desc.isBlank()) && console != Console.MAME) wiki(e.optString("t"), console)?.let { e.put("d", it) }
                            synchronized(this) { cache.put(g.id, e) }
                        }
                        save(app)
                    }
                }
                save(app)
                main.post(done)
            } catch (e: Throwable) {
                android.util.Log.w("RetroStream", "Game data lookup failed", e)
            } finally { running = false }
        }.start()
    }
}
