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
object GameMeta {
    private const val VERSION = 2
    private const val DB_URL = "https://github.com/OpenVGDB/OpenVGDB/releases/latest/download/openvgdb.zip"
    const val CREDITS = "Description: OpenVGDB · Pictures: libretro thumbnails"
    private val SYSTEM = mapOf(
        Console.N64 to 23, Console.GBA to 20, Console.GBC to 21, Console.GB to 19, Console.NDS to 24,
        Console.SNES to 26, Console.NES to 25, Console.MD to 33, Console.MAME to 2,
    )
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
    private fun fix(s: String) = s.replace(Regex("(?<=[A-Za-z])_s\\b"), "'s").replace('_', ' ')

    /** "Mario_Kart_64 (Europe)" -> "Mario Kart 64"; "Legend of Zelda, The (U) [!]" -> "The Legend of Zelda". */
    @JvmStatic fun clean(stem: String): String {
        var s = fix(stem).replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "")
        s = Regex("^(.*), (The|A|An)$").replace(s.trim()) { "${it.groupValues[2]} ${it.groupValues[1]}" }
        s = s.replace(Regex("\\s+"), " ").trim(' ', '-', '.')
        return s.ifBlank { stem }
    }

    private fun ascii(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "").lowercase().replace("&", "and")
    private fun key(s: String): String {
        var t = ascii(s).replace(Regex("[(\\[].*?[)\\]]"), "").trim()
        t = Regex("^(.*), (the|a|an)$").replace(t) { "${it.groupValues[2]} ${it.groupValues[1]}" }
        t = t.replace(Regex("^(the|a|an) "), "")
        return t.replace(Regex("[^a-z0-9]"), "")
    }
    private fun words(s: String): Set<String> =
        Regex("[a-z0-9]+").findAll(ascii(s).replace(Regex("[(\\[].*?[)\\]]"), "")).map { it.value }.filter { it !in setOf("the", "a", "an", "of") }.toSet()

    private fun regionOf(stem: String): String {
        val t = Regex("[(\\[]([^)\\]]*)[)\\]]").findAll(stem).joinToString(" ") { it.groupValues[1] }.lowercase()
        return when {
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
        if (g.telegramAccount == 0L) return g
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

    /** Looks up every game that hasn't been looked up yet (in the background). [done] runs on the main thread when something new was found. */
    @JvmStatic fun refresh(ctx: Context, games: List<Game>, done: Runnable) {
        val app = ctx.applicationContext
        load(app)
        val todo = synchronized(this) { games.filter { it.telegramAccount != 0L && !cache.has(it.id) } }
        if (todo.isEmpty() || running) return
        running = true
        Thread {
            try {
                val file = database(app) ?: return@Thread
                val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                db.use {
                    for ((console, list) in todo.groupBy { it.console }) {
                        val sid = SYSTEM[console] ?: continue
                        val idx = index(db, sid)
                        for (g in list) {
                            val stem = stemOf(g)
                            val e = entry(g, stem, runCatching { match(idx, stem) }.getOrNull())
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
