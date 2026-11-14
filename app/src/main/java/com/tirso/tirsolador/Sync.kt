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
    @JvmStatic fun unwatch(onChange: Runnable) { watchers.remove(onChange); if (watchers.isEmpty()) soon(800) }

    private fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext; app = c
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (!applying && k != null && (k in WATCH || k.startsWith("unrecent_"))) soon(4000) }
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
    private fun tomb(k: String) = k.startsWith("f:") || k.startsWith("h:") || k.startsWith("c:") || k.startsWith("l:") || k.startsWith("col:")
    private fun same(a: Any?, b: Any?) = (a ?: JSONObject.NULL).toString() == (b ?: JSONObject.NULL).toString()
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
                if (s == null || r.optLong("t") > s.optLong("t")) { if (s == null || !same(s.opt("v"), r.opt("v"))) changed = true; items.put(k, r) }
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
        return o
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
    }
}
