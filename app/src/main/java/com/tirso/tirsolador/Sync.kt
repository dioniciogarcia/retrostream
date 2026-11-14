package com.tirso.tirsolador

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps favorites, collections, hidden and completed games, "Play later", the played history and play time the same
 * on every device signed in with the same Telegram account (phone, TV and PC).
 * They travel as one small JSON file in a private channel, "TD - RetroStream - Library sync" (same format as the PC app):
 *   {rs:"sync", v:1, device, items:{ key: {v: value or null, t: time changed} }}
 * Keys: f:<game> favorite · h:<game> hidden · c:<game> completed · l:<game> play later · col:<name> collection (ids)
 *       pl:<time>:<game> one play · ur:<game> / ur:* removed from recents at · pt:<device>:<game file name> play time on that device
 * Also: gs:<game> this game's settings {res, filter} · m:<game> your fix of its name/cover/description · co:<game file name> cheats on (codes)
 *       bind:<CONSOLE> channel assigned (chat id) · vc:<CONSOLE> console hidden (false) · hs:<section> home section shown · pd:<id> TV/PC paired with the phone remote
 * The newest change wins; plays and play time only add up.
 */
internal object Sync {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var busy = false
    private var again = false
    private var applying = false
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val watchers = LinkedHashSet<Runnable>()
    private val run = Runnable { app?.let { cycle(it) } }
    private val tick = object : Runnable { override fun run() { if (watchers.isNotEmpty()) soon(500); main.postDelayed(this, 180_000) } }
    private val WATCH = setOf("favorites", "hidden", "completed", "later", "collections", "plays")
    private const val KEEP_PLAYS = 300
    private const val TOMB_DAYS = 60L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tirsolador", Context.MODE_PRIVATE)

    /** This device's name in the sync file (play time is counted per device and added up). */
    @JvmStatic fun device(ctx: Context): String {
        val p = prefs(ctx); var id = p.getString("sync_device", null)
        if (id.isNullOrEmpty()) { id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10); p.edit().putString("sync_device", id).apply() }
        return id!!
    }

    /** Called when a screen opens: syncs soon, and again whenever something changes. [onChange] redraws that screen. */
    @JvmStatic fun watch(ctx: Context, onChange: Runnable) {
        init(ctx); watchers.add(onChange); soon(1500)
    }
    private var idleUntil = 0L
    /** Last sync when leaving the menus (a game may start: while it plays, nothing is synced). */
    @JvmStatic fun unwatch(onChange: Runnable) { watchers.remove(onChange); if (watchers.isEmpty()) { idleUntil = System.currentTimeMillis() + 15_000; soon(300) } }

    private fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext; app = c
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (!applying && k != null && (k in WATCH || k.startsWith("unrecent_") || k.startsWith("visible_"))) soon(4000) }
        listener = l; prefs(c).registerOnSharedPreferenceChangeListener(l)
        main.postDelayed(tick, 180_000)
    }

    @JvmStatic fun soon(ms: Long) {
        if (app == null) return
        main.removeCallbacks(run); main.postDelayed(run, ms)
    }

    private fun stateFile(ctx: Context, account: Long) = File(ctx.filesDir, "sync/state_$account.json")
    private fun loadState(ctx: Context, account: Long): JSONObject =
        runCatching { JSONObject(stateFile(ctx, account).readText()) }.getOrDefault(JSONObject())
    private fun saveState(ctx: Context, account: Long, st: JSONObject) {
        runCatching { val f = stateFile(ctx, account); f.parentFile?.mkdirs(); val tmp = File(f.path + ".tmp"); tmp.writeText(st.toString()); tmp.renameTo(f) }
    }

    private fun cycle(ctx: Context) {
        val drive = TirsoDrive.get(ctx)
        if (!drive.ready()) return
        if (watchers.isEmpty() && System.currentTimeMillis() > idleUntil) return
        if (busy) { again = true; return }
        busy = true
        val account = drive.accountId()
        drive.syncChannel { chat, _ ->
            if (chat == null || chat == 0L || account != drive.accountId()) { finish(); return@syncChannel }
            drive.syncDocs(chat) { docs, _ ->
                if (docs == null) { finish(); return@syncDocs }
                val st = loadState(ctx, account)
                val newest = docs.firstOrNull()
                if (newest != null && newest.messageId > st.optLong("seen")) {
                    drive.downloadSync(newest.fileId) { path, _ ->
                        val remote = path?.let { p -> runCatching { JSONObject(File(p).readText()).optJSONObject("items") }.getOrNull() }
                        if (remote == null) { finish(); return@downloadSync }
                        merge(ctx, drive, account, chat, docs, st, remote, newest.messageId)
                    }
                } else merge(ctx, drive, account, chat, docs, st, null, 0)
            }
        }
    }

    private fun finish() {
        busy = false
        if (again) { again = false; soon(1000) }
    }

    private fun numeric(k: String) = k.startsWith("ur:") || k.startsWith("pt:")
    private val TOMB = listOf("f:", "h:", "c:", "l:", "col:", "gs:", "m:", "co:", "vc:")
    private fun tomb(k: String) = TOMB.any { k.startsWith(it) } || (BuildConfig.PHONE && k.startsWith("pd:"))
    /** Same text whatever order the fields were written in (the PC writes them in its own order). */
    private fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> keys(v).sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is String -> JSONObject.quote(v)
        is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
    private fun same(a: Any?, b: Any?) = canon(a) == canon(b)
    /** Remote wins when newer; on a tie, the same value is picked on every device. */
    private fun wins(r: JSONObject, s: JSONObject?) = s == null || r.optLong("t") > s.optLong("t") || (r.optLong("t") == s.optLong("t") && canon(r.opt("v")) > canon(s.opt("v")))
    private fun keys(o: JSONObject): List<String> { val out = ArrayList<String>(); val it = o.keys(); while (it.hasNext()) out += it.next(); return out }

    private fun merge(ctx: Context, drive: TirsoDrive, account: Long, chat: Long, docs: List<TirsoDrive.SyncDoc>, st: JSONObject, remote: JSONObject?, seenId: Long) {
        val items = st.optJSONObject("items") ?: JSONObject()
        val first = !st.optBoolean("synced")
        val now = System.currentTimeMillis()
        var dirty = st.optBoolean("dirty")
        // 1. What changed here since the last sync.
        val local = snapshot(ctx)
        for (k in keys(local)) {
            val v = local.get(k); val s = items.optJSONObject(k)
            if (s != null && same(s.opt("v"), v)) continue
            if (numeric(k) && s != null && s.optLong("v") >= (v as Number).toLong()) continue
            items.put(k, JSONObject().put("v", v).put("t", if (first) 1 else now)); dirty = true
        }
        for (k in keys(items)) if (tomb(k) && !local.has(k) && !items.getJSONObject(k).isNull("v")) { items.put(k, JSONObject().put("v", JSONObject.NULL).put("t", now)); dirty = true }
        // 2. What changed on the other devices.
        var changed = false
        if (remote != null) {
            for (k in keys(remote)) {
                val r = remote.optJSONObject(k) ?: continue; val s = items.optJSONObject(k)
                if (numeric(k)) { if (s == null || r.optLong("v") > s.optLong("v")) { items.put(k, r); changed = true }; continue }
                if (wins(r, s)) { if (s == null || !same(s.opt("v"), r.opt("v"))) changed = true; items.put(k, r) }
            }
            st.put("seen", seenId)
        }
        // 3. Old removals and very old plays are dropped.
        val plays = keys(items).filter { it.startsWith("pl:") }.sortedByDescending { it.substring(3).substringBefore(':').toLongOrNull() ?: 0 }
        for (k in plays.drop(KEEP_PLAYS)) items.remove(k)
        for (k in keys(items)) { val s = items.getJSONObject(k); if (s.isNull("v") && now - s.optLong("t") > TOMB_DAYS * 86_400_000L) items.remove(k) }
        if (remote != null) dirty = !sameItems(items, remote)
        else if (docs.isEmpty() && items.length() > 0) dirty = true
        // 4. This device now shows the merged library.
        if (changed || first) materialize(ctx, items)
        st.put("items", items).put("synced", true).put("dirty", dirty)
        saveState(ctx, account, st)
        if (changed) for (w in watchers.toList()) runCatching { w.run() }
        if (!dirty) { finish(); return }
        // 5. Send the merged file; older copies are removed (the newest two are kept).
        val dir = File(ctx.cacheDir, "sync"); dir.mkdirs(); dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "library-$now.json")
        runCatching { f.writeText(JSONObject().put("rs", "sync").put("v", 1).put("device", device(ctx)).put("items", items).toString()) }
            .onFailure { finish(); return }
        drive.uploadBackup(chat, f, "#RSSYNC\ndevice:" + device(ctx)) { id, _ ->
            if (id != null && id > 0) {
                st.put("seen", id).put("dirty", false); saveState(ctx, account, st)
                val old = docs.drop(1).map { it.messageId }.filter { it != id }
                drive.deleteMessages(chat, old.toLongArray())
            }
            finish()
        }
    }

    private fun sameItems(a: JSONObject, b: JSONObject): Boolean {
        if (a.length() != b.length()) return false
        for (k in keys(a)) { val x = a.getJSONObject(k); val y = b.optJSONObject(k) ?: return false
            if (!same(x.opt("v"), y.opt("v")) || x.optLong("t") != y.optLong("t")) return false }
        return true
    }

    /** This device's library as sync items. */
    private fun snapshot(ctx: Context): JSONObject {
        val p = prefs(ctx); val o = JSONObject()
        for ((pref, pre) in listOf("favorites" to "f:", "hidden" to "h:", "completed" to "c:", "later" to "l:"))
            for (id in p.getStringSet(pref, emptySet()) ?: emptySet()) if (id.isNotEmpty()) o.put(pre + id, true)
        val cols = runCatching { JSONObject(p.getString("collections", "{}") ?: "{}") }.getOrDefault(JSONObject())
        for (n in keys(cols)) {
            val a = cols.optJSONArray(n) ?: JSONArray()
            val ids = (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSortedSet()
            o.put("col:$n", JSONArray(ids.toList()))
        }
        for (l in (p.getString("plays", "") ?: "").split("\n")) { val b = l.indexOf('|'); if (b > 0 && l.substring(0, b).toLongOrNull() != null) o.put("pl:" + l.substring(0, b) + ":" + l.substring(b + 1), true) }
        for ((k, v) in p.all) if (k.startsWith("unrecent_") && v is Long && v > 0) o.put(if (k == "unrecent_all") "ur:*" else "ur:" + k.removePrefix("unrecent_"), v)
        val me = device(ctx)
        File(ctx.filesDir, "playtime").listFiles()?.forEach { f ->
            val ms = runCatching { f.readText().trim().toLong() }.getOrNull() ?: return@forEach
            if (ms >= 60_000) o.put("pt:$me:${f.name}", ms)
        }
        snapshotMore(ctx, o)
        return o
    }

    private const val MULTI = 4 // Context.MODE_MULTI_PROCESS: the game runs in its own process and writes these too
    private fun gid(id: String) = id.replace(Regex("[^A-Za-z0-9]"), "_")
    private fun fileName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Game settings, info fixes, cheats, channels, consoles shown, home sections, paired TVs/PCs. */
    @Suppress("DEPRECATION")
    private fun snapshotMore(ctx: Context, o: JSONObject) {
        val po = ctx.getSharedPreferences(Presets.OPTIONS, MULTI); val tp = ctx.getSharedPreferences("tirso_player", MULTI)
        // this game's settings
        val ids = HashSet<String>(po.getStringSet("sync_gs_ids", emptySet()) ?: emptySet())
        for (k in po.all.keys) if (k.startsWith("resolution_g_")) ids += k.removePrefix("resolution_g_")
        val owns = tp.all.keys.filter { it.startsWith("own_") && tp.getBoolean(it, false) }.map { it.removePrefix("own_") }.toSet()
        if (owns.any { g -> ids.none { gid(it) == g } }) runCatching { for (g in Library(ctx).games) if (gid(g.id) in owns) ids += g.id }
        for (id in ids) {
            val res = po.getString("resolution_g_$id", null); val g = gid(id)
            val filter = if (tp.getBoolean("own_$g", false)) tp.getString("screen_filter_g_$g", null) else null
            if (res != null || filter != null) o.put("gs:$id", JSONObject().apply { res?.let { put("res", it) }; filter?.let { put("filter", it) } })
        }
        // your fixes of names, covers and descriptions
        val man = GameMeta.manual(ctx); for (k in keys(man)) o.put("m:$k", man.get(k))
        // cheats that are on (by the game's file name; the engine keys them by the ROM's fingerprint)
        val names = HashSet<String>()
        for (k in tp.all.keys) if (k.startsWith("cheats_sync_")) names += k.removePrefix("cheats_sync_")
        File(ctx.filesDir, "game_sha").listFiles()?.forEach { names += it.name }
        for (n in names) {
            val sha = runCatching { File(ctx.filesDir, "game_sha/$n").takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf { it.length >= 16 }
            val raw = (sha?.let { tp.getString("cheats_on" + it.take(16), null) }) ?: tp.getString("cheats_sync_$n", null) ?: continue
            val a = runCatching { JSONArray(raw) }.getOrNull() ?: continue
            o.put("co:$n", JSONArray((0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSortedSet().toList()))
        }
        // channels assigned to each console
        val b = TirsoDrive.get(ctx).bindingsJson(); for (k in keys(b)) { val v = b.optLong(k); if (v != 0L) o.put("bind:$k", v) }
        // consoles shown and home sections
        val lib = prefs(ctx)
        for (c in Console.values()) if (!lib.getBoolean("visible_" + c.name, true)) o.put("vc:" + c.name, false)
        for (sec in HomeLayout.SECTIONS) o.put("hs:$sec", HomeLayout.shown(ctx, sec))
        // TVs and PCs this phone controls
        if (BuildConfig.PHONE) for (d in RemoteClient.saved(ctx)) if (d.token.isNotEmpty()) o.put("pd:" + d.id, JSONObject().put("name", d.name).put("kind", d.kind).put("host", d.host).put("port", d.port).put("token", d.token))
    }

    @Suppress("DEPRECATION")
    private fun materializeMore(ctx: Context, items: JSONObject) {
        val po = ctx.getSharedPreferences(Presets.OPTIONS, MULTI); val tp = ctx.getSharedPreferences("tirso_player", MULTI)
        val gs = HashMap<String, JSONObject>(); val man = JSONObject(); val co = HashMap<String, JSONArray>(); val bind = JSONObject()
        val hidden = HashSet<String>(); val hs = HashMap<String, Boolean>(); val pd = ArrayList<RemoteClient.Device>()
        for (k in keys(items)) {
            val v = items.getJSONObject(k).opt("v"); if (v == null || v == JSONObject.NULL) continue
            when {
                k.startsWith("gs:") -> (v as? JSONObject)?.let { gs[k.substring(3)] = it }
                k.startsWith("m:") -> (v as? JSONObject)?.let { man.put(k.substring(2), it) }
                k.startsWith("co:") -> (v as? JSONArray)?.let { co[k.substring(3)] = it }
                k.startsWith("bind:") -> (v as? Number)?.let { bind.put(k.substring(5), it.toLong()) }
                k.startsWith("vc:") -> if (v == false) hidden += k.substring(3)
                k.startsWith("hs:") -> (v as? Boolean)?.let { hs[k.substring(3)] = it }
                k.startsWith("pd:") -> (v as? JSONObject)?.let { d -> pd += RemoteClient.Device(k.substring(3), d.optString("name"), d.optString("kind", "tv"), d.optString("host"), d.optInt("port", Remote.PORT), d.optString("token")) }
            }
        }
        // this game's settings
        val pe = po.edit(); val te = tp.edit()
        val before = po.getStringSet("sync_gs_ids", emptySet()) ?: emptySet()
        for (id in before + po.all.keys.filter { it.startsWith("resolution_g_") }.map { it.removePrefix("resolution_g_") }) if (id !in gs) {
            pe.remove("resolution_g_$id"); te.remove("screen_filter_g_" + gid(id))
        }
        for ((id, v) in gs) {
            val res = v.optString("res"); val filter = v.optString("filter"); val g = gid(id)
            if (res.isNotEmpty()) pe.putString("resolution_g_$id", res) else pe.remove("resolution_g_$id")
            if (filter.isNotEmpty()) te.putString("screen_filter_g_$g", filter).putBoolean("own_$g", true) else te.remove("screen_filter_g_$g")
        }
        pe.putStringSet("sync_gs_ids", HashSet(gs.keys))
        // cheats
        for (k in tp.all.keys) if (k.startsWith("cheats_sync_") && k.removePrefix("cheats_sync_") !in co) te.remove(k)
        for ((n, codes) in co) {
            te.putString("cheats_sync_$n", codes.toString())
            val sha = runCatching { File(ctx.filesDir, "game_sha/$n").takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
            if (sha != null && sha.length >= 16) te.putString("cheats_on" + sha.take(16), codes.toString())
        }
        pe.commit(); te.commit()
        GameMeta.setManual(ctx, man)
        // consoles shown
        val le = prefs(ctx).edit(); for (c in Console.values()) le.putBoolean("visible_" + c.name, c.name !in hidden); le.commit()
        for ((sec, on) in hs) if (sec in HomeLayout.SECTIONS && HomeLayout.shown(ctx, sec) != on) HomeLayout.shown(ctx, sec, on)
        if (BuildConfig.PHONE) {
            val keep = RemoteClient.saved(ctx).filter { it.token.isEmpty() && pd.none { p -> p.id == it.id } }
            RemoteClient.save(ctx, pd + keep)
        }
        if (bind.length() > 0) TirsoDrive.get(ctx).syncBindings(bind)
    }

    /** Writes the merged items into this device's settings. */
    private fun materialize(ctx: Context, items: JSONObject) {
        val p = prefs(ctx); val me = device(ctx)
        val sets = mapOf("f:" to HashSet<String>(), "h:" to HashSet<String>(), "c:" to HashSet<String>(), "l:" to HashSet<String>())
        val cols = LinkedHashMap<String, JSONArray>(); val plays = ArrayList<Pair<Long, String>>()
        val ur = HashMap<String, Long>(); val others = JSONObject()
        for (k in keys(items)) {
            val v = items.getJSONObject(k).opt("v"); if (v == null || v == JSONObject.NULL) continue
            when {
                k.startsWith("col:") -> cols[k.substring(4)] = v as? JSONArray ?: JSONArray()
                k.startsWith("pl:") -> { val rest = k.substring(3); val c = rest.indexOf(':'); val t = if (c > 0) rest.substring(0, c).toLongOrNull() else null; if (t != null) plays += t to rest.substring(c + 1) }
                k.startsWith("ur:") -> (v as? Number)?.let { ur[k.substring(3)] = it.toLong() }
                k.startsWith("pt:") -> { val rest = k.substring(3); val c = rest.indexOf(':')
                    if (c > 0 && rest.substring(0, c) != me) { val name = rest.substring(c + 1); others.put(name, others.optLong(name) + ((v as? Number)?.toLong() ?: 0)) } }
                else -> sets[k.substring(0, 2)]?.add(k.substring(2))
            }
        }
        val current = runCatching { JSONObject(p.getString("collections", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val outCols = JSONObject()
        for (n in keys(current)) cols[n]?.let { outCols.put(n, it) }
        for ((n, a) in cols) if (!outCols.has(n)) outCols.put(n, a)
        plays.sortByDescending { it.first }
        val lines = plays.take(KEEP_PLAYS).joinToString("\n") { "${it.first}|${it.second}" }
        applying = true
        try {
            val e = p.edit().putStringSet("favorites", sets["f:"]).putStringSet("hidden", sets["h:"]).putStringSet("completed", sets["c:"]).putStringSet("later", sets["l:"])
                .putString("collections", outCols.toString()).putString("plays", lines)
            for ((id, t) in ur) { val key = if (id == "*") "unrecent_all" else "unrecent_$id"; if (t > p.getLong(key, 0)) e.putLong(key, t) }
            e.commit()
        } finally { applying = false }
        PlayTime.others(ctx, others)
        runCatching { materializeMore(ctx, items) }
    }
}
